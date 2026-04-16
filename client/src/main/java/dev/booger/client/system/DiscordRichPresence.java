package dev.booger.client.system;

import dev.booger.client.BoogerClient;
import dev.booger.client.core.event.EventBus;
import dev.booger.client.core.event.events.Events;
import dev.booger.client.module.ModuleManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import com.google.gson.*;

/**
 * Discord Rich Presence integration.
 *
 * ARCHITECTURE:
 * Discord's GameSDK is a native library (.dll/.so/.dylib) that must be
 * linked directly into the process. Since Minecraft is a JVM process,
 * we have two options:
 *
 * Option A: JNI — load the GameSDK native lib directly in the JVM.
 *   Pro: Low latency, direct API access.
 *   Con: Risky — native crashes kill the JVM. Discord SDK memory must be
 *        managed carefully. Complex to ship cross-platform.
 *
 * Option B: IPC Bridge — our Tauri launcher runs the Discord SDK in its
 *   own Rust process. The game communicates via a local socket (loopback).
 *   Pro: Crash-isolated. SDK update doesn't require game JAR changes.
 *        Rust handles Discord's threading requirements natively.
 *   Con: ~1-2ms IPC latency per update (acceptable — we update every 15s).
 *
 * We chose Option B. The Tauri launcher runs a lightweight IPC server
 * on localhost:29384 (Booger's designated IPC port). We connect to it
 * and send JSON update commands.
 *
 * MESSAGE PROTOCOL (JSON over TCP, newline-delimited):
 * {
 *   "type": "UPDATE_PRESENCE",
 *   "state": "Playing on Hypixel",
 *   "details": "Bedwars | Solo — 12 wins",
 *   "largeImageKey": "booger_logo",
 *   "largeImageText": "Booger Client v0.1.0",
 *   "smallImageKey": "pvp_icon",
 *   "smallImageText": "PvP Mode",
 *   "startTimestamp": 1704067200,
 *   "partySize": 1,
 *   "partyMax": 4
 * }
 *
 * PRESENCE UPDATE THROTTLE:
 * Discord rate-limits presence updates to 5 per 20 seconds.
 * We update at most every 15 seconds, and only when state actually changes.
 * Changes are detected by hashing the presence data and comparing to last sent.
 *
 * PRIVACY:
 * Rich Presence is OFF by default and clearly labeled in settings.
 * Players choose what to share:
 * - Server address (shown/hidden)
 * - Game mode / server name (shown/hidden)
 * - Time elapsed (shown/hidden)
 * No personally identifiable data (UUID, username) is shared — only
 * game state that the player explicitly enables.
 */
public final class DiscordRichPresence {

    private static final int    IPC_PORT     = 29384;
    private static final String IPC_HOST     = "127.0.0.1";
    private static final long   UPDATE_INTERVAL_MS = 15_000; // 15 seconds
    private static final int    CONNECT_TIMEOUT_MS = 2_000;

    private final EventBus eventBus;
    private final Gson gson = new Gson();

    // Connection state
    private Socket socket;
    private PrintWriter writer;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean enabled   = new AtomicBoolean(false);

    // Update throttle
    private long lastUpdateMs = 0;
    private long lastPresenceHash = 0;
    private long sessionStartMs = System.currentTimeMillis();

    // Privacy settings
    private volatile boolean showServerAddress = false;
    private volatile boolean showServerName    = true;
    private volatile boolean showElapsedTime   = true;

