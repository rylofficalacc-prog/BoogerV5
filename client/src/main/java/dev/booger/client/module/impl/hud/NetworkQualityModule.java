package dev.booger.client.module.impl.hud;

import dev.booger.client.BoogerClient;
import dev.booger.client.module.BoogerModule;
import dev.booger.client.module.ModuleCategory;
import dev.booger.client.module.Setting;
import dev.booger.client.network.quality.NetworkQualityMonitor;
import dev.booger.client.render.BoogerFontRenderer;
import dev.booger.client.render.theme.ThemeEngine;
import net.minecraft.client.gui.DrawContext;

/**
 * Network quality HUD module.
 *
 * Renders a live latency graph + TPS + packet loss indicator.
 * This module surfaces data that NO other major client exposes in-game.
 *
 * GRAPH RENDERING:
 * We render the latency graph as a bar chart (not a line graph).
 * Bar charts render with simple fill() calls — no polyline math needed.
 * Each bar = one KeepAlive RTT sample (one per ~15 seconds on vanilla servers,
 * more frequent on servers with custom keep-alive intervals).
 *
 * Bars are color-coded by RTT tier:
 * Green: <50ms | Yellow: 50-100ms | Orange: 100-200ms | Red: >200ms
 *
 * TPS DISPLAY:
 * Shows server TPS as a number with a color-coded indicator.
 * "TPS: 20.0" in green = server is healthy.
 * "TPS: 14.2" in red = server lag, hit registration will be inconsistent.
 *
 * PACKET LOSS:
 * Shows estimated packet loss percentage.
 * >5% loss is shown in red with a blinking indicator.
 */
public final class NetworkQualityModule extends BoogerModule {

    private static final int GRAPH_WIDTH  = 120;
    private static final int GRAPH_HEIGHT = 40;
    private static final int BAR_GAP      = 1;

    private final Setting<Boolean> showGraph = addSetting(
        Setting.bool("showGraph", "Show Graph", "Render latency bar graph", true)
    );
    private final Setting<Boolean> showTps = addSetting(
        Setting.bool("showTps", "Show TPS", "Show server ticks per second", true)
    );
    private final Setting<Boolean> showLoss = addSetting(
        Setting.bool("showLoss", "Show Packet Loss", true)
    );
    private final Setting<Boolean> showBackground = addSetting(
        Setting.bool("showBackground", "Background", true)
    );

    public NetworkQualityModule() {
        super("NetQuality", "Network quality monitor", ModuleCategory.HUD);
        setEnabled(false); // Off by default — power user feature
        setHudPosition(2, 160);
    }

    @Override
    public void onRender(DrawContext context, float partialTick, int screenW, int screenH) {
        BoogerFontRenderer font = BoogerClient.hud().getFontRenderer();
        var theme = ThemeEngine.current();
        var monitor = BoogerClient.getInstance().getCore().getNetworkQualityMonitor();

        float y = 0;
        float pad = theme.elementPadding();
        float totalW = GRAPH_WIDTH + pad * 2;
        float totalH = calculateHeight(font);

        if (showBackground.getValue()) {
            font.drawBox(context, -pad, -pad, totalW, totalH + pad * 2, theme.bgPrimary());
        }

        // ── Latency graph ──────────────────────────────────────────────────
        if (showGraph.getValue()) {
            long[] samples = monitor.getLatencyGraphSnapshot();
            renderGraph(context, font, theme, samples, 0, y);
            y += GRAPH_HEIGHT + 4;
        }

        // ── TPS ───────────────────────────────────────────────────────────
        if (showTps.getValue()) {
            float tps = monitor.getSmoothedTps();
            int tpsColor = tps >= 19.5f ? theme.colorGood()
                         : tps >= 15f   ? theme.colorWarning()
                         : theme.colorDanger();
            String tpsStr = String.format("TPS: %.1f", tps);
            font.drawText(context, tpsStr, 0, y, tpsColor, theme.shadowText());
            y += font.getFontHeight() + 2;
        }

        // ── Packet loss ────────────────────────────────────────────────────
        if (showLoss.getValue()) {
            float loss = monitor.getPacketLossPercent();
            String lossStr = String.format("Loss: %.1f%%", loss);
            boolean blink = loss > 5f && (System.currentTimeMillis() / 500) % 2 == 0;
            int lossColor = loss < 1f  ? theme.colorGood()
                          : loss < 5f  ? theme.colorWarning()
                          : blink      ? 0xFFFFFFFF
                          : theme.colorDanger();
            font.drawText(context, lossStr, 0, y, lossColor, theme.shadowText());
            y += font.getFontHeight() + 2;
        }

        // ── Quality badge ──────────────────────────────────────────────────
        var quality = monitor.getCurrentQuality();
        String qualityStr = quality.name();
        int qualityColor = switch (quality) {
            case EXCELLENT -> theme.colorGood();
            case GOOD      -> BoogerFontRenderer.withAlpha(theme.colorGood(), 200);
            case FAIR      -> theme.colorWarning();
            case POOR      -> theme.colorDanger();
            case CRITICAL  -> 0xFFFF0000;
        };
        font.drawText(context, qualityStr, 0, y, qualityColor, theme.shadowText());
    }

    private void renderGraph(DrawContext context, BoogerFontRenderer font,
                              dev.booger.client.render.theme.Theme theme,
                              long[] samples, float x, float y) {
        // Graph background
        font.drawBox(context, x, y, GRAPH_WIDTH, GRAPH_HEIGHT, theme.bgSecondary());
        font.drawBorder(context, x, y, GRAPH_WIDTH, GRAPH_HEIGHT,
            BoogerFontRenderer.withAlpha(theme.bgBorder(), 120));

        if (samples.length == 0) {
            font.drawText(context, "No data", x + 4, y + GRAPH_HEIGHT / 2f - 4,
                theme.textSecondary(), false);
            return;
        }

        // Find max value for scaling (min 100ms for consistent scale)
        long maxMs = 100;
        for (long s : samples) maxMs = Math.max(maxMs, s);

        // Bar width from sample count
        int count = samples.length;
        float barW = Math.max(1f, (float)(GRAPH_WIDTH - BAR_GAP) / count - BAR_GAP);

        float curX = x + 1;
        for (int i = 0; i < count; i++) {
            long rtt = samples[i];
            float fraction = Math.min(1.0f, (float) rtt / maxMs);
            float barH = fraction * (GRAPH_HEIGHT - 2);

            // Color by RTT tier
            int barColor = rtt < 50  ? theme.colorGood()
                         : rtt < 100 ? theme.colorWarning()
                         : rtt < 200 ? 0xFFFF8800
                         : theme.colorDanger();

            float barTop = y + GRAPH_HEIGHT - 1 - barH;
            font.drawBox(context, curX, barTop, barW, barH, barColor);
            curX += barW + BAR_GAP;
        }

        // Axis label: max RTT
        font.drawText(context, maxMs + "ms", x + 2, y + 1, theme.textSecondary(), false);
    }

    private float calculateHeight(BoogerFontRenderer font) {
        float h = 0;
        if (showGraph.getValue())  h += GRAPH_HEIGHT + 4;
        if (showTps.getValue())    h += font.getFontHeight() + 2;
        if (showLoss.getValue())   h += font.getFontHeight() + 2;
        h += font.getFontHeight() + 2; // quality badge always shown
        return h;
    }
}
