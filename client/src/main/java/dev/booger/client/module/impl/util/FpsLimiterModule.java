package dev.booger.client.module.impl.util;

import dev.booger.client.BoogerClient;
import dev.booger.client.module.BoogerModule;
import dev.booger.client.module.ModuleCategory;
import dev.booger.client.module.Setting;
import net.minecraft.client.gui.DrawContext;

/**
 * FPS Limiter module.
 *
 * Exposes our FramePacingThread's FPS limit to the user.
 * This is separate from Minecraft's built-in FPS limiter because:
 * 1. Minecraft's limiter uses Thread.sleep which has ~2ms jitter
 * 2. Ours uses the hybrid sleep/spin approach for <0.1ms variance
 *
 * We do NOT disable Minecraft's own limiter — we cooperate with it.
 * If the user sets 144fps here and 300fps in vanilla settings, ours
 * takes precedence for precision. If vanilla is set lower, vanilla wins.
 */
public final class FpsLimiterModule extends BoogerModule {

    private final Setting<Integer> targetFps = addSetting(
        Setting.integer("targetFps", "Target FPS", 144, 30, 500)
            .onChange(fps -> BoogerClient.getInstance().getCore()
                .getFramePacingThread().setTargetFps(fps))
    );
    private final Setting<Boolean> adaptivePacing = addSetting(
        Setting.bool("adaptivePacing", "Adaptive Pacing",
            "Auto-adjust timing to compensate for systematic drift", true)
    );

    public FpsLimiterModule() {
        super("FPS Limiter", "Precise frame rate limiter", ModuleCategory.PERFORMANCE);
        setEnabled(false); // Off by default — users may prefer vanilla limiter
    }

    @Override
    public void onEnable() {
        BoogerClient.getInstance().getCore()
            .getFramePacingThread().setTargetFps(targetFps.getValue());
    }

    @Override
    public void onDisable() {
        BoogerClient.getInstance().getCore()
            .getFramePacingThread().setTargetFps(0); // Uncapped
    }

    @Override
    public void onRender(DrawContext context, float partialTick, int screenW, int screenH) {
        // No HUD element for this module
    }
}