    // Reconnect
    private final ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Booger-DiscordRPC");
            t.setDaemon(true);
            return t;
        });
    private ScheduledFuture<?> reconnectTask;

    public DiscordRichPresence(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    public void init() {
        // Subscribe to ticks for periodic updates
        eventBus.subscribe(Events.TickEvent.class, this::onTick);
        BoogerClient.LOGGER.debug("DiscordRichPresence initialized (disabled by default)");
    }

    /**
     * Enable Discord RPC. Attempts IPC connection to Tauri launcher.
     */
    public void enable() {
        if (enabled.get()) return;
        enabled.set(true);
        sessionStartMs = System.currentTimeMillis();
        connectAsync();
    }

    public void disable() {
        enabled.set(false);
        clearPresence();
        disconnect();
    }

    private void connectAsync() {
        scheduler.execute(() -> {
            try {
                socket = new Socket();
                socket.connect(new InetSocketAddress(IPC_HOST, IPC_PORT), CONNECT_TIMEOUT_MS);
                socket.setKeepAlive(true);
                socket.setSoTimeout(5000);
                writer = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
                connected.set(true);
                BoogerClient.LOGGER.info("DiscordRPC: connected to Tauri IPC bridge");

                // Send initial presence
                sendCurrentPresence();

            } catch (Exception e) {
                connected.set(false);
                BoogerClient.LOGGER.debug("DiscordRPC: IPC connection failed (launcher not running?) — {}", e.getMessage());
                // Schedule reconnect attempt in 30 seconds
                reconnectTask = scheduler.schedule(this::connectAsync, 30, TimeUnit.SECONDS);
            }
        });
    }

    private void onTick(Events.TickEvent event) {
        if (!enabled.get() || !connected.get()) return;

        long now = System.currentTimeMillis();
        if (now - lastUpdateMs < UPDATE_INTERVAL_MS) return;

        sendCurrentPresence();
    }

    private void sendCurrentPresence() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return;

        PresenceData data = buildPresenceData(mc);
        long hash = data.hashCode();

        if (hash == lastPresenceHash) return; // No change — skip update

        sendPresence(data);
        lastPresenceHash = hash;
        lastUpdateMs = System.currentTimeMillis();
    }

    private PresenceData buildPresenceData(MinecraftClient mc) {
        String state   = "In menus";
        String details = "Booger Client";
        String smallImage = "menu_icon";
        String smallText  = "In menus";

        if (mc.world != null && mc.player != null) {
            // In game
            ServerInfo serverInfo = mc.getCurrentServerEntry();
            String serverName = "Singleplayer";
            String serverAddr = "";

            if (serverInfo != null) {
                serverName = serverInfo.name;
                serverAddr = serverInfo.address;
            }

            // Determine game mode
            String dimension = mc.world.getRegistryKey().getValue().getPath();
            String dimDisplay = switch (dimension) {
                case "overworld" -> "Overworld";
                case "the_nether" -> "The Nether";
                case "the_end" -> "The End";
                default -> dimension;
            };

            if (showServerName && !serverName.equals("Singleplayer")) {
                state = "Playing on " + serverName;
            } else if (showServerAddress && !serverAddr.isEmpty()) {
                state = serverAddr;
            } else if (serverInfo == null) {
                state = "Singleplayer";
            } else {
                state = "Multiplayer";
            }

            details = dimDisplay + " · " + BoogerClient.getInstance()
                .getCore().getProfileManager().getActiveProfile() + " profile";

            smallImage = "game_icon";
            smallText  = String.format("%.0f FPS · %dms ping",
                BoogerClient.getInstance().getCore().getFramePacingThread().getMeasuredFps(),
                BoogerClient.latency().getSmoothedRttMs());
        }

        return new PresenceData(
            state, details,
            "booger_logo", "Booger Client v" + BoogerClient.VERSION,
            smallImage, smallText,
            showElapsedTime ? sessionStartMs / 1000 : 0,
            1, 1 // party size — Phase 6 will add real party support
        );
    }

    private void sendPresence(PresenceData data) {
        if (writer == null || !connected.get()) return;

        try {
            JsonObject msg = new JsonObject();
            msg.addProperty("type",            "UPDATE_PRESENCE");
            msg.addProperty("state",           data.state());
            msg.addProperty("details",         data.details());
            msg.addProperty("largeImageKey",   data.largeImageKey());
            msg.addProperty("largeImageText",  data.largeImageText());
            msg.addProperty("smallImageKey",   data.smallImageKey());
            msg.addProperty("smallImageText",  data.smallImageText());
            msg.addProperty("startTimestamp",  data.startTimestamp());
            msg.addProperty("partySize",       data.partySize());
            msg.addProperty("partyMax",        data.partyMax());

            writer.println(gson.toJson(msg));

            if (writer.checkError()) {
                connected.set(false);
                BoogerClient.LOGGER.debug("DiscordRPC: write error — disconnected");
                reconnectTask = scheduler.schedule(this::connectAsync, 10, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            connected.set(false);
        }
    }

    private void clearPresence() {
        if (writer == null || !connected.get()) return;
        try {
            JsonObject msg = new JsonObject();
            msg.addProperty("type", "CLEAR_PRESENCE");
            writer.println(gson.toJson(msg));
        } catch (Exception ignored) {}
    }

    private void disconnect() {
        connected.set(false);
        if (reconnectTask != null) reconnectTask.cancel(false);
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        writer = null;
        socket = null;
    }

    private record PresenceData(
        String state, String details,
        String largeImageKey, String largeImageText,
        String smallImageKey, String smallImageText,
        long startTimestamp,
        int partySize, int partyMax
    ) {}

    // ─── Settings ────────────────────────────────────────────────────────────

    public void setShowServerAddress(boolean show) { this.showServerAddress = show; }
    public void setShowServerName   (boolean show) { this.showServerName    = show; }
    public void setShowElapsedTime  (boolean show) { this.showElapsedTime   = show; }
    public boolean isEnabled()   { return enabled.get();   }
    public boolean isConnected() { return connected.get(); }

    public void shutdown() {
        disable();
        scheduler.shutdownNow();
    }
}
