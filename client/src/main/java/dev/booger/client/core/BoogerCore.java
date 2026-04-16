package dev.booger.client.core;

import dev.booger.client.config.ConfigManager;
import dev.booger.client.core.event.EventBus;
import dev.booger.client.core.thread.FramePacingThread;
import dev.booger.client.core.thread.InputPollingThread;
import dev.booger.client.mod.marketplace.ModMarketplace;
import dev.booger.client.module.ModuleManager;
import dev.booger.client.network.LatencyTracker;
import dev.booger.client.network.cloud.CloudApiClient;
import dev.booger.client.network.quality.NetworkQualityMonitor;
import dev.booger.client.render.EntityOutlineRenderer;
import dev.booger.client.render.HudRenderer;
import dev.booger.client.render.chunk.ChunkRenderOptimizer;
import dev.booger.client.render.font.sdf.SdfTextRenderer;
import dev.booger.client.security.SafeModeAuditor;
import dev.booger.client.security.SafeModeSandbox;
import dev.booger.client.system.DiscordRichPresence;
import dev.booger.client.system.PerformanceProfiler;
import dev.booger.client.system.ProfileManager;
import dev.booger.client.system.party.PartyManager;

import java.nio.file.Path;

/**
 * BoogerCore — Phase 6 final service locator.
 *
 * All 19 subsystems owned and wired here.
 * Init order is explicit and documented — dependencies always constructed
 * before their consumers. No circular dependencies.
 *
 * GL-dependent systems (SDF, EntityOutline) initialize lazily on first
 * render frame via initGlSystems() — GL context is not available at mod init.
 */
public final class BoogerCore {

    // Phase 1-2
    private final EventBus              eventBus;
    private final ConfigManager         configManager;
    private final ModuleManager         moduleManager;
    private final HudRenderer           hudRenderer;
    private final LatencyTracker        latencyTracker;
    private final InputPollingThread    inputPollingThread;
    private final FramePacingThread     framePacingThread;
    // Phase 3-4
    private final NetworkQualityMonitor networkQualityMonitor;
    private final ProfileManager        profileManager;
    // Phase 5
    private final SdfTextRenderer       sdfTextRenderer;
    private final EntityOutlineRenderer entityOutlineRenderer;
    private final ChunkRenderOptimizer  chunkRenderOptimizer;
    private final SafeModeSandbox       safeSandbox;
    private final SafeModeAuditor       safeModeAuditor;
    private final ModMarketplace        modMarketplace;
    private final DiscordRichPresence   discordRpc;
    private final PerformanceProfiler   profiler;
    // Phase 6
    private final CloudApiClient        cloudApiClient;
    private final PartyManager          partyManager;

    private boolean healthy = true;
    private boolean glInitialized = false;

    public BoogerCore() {
        this.eventBus              = new EventBus();
        this.configManager         = new ConfigManager();
        this.moduleManager         = new ModuleManager(eventBus, configManager);
        this.hudRenderer           = new HudRenderer(eventBus, moduleManager);
        this.latencyTracker        = new LatencyTracker(eventBus);
        this.inputPollingThread    = new InputPollingThread(eventBus);
        this.framePacingThread     = new FramePacingThread();
        this.networkQualityMonitor = new NetworkQualityMonitor(eventBus);
        this.profileManager        = new ProfileManager(configManager);
        this.sdfTextRenderer       = new SdfTextRenderer();
        this.entityOutlineRenderer = new EntityOutlineRenderer();
        this.chunkRenderOptimizer  = new ChunkRenderOptimizer(eventBus);
        this.safeSandbox           = new SafeModeSandbox(eventBus, moduleManager);
        this.safeModeAuditor       = new SafeModeAuditor(safeSandbox);
        this.modMarketplace        = new ModMarketplace();
        this.discordRpc            = new DiscordRichPresence(eventBus);
        this.profiler              = new PerformanceProfiler(eventBus);
        this.cloudApiClient        = new CloudApiClient();
        this.partyManager          = new PartyManager(cloudApiClient, profileManager);

        // Wire post-construction bidirectional references
        configManager.setModuleManager(moduleManager);
        moduleManager.setHudRenderer(hudRenderer);
    }

    public void initEventBus() {
        eventBus.init();
    }

    public void initAll(Path gameDir) {
        configManager.load();
        profileManager.init();
        latencyTracker.init();
        networkQualityMonitor.init();
        moduleManager.init();
        hudRenderer.init();
        chunkRenderOptimizer.init();
        profiler.init();
        safeSandbox.init();
        modMarketplace.init();

        // Cloud systems — session file may not exist (offline players)
        Path sessionFile = gameDir.resolve("booger/session.jwt");
        cloudApiClient.initFromSessionFile(sessionFile);
        partyManager.init();

        // Discord RPC off by default
        discordRpc.init();
    }

    /** Called from MixinGameRenderer on first frame — GL context guaranteed. */
    public void initGlSystems() {
        if (glInitialized) return;
        glInitialized = true;

        sdfTextRenderer.init();
        if (sdfTextRenderer.isReady()) {
            hudRenderer.getFontRenderer().setSdfRenderer(sdfTextRenderer);
        }
        entityOutlineRenderer.init();
    }

    /** Wire SafeModeAuditor from MixinClientPlayerEntityCoords. */
    public void auditPlayerMovement(double speedBps, double x, double y, double z) {
        safeModeAuditor.auditSpeed(speedBps, x, y, z);
    }

    public void shutdown() {
        configManager.shutdown();
        partyManager.shutdown();
        cloudApiClient.shutdown();
        discordRpc.shutdown();
        sdfTextRenderer.destroy();
        entityOutlineRenderer.destroy();
        inputPollingThread.shutdown();
    }

    public boolean isHealthy()  { return healthy; }
    public void markUnhealthy() { this.healthy = false; }

    // Accessors
    public EventBus              getEventBus()              { return eventBus; }
    public ConfigManager         getConfigManager()         { return configManager; }
    public ModuleManager         getModuleManager()         { return moduleManager; }
    public HudRenderer           getHudRenderer()           { return hudRenderer; }
    public LatencyTracker        getLatencyTracker()        { return latencyTracker; }
    public InputPollingThread    getInputPollingThread()    { return inputPollingThread; }
    public FramePacingThread     getFramePacingThread()     { return framePacingThread; }
    public NetworkQualityMonitor getNetworkQualityMonitor() { return networkQualityMonitor; }
    public ProfileManager        getProfileManager()        { return profileManager; }
    public SdfTextRenderer       getSdfTextRenderer()       { return sdfTextRenderer; }
    public EntityOutlineRenderer getEntityOutlineRenderer() { return entityOutlineRenderer; }
    public ChunkRenderOptimizer  getChunkRenderOptimizer()  { return chunkRenderOptimizer; }
    public SafeModeSandbox       getSafeSandbox()           { return safeSandbox; }
    public SafeModeAuditor       getSafeModeAuditor()       { return safeModeAuditor; }
    public ModMarketplace        getModMarketplace()        { return modMarketplace; }
    public DiscordRichPresence   getDiscordRpc()            { return discordRpc; }
    public PerformanceProfiler   getProfiler()              { return profiler; }
    public CloudApiClient        getCloudApiClient()        { return cloudApiClient; }
    public PartyManager          getPartyManager()          { return partyManager; }
}
