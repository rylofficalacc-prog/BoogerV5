package dev.booger.client.system.party;

import com.google.gson.*;
import dev.booger.client.BoogerClient;
import dev.booger.client.network.cloud.CloudApiClient;
import dev.booger.client.system.ProfileManager;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Party system — real-time multiplayer party management.
 *
 * WHAT A "PARTY" IS:
 * A named group of Booger Client players who can:
 * 1. Share HUD profiles (one player changes to "pvp" — all see it)
 * 2. See each other's stats in a shared overlay (FPS, ping, etc.)
 * 3. Broadcast party size to Discord Rich Presence ("Playing (3/4)")
 * 4. Coordinate server joins (optional: share a join code)
 *
 * ARCHITECTURE:
 * Party state is maintained on the Booger API server.
 * Clients receive live updates via Server-Sent Events (SSE):
 *   GET /party/{id}/state  →  EventStream
 *   data: { "type": "member_join", "member": { ... } }
 *   data: { "type": "profile_change", "profile": "pvp" }
 *   data: { "type": "member_leave", "member": { ... } }
 *   data: { "type": "party_dissolved" }
 *
 * SSE over HTTP/2 gives us low-latency updates (~50-200ms end-to-end)
 * without the complexity of WebSockets. Java's HttpClient supports SSE
 * natively via BodyHandlers.ofLines().
 *
 * PARTY LIFECYCLE:
 * IDLE → CREATE → ACTIVE(leader) → DISSOLVED
 *      ↘ JOIN → ACTIVE(member) → LEFT
 *
 * Only the leader can dissolve a party or change the shared profile.
 * Members can leave individually.
 *
 * MEMBER DATA (from server):
 * {
 *   "playerId":   "uuid",
 *   "username":   "Steve",
 *   "skinHash":   "abc123",  // For avatar display in party overlay
 *   "ping":       42,        // Reported by the client, not authoritative
 *   "fps":        144,       // Same
 *   "server":     "hypixel.net",
 *   "joinedAt":   1704067200
 * }
 *
 * SECURITY:
 * Party join codes are 8-character alphanumeric (e.g. "BOOGER42").
 * ~36^8 = 2.8 trillion combinations → brute force infeasible.
 * Codes expire after 10 minutes or first use, whichever comes first.
 * Player identity is verified by the API via their JWT access token.
 *
 * LOCAL INTEGRATION:
 * When the party profile changes:
 *   1. ProfileManager.switchProfile() is called
 *   2. All modules reload with the new profile's settings
 *   3. DiscordRichPresence updates party size fields
 * This all happens automatically — the player doesn't need to do anything.
 */
public final class PartyManager {

    // ─── Data classes ─────────────────────────────────────────────────────────

    public record PartyMember(
        String playerId,
        String username,
        String skinHash,
        int    ping,
        int    fps,
        String server,
        boolean isLeader,
        long   joinedAt
    ) {}

    public record PartyState(
        String         partyId,
        String         joinCode,
        List<PartyMember> members,
        String         sharedProfile,
        boolean        isLeader
    ) {}

    public enum PartyEvent {
        MEMBER_JOIN, MEMBER_LEAVE, PROFILE_CHANGE, PARTY_DISSOLVED, STATE_UPDATE
    }

    public record PartyNotification(PartyEvent event, PartyState state, PartyMember affected) {}

    // ─── State ────────────────────────────────────────────────────────────────

    private volatile PartyState currentParty = null;
    private final AtomicBoolean inParty = new AtomicBoolean(false);

    // SSE connection
    private Thread sseThread;
    private volatile boolean sseRunning = false;

    // Event listeners
    private final List<Consumer<PartyNotification>> listeners =
        new CopyOnWriteArrayList<>();

    // Heartbeat — send our current stats to the party every 5 seconds
    private final ScheduledExecutorService scheduler;

    private final CloudApiClient api;
    private final ProfileManager profileManager;
    private final DiscordRpcBridge discordBridge;

    private final Gson gson = new Gson();
    private static final String BASE = "https://api.boogerclient.dev/v1";

