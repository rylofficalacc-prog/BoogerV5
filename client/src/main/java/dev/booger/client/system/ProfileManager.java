package dev.booger.client.system;

import com.google.gson.*;
import dev.booger.client.BoogerClient;
import dev.booger.client.config.ConfigManager;
import net.fabricmc.loader.api.FabricLoader;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Profile manager — named config profiles with per-server auto-switching.
 *
 * PROFILE SYSTEM:
 * A "profile" is a named ConfigManager snapshot. Each profile stores:
 * - All module enable states
 * - All module HUD positions and scales
 * - All module settings
 * - Theme selection
 *
 * Built-in profiles:
 * - "default"    — clean baseline, used when no server-specific profile matches
 * - "pvp"        — preset optimized for PvP (combat modules on, cosmetics off)
 * - "survival"   — full HUD, coordinates, maps, less combat
 * - "streaming"  — cleaner HUD, no debug info, better for viewers
 *
 * SERVER AUTO-SWITCH:
 * A rules file maps server hostnames to profile names:
 * {
 *   "rules": [
 *     { "host": "hypixel.net",        "profile": "pvp" },
 *     { "host": "mc.hypixel.net",     "profile": "pvp" },
 *     { "host": "pvp.example.com",    "profile": "pvp" },
 *     { "host": "*",                  "profile": "default" }
 *   ]
 * }
 *
 * When connecting to a server, ProfileManager checks this list in order
 * and switches to the matching profile automatically.
 *
 * CLOUD SYNC (STUB):
 * Phase 5 will add cloud sync via the Booger API. The sync interface is
 * defined here as stubs so the ProfileManager API doesn't change in Phase 5.
 * Local profiles always take precedence over cloud (offline-first).
 *
 * PERFORMANCE:
 * Profile switching involves ConfigManager.load() which takes ~5ms (disk read + JSON parse).
 * This only happens on server connect — never during active gameplay.
 * No performance impact.
 */
public final class ProfileManager {

    private static final String PROFILES_DIR = "booger/profiles";
    private static final String RULES_FILE   = "booger/server_profiles.json";

    private final ConfigManager configManager;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    // Profile name → metadata (not full config — loaded on-demand)
    private final Map<String, ProfileMeta> knownProfiles = new ConcurrentHashMap<>();

    // Server hostname → profile name rules (ordered)
    private final List<ServerRule> serverRules = new ArrayList<>();

    // Currently active profile
    private String activeProfile = "default";

    public record ProfileMeta(
        String name,
        String displayName,
        String description,
        long lastModifiedMs,
        boolean isBuiltIn,
        boolean isSynced     // cloud sync flag (Phase 5)
    ) {}

    private record ServerRule(String hostPattern, String profileName) {
        boolean matches(String host) {
            if (hostPattern.equals("*")) return true;
            if (hostPattern.startsWith("*.")) {
                return host.endsWith(hostPattern.substring(1));
            }
            return host.equalsIgnoreCase(hostPattern);
        }
    }

    public ProfileManager(ConfigManager configManager) {
        this.configManager = configManager;
    }

    public void init() {
        ensureBuiltInProfiles();
        loadKnownProfiles();
        loadServerRules();
        BoogerClient.LOGGER.info("ProfileManager: {} profiles loaded, {} server rules",
            knownProfiles.size(), serverRules.size());
    }

    /**
     * Called when connecting to a server.
     * Checks server rules and switches profile if a match is found.
     *
     * @param serverAddress The server IP/hostname being connected to
     */
    public void onServerConnect(String serverAddress) {
        String host = extractHost(serverAddress);

        for (ServerRule rule : serverRules) {
            if (rule.matches(host)) {
                if (!rule.profileName().equals(activeProfile)) {
                    BoogerClient.LOGGER.info(
                        "ProfileManager: auto-switching to '{}' for server '{}'",
                        rule.profileName(), host
                    );
                    switchProfile(rule.profileName());
                }
                return;
            }
        }

        // No match — use default if not already on it
        if (!activeProfile.equals("default")) {
            switchProfile("default");
        }
    }

