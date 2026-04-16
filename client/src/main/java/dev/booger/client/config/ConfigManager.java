package dev.booger.client.config;

import com.google.gson.*;
import dev.booger.client.BoogerClient;
import dev.booger.client.module.BoogerModule;
import dev.booger.client.module.ModuleManager;
import dev.booger.client.module.Setting;
import dev.booger.client.render.theme.ThemeEngine;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Config manager — JSON-based, profile-aware, atomic file writes.
 *
 * DESIGN:
 * Config is stored as per-profile JSON files in:
 *   .minecraft/booger/profiles/<profileName>.json
 *
 * The "default" profile is always present. Users can create named profiles
 * for different servers or playstyles (e.g. "hypixel", "pvp_practice", "chill").
 *
 * File format:
 * {
 *   "profile": "default",
 *   "theme": "Booger Dark",
 *   "modules": {
 *     "FPS": {
 *       "enabled": true,
 *       "visible": true,
 *       "hudX": 2.0,
 *       "hudY": 2.0,
 *       "hudScale": 1.0,
 *       "settings": {
 *         "showBackground": true,
 *         "colorCode": true,
 *         "goodFps": 120
 *       }
 *     }
 *   }
 * }
 *
 * ATOMIC WRITE:
 * We write to a temp file first, then atomically rename it over the real file.
 * This prevents corrupt configs from partial writes (e.g., JVM crash during save).
 *
 * SAVE DEBOUNCING:
 * Settings changes mark the config dirty. A background save runs 2 seconds
 * after the last dirty mark. This prevents hammering disk during rapid setting changes
 * (e.g., dragging a slider fires markDirty() 60x/sec — we only write once).
 *
 * READ-WRITE LOCK:
 * Config can be read from the render thread (module reads settings during render)
 * and written from the main thread (user changes a setting). We use a
 * ReentrantReadWriteLock — multiple concurrent readers, exclusive writer.
 * This is the correct primitive for read-heavy, write-rare access patterns.
 */
public final class ConfigManager {

    private static final String CONFIG_DIR = "booger/profiles";
    private static final String DEFAULT_PROFILE = "default";
    private static final long SAVE_DEBOUNCE_MS = 2000;

    private final Gson gson = new GsonBuilder()
        .setPrettyPrinting()
        .serializeNulls()
        .create();

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    // Lazily resolved after module manager is available
    private ModuleManager moduleManager;

    private String currentProfile = DEFAULT_PROFILE;
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private volatile long lastDirtyMs = 0;

    // Background save thread
    private Thread saveThread;
    private volatile boolean saveThreadRunning = false;

    public ConfigManager() {}

    public void setModuleManager(ModuleManager moduleManager) {
        this.moduleManager = moduleManager;
    }

    /**
     * Load config from disk for the current profile.
     * Called once on client init, after all modules are registered.
     */
    public void load() {
        load(currentProfile);
    }

    public void load(String profile) {
        this.currentProfile = profile;
        Path configFile = getProfilePath(profile);

        if (!Files.exists(configFile)) {
            BoogerClient.LOGGER.info("No config found for profile '{}', using defaults", profile);
            startSaveThread();
            return;
        }

        lock.writeLock().lock();
        try (Reader reader = Files.newBufferedReader(configFile)) {
            JsonObject root = gson.fromJson(reader, JsonObject.class);
            if (root == null) {
                BoogerClient.LOGGER.warn("Config file is empty or malformed for profile '{}'", profile);
                return;
            }
            deserialize(root);
            BoogerClient.LOGGER.info("Config loaded for profile '{}'", profile);
        } catch (IOException e) {
            BoogerClient.LOGGER.error("Failed to load config for profile '{}'", profile, e);
        } catch (JsonSyntaxException e) {
            BoogerClient.LOGGER.error("Config JSON is corrupt for profile '{}', using defaults", profile, e);
            // Backup corrupt config
            backupCorruptConfig(configFile);
        } finally {
            lock.writeLock().unlock();
        }

        startSaveThread();
    }

    /**
     * Save current config to disk immediately (blocking).
     * Normally you want save() via debounce — this is for shutdown.
     */
    public void saveNow() {
        saveNow(currentProfile);
    }

    public void saveNow(String profile) {
        if (moduleManager == null) return;

        Path configFile = getProfilePath(profile);
        Path tempFile = configFile.resolveSibling(configFile.getFileName() + ".tmp");

        lock.readLock().lock();
        try {
            // Ensure directory exists
            Files.createDirectories(configFile.getParent());

            // Write to temp file
            JsonObject root = serialize();
            try (Writer writer = Files.newBufferedWriter(tempFile)) {
                gson.toJson(root, writer);
            }

            // Atomic rename — either old or new file exists, never a partial write
            Files.move(tempFile, configFile,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);

            dirty.set(false);
            BoogerClient.LOGGER.debug("Config saved for profile '{}'", profile);

        } catch (AtomicMoveNotSupportedException e) {
            // Fallback for FSes that don't support atomic move (FAT32, some network drives)
            try {
                Files.move(tempFile, configFile, StandardCopyOption.REPLACE_EXISTING);
                dirty.set(false);
            } catch (IOException e2) {
                BoogerClient.LOGGER.error("Config save failed (fallback) for profile '{}'", profile, e2);
            }
        } catch (IOException e) {
            BoogerClient.LOGGER.error("Config save failed for profile '{}'", profile, e);
        } finally {
            lock.readLock().unlock();
        }
    }