    public PartyManager(CloudApiClient api, ProfileManager profileManager) {
        this.api             = api;
        this.profileManager  = profileManager;
        this.discordBridge   = new DiscordRpcBridge();
        this.scheduler       = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Booger-PartyHeartbeat");
            t.setDaemon(true);
            return t;
        });
    }

    public void init() {
        BoogerClient.LOGGER.debug("PartyManager initialized");
    }

    // ─── Party Operations ─────────────────────────────────────────────────────

    /**
     * Create a new party. The calling player becomes the leader.
     * Returns the join code for sharing with friends.
     */
    public CompletableFuture<String> createParty() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                JsonObject resp = api.postJsonPublic(BASE + "/party/create", "{}");
                String partyId  = resp.get("partyId").getAsString();
                String joinCode = resp.get("joinCode").getAsString();

                currentParty = new PartyState(
                    partyId, joinCode,
                    new ArrayList<>(List.of(buildSelfMember(true))),
                    profileManager.getActiveProfile(),
                    true
                );
                inParty.set(true);

                startSseConnection(partyId);
                startHeartbeat(partyId);
                updateDiscordPartySize();

                BoogerClient.LOGGER.info("PartyManager: created party {} (code: {})",
                    partyId, joinCode);
                return joinCode;

            } catch (Exception e) {
                BoogerClient.LOGGER.error("PartyManager: createParty failed", e);
                throw new CompletionException(e);
            }
        }, scheduler);
    }

    /**
     * Join an existing party via join code.
     */
    public CompletableFuture<PartyState> joinParty(String joinCode) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                JsonObject body = new JsonObject();
                body.addProperty("joinCode", joinCode.toUpperCase().trim());

                JsonObject resp = api.postJsonPublic(BASE + "/party/join", body.toString());
                String partyId = resp.get("partyId").getAsString();

                List<PartyMember> members = parseMembers(resp.getAsJsonArray("members"));
                String sharedProfile = resp.has("sharedProfile")
                    ? resp.get("sharedProfile").getAsString()
                    : "default";

                currentParty = new PartyState(
                    partyId, joinCode, members, sharedProfile, false
                );
                inParty.set(true);

                // Apply the party's shared profile immediately
                if (!sharedProfile.equals(profileManager.getActiveProfile())) {
                    profileManager.switchProfile(sharedProfile);
                    BoogerClient.LOGGER.info("PartyManager: applied party profile '{}'", sharedProfile);
                }

                startSseConnection(partyId);
                startHeartbeat(partyId);
                updateDiscordPartySize();

                notifyListeners(new PartyNotification(
                    PartyEvent.STATE_UPDATE, currentParty, null));

                BoogerClient.LOGGER.info("PartyManager: joined party {} ({} members)",
                    partyId, members.size());
                return currentParty;

            } catch (Exception e) {
                BoogerClient.LOGGER.error("PartyManager: joinParty failed", e);
                throw new CompletionException(e);
            }
        }, scheduler);
    }

    /**
     * Leave the current party.
     */
    public CompletableFuture<Void> leaveParty() {
        return CompletableFuture.runAsync(() -> {
            if (!inParty.get() || currentParty == null) return;

            String partyId = currentParty.partyId();
            stopSseConnection();

            try {
                api.postJsonPublic(BASE + "/party/" + partyId + "/leave", "{}");
            } catch (Exception e) {
                BoogerClient.LOGGER.warn("PartyManager: leave request failed (proceeding anyway): {}", e.getMessage());
            }

            cleanupParty();
            BoogerClient.LOGGER.info("PartyManager: left party {}", partyId);
        }, scheduler);
    }

    /**
     * Change the shared party profile (leader only).
     * All members will switch to this profile automatically.
     */
    public CompletableFuture<Void> setSharedProfile(String profileName) {
        return CompletableFuture.runAsync(() -> {
            if (!inParty.get() || currentParty == null || !currentParty.isLeader()) {
                throw new IllegalStateException("Must be party leader to change profile");
            }

            try {
                JsonObject body = new JsonObject();
                body.addProperty("profile", profileName);
                api.postJsonPublic(
                    BASE + "/party/" + currentParty.partyId() + "/profile",
                    body.toString()
                );
                // Members will receive the update via SSE
                BoogerClient.LOGGER.info("PartyManager: set shared profile to '{}'", profileName);
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, scheduler);
    }

    // ─── SSE Connection ───────────────────────────────────────────────────────

    /**
     * Open a Server-Sent Events stream for real-time party updates.
     * Runs on a dedicated thread — reconnects automatically on failure.
     */
    private void startSseConnection(String partyId) {
        stopSseConnection();
        sseRunning = true;

        sseThread = new Thread(() -> {
            String url = BASE + "/party/" + partyId + "/state";
            long reconnectDelayMs = 1000;

            while (sseRunning && inParty.get()) {
                try {
                    HttpClient sseHttp = HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_2)
                        .build();

                    HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(30))
                        .header("Accept", "text/event-stream")
                        .header("Authorization", "Bearer " + api.getAccessToken())
                        .GET()
                        .build();

                    sseHttp.send(req, responseInfo -> {
                        return HttpResponse.BodySubscribers.mapping(
                            HttpResponse.BodySubscribers.ofLines(StandardCharsets.UTF_8),
                            lines -> {
                                lines.forEach(line -> {
                                    if (line.startsWith("data: ")) {
                                        handleSseEvent(line.substring(6));
                                    }
                                });
                                return null;
                            }
                        );
                    });

                    // Stream ended — reconnect
                    reconnectDelayMs = 1000; // Reset backoff on clean disconnect

                } catch (Exception e) {
                    if (!sseRunning) break;
                    BoogerClient.LOGGER.debug("PartyManager: SSE stream error — reconnecting in {}ms: {}",
                        reconnectDelayMs, e.getMessage());
                    try { Thread.sleep(reconnectDelayMs); }
                    catch (InterruptedException ie) { break; }
                    reconnectDelayMs = Math.min(reconnectDelayMs * 2, 30_000);
                }
            }
        }, "Booger-PartySSE");
        sseThread.setDaemon(true);
        sseThread.start();
    }

    private void handleSseEvent(String jsonData) {
        try {
            JsonObject event = gson.fromJson(jsonData, JsonObject.class);
            String type = event.get("type").getAsString();

            switch (type) {
                case "member_join" -> {
                    PartyMember newMember = parseMember(event.getAsJsonObject("member"));
                    if (currentParty != null) {
                        var members = new ArrayList<>(currentParty.members());
                        members.removeIf(m -> m.playerId().equals(newMember.playerId()));
                        members.add(newMember);
                        currentParty = new PartyState(
                            currentParty.partyId(), currentParty.joinCode(),
                            members, currentParty.sharedProfile(), currentParty.isLeader()
                        );
                        updateDiscordPartySize();
                        notifyListeners(new PartyNotification(
                            PartyEvent.MEMBER_JOIN, currentParty, newMember));
                    }
                }
                case "member_leave" -> {
                    String leftId = event.get("playerId").getAsString();
                    if (currentParty != null) {
                        PartyMember leftMember = currentParty.members().stream()
                            .filter(m -> m.playerId().equals(leftId))
                            .findFirst().orElse(null);
                        var members = new ArrayList<>(currentParty.members());
                        members.removeIf(m -> m.playerId().equals(leftId));
                        currentParty = new PartyState(
                            currentParty.partyId(), currentParty.joinCode(),
                            members, currentParty.sharedProfile(), currentParty.isLeader()
                        );
                        updateDiscordPartySize();
                        notifyListeners(new PartyNotification(
                            PartyEvent.MEMBER_LEAVE, currentParty, leftMember));
                    }
                }
                case "profile_change" -> {
                    String newProfile = event.get("profile").getAsString();
                    if (currentParty != null) {
                        currentParty = new PartyState(
                            currentParty.partyId(), currentParty.joinCode(),
                            currentParty.members(), newProfile, currentParty.isLeader()
                        );
                        // Apply the new profile locally
                        if (!currentParty.isLeader()) { // Leaders don't re-apply own changes
                            profileManager.switchProfile(newProfile);
                        }
                        notifyListeners(new PartyNotification(
                            PartyEvent.PROFILE_CHANGE, currentParty, null));
                    }
                }
                case "party_dissolved" -> {
                    BoogerClient.LOGGER.info("PartyManager: party dissolved by leader");
                    notifyListeners(new PartyNotification(
                        PartyEvent.PARTY_DISSOLVED, currentParty, null));
                    cleanupParty();
                }
                case "member_stats" -> {
                    // Update member stats (ping, fps, server) in the members list
                    String memberId = event.get("playerId").getAsString();
                    if (currentParty != null) {
                        var updated = new ArrayList<>(currentParty.members());
                        for (int i = 0; i < updated.size(); i++) {
                            if (updated.get(i).playerId().equals(memberId)) {
                                var m = updated.get(i);
                                updated.set(i, new PartyMember(
                                    m.playerId(), m.username(), m.skinHash(),
                                    event.has("ping") ? event.get("ping").getAsInt() : m.ping(),
                                    event.has("fps")  ? event.get("fps").getAsInt()  : m.fps(),
                                    event.has("server") ? event.get("server").getAsString() : m.server(),
                                    m.isLeader(), m.joinedAt()
                                ));
                                break;
                            }
                        }
                        currentParty = new PartyState(
                            currentParty.partyId(), currentParty.joinCode(),
                            updated, currentParty.sharedProfile(), currentParty.isLeader()
                        );
                    }
                }
            }
        } catch (Exception e) {
            BoogerClient.LOGGER.debug("PartyManager: SSE parse error: {}", e.getMessage());
        }
    }

    private void stopSseConnection() {
        sseRunning = false;
        if (sseThread != null) {
            sseThread.interrupt();
            sseThread = null;
        }
    }

    // ─── Heartbeat ────────────────────────────────────────────────────────────

    private ScheduledFuture<?> heartbeatTask;

    private void startHeartbeat(String partyId) {
        stopHeartbeat();
        heartbeatTask = scheduler.scheduleAtFixedRate(() -> {
            if (!inParty.get()) return;
            try {
                JsonObject stats = buildStatsPayload();
                api.postJsonPublic(BASE + "/party/" + partyId + "/heartbeat", stats.toString());
            } catch (Exception e) {
                BoogerClient.LOGGER.debug("PartyManager: heartbeat failed: {}", e.getMessage());
            }
        }, 0, 5, TimeUnit.SECONDS);
    }

    private void stopHeartbeat() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
            heartbeatTask = null;
        }
    }

    private JsonObject buildStatsPayload() {
        JsonObject stats = new JsonObject();
        stats.addProperty("ping", (int) BoogerClient.latency().getSmoothedRttMs());
        stats.addProperty("fps",  (int) BoogerClient.getInstance().getCore()
            .getFramePacingThread().getMeasuredFps());

        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
        if (mc != null) {
            var serverEntry = mc.getCurrentServerEntry();
            stats.addProperty("server",
                serverEntry != null ? serverEntry.address : "singleplayer");
        }
        return stats;
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private void cleanupParty() {
        stopSseConnection();
        stopHeartbeat();
        currentParty = null;
        inParty.set(false);
        updateDiscordPartySize();
    }

    private void updateDiscordPartySize() {
        // Signal DiscordRichPresence to update party fields
        // The DiscordRichPresence system reads currentParty directly
        BoogerClient.LOGGER.debug("PartyManager: party size updated → Discord RPC will reflect change");
    }

    private PartyMember buildSelfMember(boolean isLeader) {
        net.minecraft.client.MinecraftClient mc =
            net.minecraft.client.MinecraftClient.getInstance();
        String username = mc != null && mc.player != null
            ? mc.player.getEntityName() : "Player";
        String uuid = mc != null && mc.player != null
            ? mc.player.getUuidAsString() : "";

        return new PartyMember(
            uuid, username, "",
            (int) BoogerClient.latency().getSmoothedRttMs(),
            (int) BoogerClient.getInstance().getCore().getFramePacingThread().getMeasuredFps(),
            "unknown",
            isLeader,
            System.currentTimeMillis() / 1000
        );
    }

    private List<PartyMember> parseMembers(JsonArray arr) {
        var members = new ArrayList<PartyMember>();
        if (arr == null) return members;
        for (JsonElement e : arr) {
            members.add(parseMember(e.getAsJsonObject()));
        }
        return members;
    }

    private PartyMember parseMember(JsonObject obj) {
        return new PartyMember(
            obj.has("playerId")  ? obj.get("playerId").getAsString()  : "",
            obj.has("username")  ? obj.get("username").getAsString()  : "?",
            obj.has("skinHash")  ? obj.get("skinHash").getAsString()  : "",
            obj.has("ping")      ? obj.get("ping").getAsInt()          : -1,
            obj.has("fps")       ? obj.get("fps").getAsInt()           : -1,
            obj.has("server")    ? obj.get("server").getAsString()     : "",
            obj.has("isLeader")  && obj.get("isLeader").getAsBoolean(),
            obj.has("joinedAt")  ? obj.get("joinedAt").getAsLong()     : 0L
        );
    }

    private void notifyListeners(PartyNotification notification) {
        for (Consumer<PartyNotification> listener : listeners) {
            try { listener.accept(notification); }
            catch (Exception e) {
                BoogerClient.LOGGER.debug("PartyManager: listener threw: {}", e.getMessage());
            }
        }
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    public void addListener(Consumer<PartyNotification> listener) {
        listeners.add(listener);
    }

    public boolean isInParty()        { return inParty.get(); }
    public PartyState getPartyState() { return currentParty; }
    public int getPartySize()         { return currentParty != null ? currentParty.members().size() : 1; }

    public void shutdown() {
        if (inParty.get()) {
            leaveParty().join();
        }
        scheduler.shutdownNow();
    }

    // Stub inner class — replaced by DiscordRichPresence in production wiring
    private static class DiscordRpcBridge {
        void updatePartySize(int current, int max) {}
    }
}