    /**
     * Switch to a named profile. Saves current profile first.
     */
    public void switchProfile(String profileName) {
        if (!knownProfiles.containsKey(profileName)) {
            BoogerClient.LOGGER.warn("ProfileManager: unknown profile '{}'", profileName);
            return;
        }

        // Save current state before switching
        configManager.saveNow(activeProfile);

        // Load new profile
        configManager.load(profileName);
        activeProfile = profileName;

        BoogerClient.LOGGER.info("ProfileManager: switched to profile '{}'", profileName);
    }

    /**
     * Create a new profile by copying the current active profile.
     */
    public void createProfile(String name, String displayName, String description) {
        if (knownProfiles.containsKey(name)) {
            BoogerClient.LOGGER.warn("ProfileManager: profile '{}' already exists", name);
            return;
        }

        // Save current state as new profile
        configManager.saveNow(name);

        knownProfiles.put(name, new ProfileMeta(
            name, displayName, description,
            System.currentTimeMillis(), false, false
        ));

        saveProfileRegistry();
        BoogerClient.LOGGER.info("ProfileManager: created profile '{}'", name);
    }

    /**
     * Delete a profile (cannot delete built-in profiles).
     */
    public void deleteProfile(String name) {
        ProfileMeta meta = knownProfiles.get(name);
        if (meta == null) return;
        if (meta.isBuiltIn()) {
            BoogerClient.LOGGER.warn("ProfileManager: cannot delete built-in profile '{}'", name);
            return;
        }
        if (name.equals(activeProfile)) {
            switchProfile("default");
        }

        knownProfiles.remove(name);
        try {
            Files.deleteIfExists(getProfilePath(name));
        } catch (IOException e) {
            BoogerClient.LOGGER.error("Failed to delete profile file for '{}'", name, e);
        }
        saveProfileRegistry();
    }

    /**
     * Add a server → profile rule.
     * Rules are evaluated in order; use "*" as a catch-all last entry.
     */
    public void addServerRule(String hostPattern, String profileName) {
        // Remove existing rule for this host
        serverRules.removeIf(r -> r.hostPattern().equalsIgnoreCase(hostPattern));

        // Find position of "*" wildcard and insert before it
        int wildcardIdx = -1;
        for (int i = 0; i < serverRules.size(); i++) {
            if (serverRules.get(i).hostPattern().equals("*")) {
                wildcardIdx = i;
                break;
            }
        }

        ServerRule newRule = new ServerRule(hostPattern, profileName);
        if (wildcardIdx >= 0) {
            serverRules.add(wildcardIdx, newRule);
        } else {
            serverRules.add(newRule);
        }

        saveServerRules();
    }

    public void removeServerRule(String hostPattern) {
        serverRules.removeIf(r -> r.hostPattern().equalsIgnoreCase(hostPattern));
        saveServerRules();
    }

    // ─── Cloud sync stubs (Phase 5) ───────────────────────────────────────────

    /**
     * Upload current profile to cloud storage.
     * STUB — Phase 5 will implement via Booger API.
     */
    public void syncToCloud(String profileName) {
        BoogerClient.LOGGER.debug("Cloud sync stub: upload '{}' (Phase 5)", profileName);
        // Phase 5: POST /api/v1/profiles/{name} with JWT auth
    }

    /**
     * Download profile from cloud and merge with local.
     * STUB — Phase 5.
     */
    public void syncFromCloud(String profileName) {
        BoogerClient.LOGGER.debug("Cloud sync stub: download '{}' (Phase 5)", profileName);
    }

    // ─── Init helpers ─────────────────────────────────────────────────────────

    private void ensureBuiltInProfiles() {
        // Built-in profile metadata — actual config files are created on first save
        registerBuiltIn("default",   "Default",   "Balanced settings for general play");
        registerBuiltIn("pvp",       "PvP",       "Combat-focused: combat modules on, cosmetics off");
        registerBuiltIn("survival",  "Survival",  "Full HUD with coordinates and resource tracking");
        registerBuiltIn("streaming", "Streaming", "Clean HUD, no debug info, viewer-friendly");
    }

