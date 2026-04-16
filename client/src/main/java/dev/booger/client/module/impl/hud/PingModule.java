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
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;

/**
 * Ping display module.
 *
 * TWO DATA SOURCES — user can choose:
 *
 * 1. SERVER-REPORTED PING: From PlayerListEntry.getLatency().
 *    Updated every ~2 seconds by the server's tab-list packet.
 *    Pros: Always available. Cons: Server can lie, low resolution.
 *
 * 2. PACKET-MEASURED RTT: From our LatencyTracker, which timestamps
 *    outgoing packets and measures time to first response.
 *    Pros: Real, accurate, updates every packet exchange.
 *    Cons: Requires our mixin to be active.
 *
 * We display both: "42ms (±3)" where 42 is the RTT and 3 is jitter (std dev
 * of last 10 measurements). Jitter is MORE important than raw ping in PvP —
 * consistent 80ms is better than spiking 20-120ms.
 *
 * Color coding follows standard thresholds used by competitive games:
 * Green: <50ms, Yellow: 50-100ms, Red: >100ms
 */
public final class PingModule extends BoogerModule implements TickableModule {

    private final Setting<Boolean> showJitter = addSetting(
        Setting.bool("showJitter", "Show Jitter", "Show ping variance (±Xms)", true)
    );
    private final Setting<Boolean> preferMeasured = addSetting(
        Setting.bool("preferMeasured", "Use Measured RTT",
            "Use packet-measured RTT instead of server-reported ping", true)
    );
    private final Setting<Boolean> showBackground = addSetting(
        Setting.bool("showBackground", "Background", true)
    );

    private String displayStr = "Ping: --";
    private long lastUpdateMs = 0;
    private static final long UPDATE_INTERVAL_MS = 500; // Update at 2Hz

    public PingModule() {
        super("Ping", "Network latency display", ModuleCategory.HUD);
        setEnabled(true);
        setHudPosition(2, 14); // Below FPS
    }

    @Override
    public void onTick(int tickCount) {
        // Rebuild at 2Hz — ping doesn't need faster updates
        long now = System.currentTimeMillis();
        if (now - lastUpdateMs > UPDATE_INTERVAL_MS) {
            lastUpdateMs = now;
            rebuildDisplay();
        }
    }

    private void rebuildDisplay() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.getNetworkHandler() == null) {
            displayStr = "Ping: --";
            return;
        }

        int ping;
        int jitterMs = -1;

        if (preferMeasured.getValue()) {
            // Use our packet-measured RTT
            ping = (int) BoogerClient.latency().getSmoothedRttMs();
            jitterMs = (int) BoogerClient.latency().getJitterMs();
        } else {
            // Fall back to server-reported
            ClientPlayNetworkHandler handler = mc.getNetworkHandler();
            PlayerListEntry entry = handler.getPlayerListEntry(mc.player.getUuid());
            ping = (entry != null) ? entry.getLatency() : -1;
        }

        if (ping < 0) {
            displayStr = "Ping: --";
            return;
        }

        if (showJitter.getValue() && jitterMs >= 0) {
            displayStr = "Ping: " + ping + "ms ±" + jitterMs;
        } else {
            displayStr = "Ping: " + ping + "ms";
        }
    }

    @Override
    public void onRender(DrawContext context, float partialTick, int screenW, int screenH) {
        BoogerFontRenderer font = BoogerClient.hud().getFontRenderer();
        var theme = ThemeEngine.current();

        // Determine color from current ping value
        int pingMs = preferMeasured.getValue()
            ? (int) BoogerClient.latency().getSmoothedRttMs()
            : getServerPing();

        int color;
        if (pingMs < 0)        color = theme.textSecondary();
        else if (pingMs < 50)  color = theme.colorGood();
        else if (pingMs < 100) color = theme.colorWarning();
        else                   color = theme.colorDanger();

        if (showBackground.getValue()) {
            font.drawTextBackground(context, displayStr, 0, 0,
                (int) theme.elementPadding(), theme.bgPrimary());
        }

        font.drawText(context, displayStr, 0, 0, color, theme.shadowText());
    }

    private int getServerPing() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.getNetworkHandler() == null) return -1;
        PlayerListEntry entry = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
        return (entry != null) ? entry.getLatency() : -1;
    }
}
