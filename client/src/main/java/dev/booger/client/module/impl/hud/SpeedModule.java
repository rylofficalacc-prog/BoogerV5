package dev.booger.client.module.impl.hud;

import dev.booger.client.BoogerClient;
import dev.booger.client.module.BoogerModule;
import dev.booger.client.module.ModuleCategory;
import dev.booger.client.module.Setting;
import dev.booger.client.render.BoogerFontRenderer;
import dev.booger.client.render.theme.ThemeEngine;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.MathHelper;

public final class SpeedModule extends BoogerModule {

    private final Setting<Boolean> showPeak = addSetting(
        Setting.bool("showPeak", "Show Peak", false)
    );

    private double peakSpeed = 0;
    private double smoothedSpeed = 0;
    private static final double SMOOTHING = 0.85;

    public SpeedModule() {
        super("Speed", "Horizontal movement speed", ModuleCategory.HUD);
        setEnabled(true);
        setHudPosition(2, 252);
    }

    @Override
    public void onRender(DrawContext context, float partialTick, int screenW, int screenH) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        BoogerFontRenderer font = BoogerClient.hud().getFontRenderer();
        var theme = ThemeEngine.current();

        // Horizontal velocity magnitude in blocks/tick, converted to blocks/sec
        double vx = mc.player.getVelocity().x;
        double vz = mc.player.getVelocity().z;
        double speedBps = Math.sqrt(vx * vx + vz * vz) * 20.0; // blocks/sec

        // Exponential smoothing to reduce flickering
        smoothedSpeed = smoothedSpeed * SMOOTHING + speedBps * (1.0 - SMOOTHING);
        peakSpeed = Math.max(peakSpeed, smoothedSpeed);

        String text;
        if (showPeak.getValue()) {
            text = String.format("Speed: %.2f (%.2f) b/s", smoothedSpeed, peakSpeed);
        } else {
            text = String.format("Speed: %.2f b/s", smoothedSpeed);
        }

        font.drawText(context, text, 0, 0, theme.textPrimary(), theme.shadowText());
    }
}