    private void registerBuiltIn(String name, String display, String desc) {
        knownProfiles.put(name, new ProfileMeta(
            name, display, desc,
            0L, true, false
        ));
    }

    private void loadKnownProfiles() {
        Path profilesDir = FabricLoader.getInstance().getGameDir().resolve(PROFILES_DIR);
        if (!Files.exists(profilesDir)) return;

        try {
            Files.list(profilesDir)
                .filter(p -> p.toString().endsWith(".json"))
                .filter(p -> !p.toString().endsWith(".tmp"))
                .filter(p -> !p.toString().contains(".corrupt"))
                .forEach(p -> {
                    String name = p.getFileName().toString().replace(".json", "");
                    if (!knownProfiles.containsKey(name)) {
                        knownProfiles.put(name, new ProfileMeta(
                            name, name, "",
                            p.toFile().lastModified(), false, false
                        ));
                    }
                });
        } catch (IOException e) {
            BoogerClient.LOGGER.error("Failed to scan profiles directory", e);
        }
    }

    private void loadServerRules() {
        Path rulesFile = FabricLoader.getInstance().getGameDir().resolve(RULES_FILE);
        if (!Files.exists(rulesFile)) {
            // Create default rules file
            serverRules.add(new ServerRule("*", "default"));
            saveServerRules();
            return;
        }

        try (Reader r = Files.newBufferedReader(rulesFile)) {
            JsonObject root = gson.fromJson(r, JsonObject.class);
            if (root == null || !root.has("rules")) return;

            for (JsonElement elem : root.getAsJsonArray("rules")) {
                JsonObject rule = elem.getAsJsonObject();
                serverRules.add(new ServerRule(
                    rule.get("host").getAsString(),
                    rule.get("profile").getAsString()
                ));
            }
        } catch (Exception e) {
            BoogerClient.LOGGER.error("Failed to load server profile rules", e);
            serverRules.add(new ServerRule("*", "default"));
        }
    }

    private void saveServerRules() {
        Path rulesFile = FabricLoader.getInstance().getGameDir().resolve(RULES_FILE);
        JsonObject root = new JsonObject();
        JsonArray rules = new JsonArray();
        for (ServerRule rule : serverRules) {
            JsonObject r = new JsonObject();
            r.addProperty("host", rule.hostPattern());
            r.addProperty("profile", rule.profileName());
            rules.add(r);
        }
        root.add("rules", rules);
        try {
            Files.createDirectories(rulesFile.getParent());
            try (Writer w = Files.newBufferedWriter(rulesFile)) {
                gson.toJson(root, w);
            }
        } catch (IOException e) {
            BoogerClient.LOGGER.error("Failed to save server rules", e);
        }
    }

    private void saveProfileRegistry() {
        // Profile registry is implicit from the files in the profiles dir
        // No separate registry file needed — knownProfiles is rebuilt on init
    }

    private String extractHost(String address) {
        if (address == null) return "";
        // Strip port if present: "hypixel.net:25565" → "hypixel.net"
        int colonIdx = address.lastIndexOf(':');
        if (colonIdx > 0) {
            String portStr = address.substring(colonIdx + 1);
            if (portStr.matches("\\d+")) return address.substring(0, colonIdx);
        }
        return address;
    }

    private Path getProfilePath(String name) {
        return FabricLoader.getInstance().getGameDir()
            .resolve(PROFILES_DIR).resolve(name + ".json");
    }

    // ─── Getters ─────────────────────────────────────────────────────────────

    public String getActiveProfile() { return activeProfile; }
    public Collection<ProfileMeta> getAllProfiles() { return knownProfiles.values(); }
    public List<ServerRule> getServerRules() { return Collections.unmodifiableList(serverRules); }
}
