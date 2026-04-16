package dev.booger.client.module;

import dev.booger.client.BoogerClient;
import dev.booger.client.config.ConfigManager;
import dev.booger.client.core.event.EventBus;
import dev.booger.client.core.event.events.Events;
import dev.booger.client.module.impl.hud.*;
import dev.booger.client.module.impl.pvp.*;
import dev.booger.client.module.impl.util.*;
import dev.booger.client.render.HudRenderer;

import java.util.*;

/**
 * Module registry and lifecycle manager.
 *
 * All modules are registered here. ModuleManager owns their lifetimes
 * and dispatches tick events to enabled modules.
 *
 * Render events are dispatched by HudRenderer directly — modules register
 * themselves with HudRenderer on enable (if they have HUD elements).
 *
 * The module list is final after init() — no dynamic module loading at runtime.
 * Keeping the list static eliminates synchronization overhead on the tick dispatch loop.
 */
public final class ModuleManager {

    private final EventBus eventBus;
    private final ConfigManager configManager;
    private HudRenderer hudRenderer; // Set by setHudRenderer() after construction

    // Ordered list — rendering order matters for HUD overlap
    private final List<BoogerModule> modules = new ArrayList<>();

    // Fast lookup by name — used by keybind system and config loading
    private final Map<String, BoogerModule> modulesByName = new HashMap<>();

    // Subset of modules with tick logic — avoids iterating non-tick modules every tick
    private final List<BoogerModule> tickModules = new ArrayList<>();

    public ModuleManager(EventBus eventBus, ConfigManager configManager) {
        this.eventBus = eventBus;
        this.configManager = configManager;
    }

    public void init() {
        registerAllModules();
        subscribeToTick();
        BoogerClient.LOGGER.info("ModuleManager: {} modules registered", modules.size());
    }

    public void setHudRenderer(HudRenderer hudRenderer) {
        this.hudRenderer = hudRenderer;
        // Inject into already-registered modules
        for (BoogerModule module : modules) {
            module.inject(eventBus, configManager, hudRenderer);
        }
    }

    /**
     * Register all built-in modules.
     * ORDER MATTERS for HUD rendering: modules registered first render first.
     */
    private void registerAllModules() {
        // HUD modules
        register(new FpsModule());
        register(new PingModule());
        register(new CpsModule());
        register(new ArmorModule());
        register(new PotionModule());
        register(new CoordinatesModule());
        register(new DirectionModule());
        register(new SpeedModule());

        // PvP modules
        register(new HitColorModule());
        register(new ComboCounterModule());
        register(new TargetHudModule());
        register(new KillEffectModule());

        // Utility modules
        register(new FpsLimiterModule());
        register(new PacketLogModule());
        register(new ZoomModule());
    }

    private void register(BoogerModule module) {
        module.inject(eventBus, configManager, hudRenderer);
        module.onRegister();

        modules.add(module);
        modulesByName.put(module.getName().toLowerCase(), module);

        // Check if this module overrides onTick — if so, add to tick list
        // We use a marker interface rather than reflection
        if (module instanceof TickableModule) {
            tickModules.add(module);
        }
    }

    private void subscribeToTick() {
        eventBus.subscribe(Events.TickEvent.class, event -> {
            // Iterate snapshot — CopyOnWriteArrayList safe for this pattern
            for (BoogerModule module : tickModules) {
                if (module.isEnabled()) {
                    try {
                        module.onTick(event.tickCount);
                    } catch (Exception e) {
                        BoogerClient.LOGGER.error(
                            "Module {} threw exception in onTick", module.getName(), e
                        );
                    }
                }
            }
        });
    }

    public Optional<BoogerModule> getModule(String name) {
        return Optional.ofNullable(modulesByName.get(name.toLowerCase()));
    }

    @SuppressWarnings("unchecked")
    public <T extends BoogerModule> Optional<T> getModule(Class<T> clazz) {
        return modules.stream()
            .filter(m -> m.getClass() == clazz)
            .map(m -> (T) m)
            .findFirst();
    }

    public List<BoogerModule> getModules() {
        return Collections.unmodifiableList(modules);
    }

    public List<BoogerModule> getModulesByCategory(ModuleCategory category) {
        return modules.stream()
            .filter(m -> m.getCategory() == category)
            .toList();
    }
}
