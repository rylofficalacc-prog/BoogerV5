package dev.booger.client.module.impl.hud;

import dev.booger.client.BoogerClient;
import dev.booger.client.module.BoogerModule;
import dev.booger.client.module.ModuleCategory;
import dev.booger.client.module.Setting;
import dev.booger.client.module.TickableModule;
import dev.booger.client.render.BoogerFontRenderer;
import dev.booger.client.render.theme.ThemeEngine;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

/**
 * FPS Counter module.
 *
 * We do NOT use Minecraft's built-in FPS counter (it's in the F3 debug screen
 * and not accessible via public API cleanly). We maintain our own rolling
 * average over the last 60 frames using a circular buffer of frame timestamps.
 *
 * Rolling average provides much more stable readings than instantaneous FPS.
 * At 144fps, instantaneous FPS can swing ±20fps per frame due to GC and OS
 * scheduling. 60-frame rolling avg shows a stable, useful number.
 *
 * Update rate: We update the displayed string at most 4Hz to avoid text
 * reflow every frame (which would invalidate font width cache constantly).
 */
public final class FpsModule extends BoogerModule implements TickableModule {

    private static final int WINDOW = 60;

    // Circular buffer of frame timestamps in nanoseconds
    private final long[] frameTimes = new long[WINDOW];
    private int frameIndex = 0;
    private boolean bufferFull = false;

    // Display string — only rebuilt at most 4Hz
    private String displayStr = "FPS: --";
    private long lastUpdateMs = 0;
    private static final long UPDATE_INTERVAL_MS = 250; // 4Hz

    // Settings
    private final Setting<Boolean> showBackground = addSetting(
        Setting.bool("showBackground", "Background", "Show background box", true)
    );
    private final Setting<Boolean> colorCode = addSetting(
        Setting.bool("colorCode", "Color Code", "Color FPS number by performance tier", true)
    );
    private final Setting<Boolean> debugMode = addSetting(
        Setting.bool("debugMode", "Debug Info", "Show render time alongside FPS", false)
    );
    private final Setting<Integer> goodFps = addSetting(
        Setting.integer("goodFps", "Good FPS Threshold", 120, 30, 300)
    );
    private final Setting<Integer> okFps = addSetting(
        Setting.integer("okFps", "OK FPS Threshold", 60, 20, 200)
    );

    public FpsModule() {
        super("FPS", "Frames per second counter", ModuleCategory.HUD);
        setEnabled(true); // Default on
        // Default position: top-left
        setHudPosition(2, 2);
    }

    @Override
    public void onTick(int tickCount) {
        // Nothing — FPS is frame-driven, not tick-driven
        // (TickableModule is implemented but onTick is a no-op for this module)
        // We update in onRender() based on wall clock, not tick count
    }

    @Override
    public void onRender(DrawContext context, float partialTick, int screenW, int screenH) {
        BoogerFontRenderer font = BoogerClient.hud().getFontRenderer();
        var theme = ThemeEngine.current();

        // Record this frame's timestamp
        long now = System.nanoTime();
        frameTimes[frameIndex % WINDOW] = now;
        frameIndex++;
        if (frameIndex >= WINDOW) bufferFull = true;

        // Rebuild display string at 4Hz max
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastUpdateMs > UPDATE_INTERVAL_MS) {
            int fps = calculateFps(now);
            if (debugMode.getValue()) {
                long renderUs = BoogerClient.hud().getRenderTimeMicros();
                displayStr = "FPS: " + fps + " (" + renderUs + "μs)";
            } else {
                displayStr = "FPS: " + fps;
            }
            lastUpdateMs = nowMs;
        }

        // Determine text color
        int fps = calculateFps(now);
        int textColor;
        if (colorCode.getValue()) {
            if (fps >= goodFps.getValue()) textColor = theme.colorGood();
            else if (fps >= okFps.getValue()) textColor = theme.colorWarning();
            else textColor = theme.colorDanger();
        } else {
            textColor = theme.textValue();
        }

        // Draw background
        if (showBackground.getValue()) {
            font.drawTextBackground(context, displayStr, 0, 0,
                (int) theme.elementPadding(), theme.bgPrimary());
        }

        // Draw text (position is already transformed by HudRenderer matrix push)
        font.drawText(context, displayStr, 0, 0, textColor, theme.shadowText());
    }

    private int calculateFps(long nowNs) {
        int count = bufferFull ? WINDOW : frameIndex;
        if (count < 2) return 0;

        // Oldest frame in our window
        int oldestIdx = bufferFull ? (frameIndex % WINDOW) : 0;
        long oldest = frameTimes[oldestIdx];
        long elapsed = nowNs - oldest;

        if (elapsed == 0) return 0;
        // FPS = (count - 1) frames over elapsed nanoseconds
        return (int) ((long)(count - 1) * 1_000_000_000L / elapsed);
    }
}