    private JsonObject serialize() {
        JsonObject root = new JsonObject();
        root.addProperty("profile", currentProfile);
        root.addProperty("theme", ThemeEngine.current().name());

        if (moduleManager == null) return root;

        JsonObject modulesObj = new JsonObject();
        for (BoogerModule module : moduleManager.getModules()) {
            JsonObject moduleObj = new JsonObject();
            moduleObj.addProperty("enabled", module.isEnabled());
            moduleObj.addProperty("visible", module.isVisible());
            moduleObj.addProperty("hudX", module.getHudX());
            moduleObj.addProperty("hudY", module.getHudY());
            moduleObj.addProperty("hudScale", module.getHudScale());

            JsonObject settingsObj = new JsonObject();
            for (Setting<?> setting : module.getSettings()) {
                Object value = setting.getValue();
                if (value instanceof Boolean b)   settingsObj.addProperty(setting.getKey(), b);
                else if (value instanceof Integer i) settingsObj.addProperty(setting.getKey(), i);
                else if (value instanceof Float f)   settingsObj.addProperty(setting.getKey(), f);
                else if (value instanceof String s)  settingsObj.addProperty(setting.getKey(), s);
                else if (value instanceof Enum<?> e) settingsObj.addProperty(setting.getKey(), e.name());
            }
            moduleObj.add("settings", settingsObj);
            modulesObj.add(module.getName(), moduleObj);
        }
        root.add("modules", modulesObj);
        return root;
    }

    private void deserialize(JsonObject root) {
        // Theme
        if (root.has("theme")) {
            ThemeEngine.setThemeByName(root.get("theme").getAsString());
        }

        if (!root.has("modules") || moduleManager == null) return;

        JsonObject modulesObj = root.getAsJsonObject("modules");
        for (BoogerModule module : moduleManager.getModules()) {
            if (!modulesObj.has(module.getName())) continue;

            JsonObject moduleObj = modulesObj.getAsJsonObject(module.getName());

            if (moduleObj.has("enabled"))
                module.setEnabled(moduleObj.get("enabled").getAsBoolean());
            if (moduleObj.has("visible"))
                module.setVisible(moduleObj.get("visible").getAsBoolean());

            float x = moduleObj.has("hudX") ? moduleObj.get("hudX").getAsFloat() : module.getHudX();
            float y = moduleObj.has("hudY") ? moduleObj.get("hudY").getAsFloat() : module.getHudY();
            module.setHudPosition(x, y);

            if (moduleObj.has("hudScale"))
                module.setHudScale(moduleObj.get("hudScale").getAsFloat());

            if (!moduleObj.has("settings")) continue;
            JsonObject settingsObj = moduleObj.getAsJsonObject("settings");

            for (Setting<?> setting : module.getSettings()) {
                if (!settingsObj.has(setting.getKey())) continue;
                JsonElement elem = settingsObj.get(setting.getKey());
                try {
                    switch (setting.getType()) {
                        case BOOLEAN -> setting.setValue(elem.getAsBoolean());
                        case INTEGER, COLOR -> setting.setValue(elem.getAsInt());
                        case FLOAT -> setting.setValue(elem.getAsFloat());
                        case STRING -> setting.setValue(elem.getAsString());
                        case ENUM -> {
                            // Enum deserialization: look up by name in the enum class
                            // The setting's default value tells us the enum class
                            Object def = setting.getDefaultValue();
                            if (def instanceof Enum<?> defEnum) {
                                for (Object constant : defEnum.getClass().getEnumConstants()) {
                                    if (((Enum<?>) constant).name().equals(elem.getAsString())) {
                                        setting.setValue(constant);
                                        break;
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    BoogerClient.LOGGER.warn("Failed to deserialize setting '{}' for module '{}'",
                        setting.getKey(), module.getName());
                }
            }
        }
    }

    private void startSaveThread() {
        if (saveThreadRunning) return;
        saveThreadRunning = true;
        saveThread = new Thread(() -> {
            while (saveThreadRunning) {
                try {
                    Thread.sleep(500); // Check every 500ms
                    if (dirty.get()) {
                        long msSinceDirty = System.currentTimeMillis() - lastDirtyMs;
                        if (msSinceDirty >= SAVE_DEBOUNCE_MS) {
                            saveNow();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "Booger-ConfigSaver");
        saveThread.setDaemon(true);
        saveThread.setPriority(Thread.MIN_PRIORITY);
        saveThread.start();
    }

    public void shutdown() {
        saveThreadRunning = false;
        if (saveThread != null) saveThread.interrupt();
        if (dirty.get()) saveNow(); // Final flush on shutdown
    }

    public void markDirty() {
        dirty.set(true);
        lastDirtyMs = System.currentTimeMillis();
    }

    public String getCurrentProfile() { return currentProfile; }

    private Path getProfilePath(String profile) {
        return FabricLoader.getInstance().getGameDir()
            .resolve(CONFIG_DIR)
            .resolve(profile + ".json");
    }

    private void backupCorruptConfig(Path configFile) {
        try {
            Path backup = configFile.resolveSibling(
                configFile.getFileName() + ".corrupt." + System.currentTimeMillis());
            Files.copy(configFile, backup);
            BoogerClient.LOGGER.warn("Corrupt config backed up to: {}", backup);
        } catch (IOException e) {
            BoogerClient.LOGGER.error("Failed to backup corrupt config", e);
        }
    }
}
