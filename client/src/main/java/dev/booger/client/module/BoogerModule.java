package dev.booger.client.module;

import dev.booger.client.config.ConfigManager;
import dev.booger.client.core.event.EventBus;
import dev.booger.client.render.HudRenderer;

import java.util.ArrayList;
import java.util.List;

/**
 * Base class for all Booger Client modules.
 *
 * Every feature — FPS counter, CPS meter, TargetHUD, HitColor — is a module.
 * Modules are self-contained: they own their settings, their render logic,
 * their event subscriptions. ModuleManager just owns the list and lifecycle.
 *
 * Design comparison with Lunar:
 * Lunar modules use an annotation-based system with reflection for settings.
 * We use explicit getValue/setValue with typed Setting<T> objects.
 * Zero reflection on the hot path. Settings are just fields with metadata.
 *
 * Module lifecycle:
 *   REGISTERED → enabled() → onEnable() → [tick/render events] → onDisable() → disabled()
 *
 * PERFORMANCE CONTRACT:
 * - onTick() is called 20x/sec. Must be O(1) with no allocations.
 * - onRender() is called every frame. Must be O(1). DrawContext calls only.
 * - onEnable()/onDisable() may allocate freely — called rarely.
 */
public abstract class BoogerModule {

    // Module metadata
    private final String name;
    private final String description;
    private final ModuleCategory category;

    // State
    private boolean enabled = false;
    private boolean visible = true; // HUD visibility — separate from enabled

    // Dependencies — injected by ModuleManager
    protected EventBus eventBus;
    protected ConfigManager configManager;
    protected HudRenderer hudRenderer;

    // Settings registered by this module
    private final List<Setting<?>> settings = new ArrayList<>();

    // HUD position — for modules that render on-screen elements
    protected float hudX = 10;
    protected float hudY = 10;
    protected float hudScale = 1.0f;

    public BoogerModule(String name, String description, ModuleCategory category) {
        this.name = name;
        this.description = description;
        this.category = category;
    }

    /**
     * Called by ModuleManager after dependency injection.
     * Override to subscribe to events, initialize state.
     *
     * IMPORTANT: Subscribe to events here, NOT in the constructor.
     * The event bus is not ready during construction.
     */
    public void onRegister() {}

    /**
     * Called when the module is enabled (by user or config load).
     * Subscribe to events here if you use conditional subscription
     * (subscribe on enable, unsubscribe on disable — saves dispatch overhead
     * for modules that are usually off).
     */
    public void onEnable() {}

    /**
     * Called when the module is disabled.
     * Unsubscribe from events, clean up state.
     */
    public void onDisable() {}

    /**
     * Tick update — 20Hz. Override in modules that need per-tick logic.
     * Default is no-op. Do NOT call super.onTick() if you override.
     */
    public void onTick(int tickCount) {}

    /**
     * Called by the event system once per frame when enabled and visible.
     * Implement HUD rendering here.
     *
     * @param context   DrawContext for this frame
     * @param partialTick Interpolation factor [0.0, 1.0] between ticks
     * @param screenW   Current screen width
     * @param screenH   Current screen height
     */
    public void onRender(net.minecraft.client.gui.DrawContext context,
                         float partialTick, int screenW, int screenH) {}

    /**
     * Enable/disable this module. Fires onEnable/onDisable hooks.
     */
    public final void setEnabled(boolean enabled) {
        if (this.enabled == enabled) return;
        this.enabled = enabled;

        if (enabled) {
            onEnable();
        } else {
            onDisable();
        }

        // Persist state
        if (configManager != null) {
            configManager.markDirty();
        }
    }

    public final void toggle() {
        setEnabled(!enabled);
    }

    /**
     * Register a setting for this module.
     * Settings are serialized by ConfigManager and shown in the GUI.
     */
    protected <T> Setting<T> addSetting(Setting<T> setting) {
        settings.add(setting);
        return setting;
    }

    // ─── Dependency injection — called by ModuleManager ─────────────────────

    final void inject(EventBus bus, ConfigManager config, HudRenderer hud) {
        this.eventBus = bus;
        this.configManager = config;
        this.hudRenderer = hud;
    }

    // ─── Getters ─────────────────────────────────────────────────────────────

    public String getName() { return name; }
    public String getDescription() { return description; }
    public ModuleCategory getCategory() { return category; }
    public boolean isEnabled() { return enabled; }
    public boolean isVisible() { return visible; }
    public List<Setting<?>> getSettings() { return settings; }
    public float getHudX() { return hudX; }
    public float getHudY() { return hudY; }
    public float getHudScale() { return hudScale; }

    public void setHudPosition(float x, float y) {
        this.hudX = x;
        this.hudY = y;
        if (configManager != null) configManager.markDirty();
    }

    public void setHudScale(float scale) {
        this.hudScale = Math.max(0.5f, Math.min(3.0f, scale));
        if (configManager != null) configManager.markDirty();
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    @Override
    public String toString() {
        return String.format("Module[%s, enabled=%b]", name, enabled);
    }
}
