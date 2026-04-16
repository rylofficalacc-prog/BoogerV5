package dev.booger.client;

import dev.booger.client.core.BoogerCore;
import dev.booger.client.core.thread.InputPollingThread;
import dev.booger.client.core.thread.FramePacingThread;
import dev.booger.client.module.ModuleManager;
import dev.booger.client.config.ConfigManager;
import dev.booger.client.render.HudRenderer;
import dev.booger.client.network.LatencyTracker;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Environment(EnvType.CLIENT)
public class BoogerClient implements ClientModInitializer {

    public static final String MOD_ID = "booger";
    public static final String MOD_NAME = "Booger Client";
    public static final String VERSION = "0.1.0";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);

    // Singleton — accessed via BoogerClient.getInstance()
    private static BoogerClient instance;

    private BoogerCore core;

    @Override
    public void onInitializeClient() {
        instance = this;
        long startTime = System.nanoTime();

        LOGGER.info("╔══════════════════════════════════╗");
        LOGGER.info("║       BOOGER CLIENT v{}        ║", VERSION);
        LOGGER.info("║   High-Performance 1.21.x PvP    ║");
        LOGGER.info("╚══════════════════════════════════╝");

        // Initialization order matters — core first, then consumers
        try {
            this.core = new BoogerCore();

            // 1. Event bus — everything else depends on it
            core.initEventBus();

            // 2. Config — modules need their saved state before init
            core.getConfigManager().load();

            // 3. Module system — registers all HUD/PvP/Util modules
            core.getModuleManager().init();

            // 4. Render system — HUD renderer subscribes to render events
            core.getHudRenderer().init();

            // 5. Network tracker — packet hooks registered via mixins, this just owns state
            core.getLatencyTracker().init();

            // 6. Performance threads — last, so they don't poll before systems are ready
            core.getInputPollingThread().start();
            // Frame pacing thread is started by GameRenderer mixin post-init

            long elapsed = (System.nanoTime() - startTime) / 1_000_000;
            LOGGER.info("Booger Client initialized in {}ms", elapsed);

        } catch (Exception e) {
            LOGGER.error("FATAL: Booger Client failed to initialize", e);
            // Don't crash the game — degrade gracefully
            // All systems check core.isHealthy() before operating
        }
    }

    public static BoogerClient getInstance() {
        return instance;
    }

    public BoogerCore getCore() {
        return core;
    }

    // Convenience accessors — avoids getInstance().getCore().getX() chains
    public static ModuleManager modules() {
        return instance.core.getModuleManager();
    }

    public static ConfigManager config() {
        return instance.core.getConfigManager();
    }

    public static HudRenderer hud() {
        return instance.core.getHudRenderer();
    }

    public static LatencyTracker latency() {
        return instance.core.getLatencyTracker();
    }
}
