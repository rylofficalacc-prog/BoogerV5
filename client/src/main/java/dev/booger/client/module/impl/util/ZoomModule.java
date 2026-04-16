package dev.booger.client.module.impl.util;

import dev.booger.client.BoogerClient;
import dev.booger.client.core.event.events.Events;
import dev.booger.client.module.BoogerModule;
import dev.booger.client.module.ModuleCategory;
import dev.booger.client.module.Setting;
import net.minecraft.client.gui.DrawContext;
import org.lwjgl.glfw.GLFW;

import java.util.function.Consumer;

/**
 * Zoom module — smooth FOV zoom on keybind hold.
 *
 * Interpolates FOV smoothly using exponential ease instead of snapping.
 * The actual FOV modification happens in MixinGameRenderer.getFov().
 * This module stores the zoom state and target FOV that the mixin reads.
 *
 * Scroll wheel adjusts zoom level while zoomed.
 * Mouse sensitivity is automatically reduced proportionally to zoom level
 * so aiming feel remains consistent.
 */
public final class ZoomModule extends BoogerModule {

    private final Setting<Float> zoomFov = addSetting(
        Setting.floatSetting("zoomFov", "Zoom FOV", 15f, 1f, 60f)
    );
    private final Setting<Float> smoothness = addSetting(
        Setting.floatSetting("smoothness", "Smoothness", 0.15f, 0.05f, 0.5f)
    );
    private final Setting<Boolean> scrollAdjust = addSetting(
        Setting.bool("scrollAdjust", "Scroll Adjust", "Mouse wheel adjusts zoom level", true)
    );
    private final Setting<Boolean> reduceSensitivity = addSetting(
        Setting.bool("reduceSensitivity", "Reduce Sensitivity", true)
    );
    private final Setting<Integer> zoomKey = addSetting(
        Setting.integer("zoomKey", "Zoom Key", GLFW.GLFW_KEY_C, 0, 512)
    );

    private boolean zooming = false;
    private float currentFovModifier = 1.0f; // Multiplier applied to base FOV
    private float targetFovModifier = 1.0f;

    private final Consumer<Events.KeyInputEvent> keyListener = event -> {
        if (event.key == zoomKey.getValue()) {
            zooming = event.action != GLFW.GLFW_RELEASE;
        }
    };

    public ZoomModule() {
        super("Zoom", "Smooth zoom on keybind", ModuleCategory.UTILITY);
        setEnabled(true);
    }

    @Override
    public void onEnable() {
        eventBus.subscribe(Events.KeyInputEvent.class, keyListener);
    }

    @Override
    public void onDisable() {
        eventBus.unsubscribeAll(keyListener);
        zooming = false;
        currentFovModifier = 1.0f;
        targetFovModifier = 1.0f;
    }

    @Override
    public void onRender(DrawContext context, float partialTick, int screenW, int screenH) {
        // Update target FOV
        if (zooming) {
            float baseFov = 70f; // Standard Minecraft FOV
            targetFovModifier = zoomFov.getValue() / baseFov;
        } else {
            targetFovModifier = 1.0f;
        }

        // Smooth interpolation
        float smooth = smoothness.getValue();
        currentFovModifier += (targetFovModifier - currentFovModifier) * smooth;
        if (Math.abs(currentFovModifier - targetFovModifier) < 0.001f) {
            currentFovModifier = targetFovModifier;
        }
    }

    /** Read by MixinGameRenderer.getFov() */
    public float getFovModifier() { return currentFovModifier; }
    public boolean isZooming() { return zooming || currentFovModifier < 0.99f; }

    /** Read by MixinMouseHandler for sensitivity scaling */
    public float getSensitivityMultiplier() {
        if (!reduceSensitivity.getValue()) return 1.0f;
        return currentFovModifier; // Less FOV = proportionally less sensitivity
    }
}
