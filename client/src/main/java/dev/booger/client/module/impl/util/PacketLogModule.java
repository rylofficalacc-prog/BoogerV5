package dev.booger.client.module.impl.util;

import dev.booger.client.BoogerClient;
import dev.booger.client.core.event.EventBus;
import dev.booger.client.core.event.events.Events;
import dev.booger.client.module.BoogerModule;
import dev.booger.client.module.ModuleCategory;
import dev.booger.client.module.Setting;
import dev.booger.client.module.TickableModule;
import dev.booger.client.render.BoogerFontRenderer;
import dev.booger.client.render.theme.ThemeEngine;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.network.packet.Packet;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;

/**
 * Packet Log module — shows recent packet activity for debugging.
 * Disabled by default. Useful for diagnosing lag spikes and connection issues.
 */
public final class PacketLogModule extends BoogerModule implements TickableModule {

    private static final int MAX_ENTRIES = 8;

    private final Setting<Boolean> showSent = addSetting(
        Setting.bool("showSent", "Show Sent", false)
    );
    private final Setting<Boolean> showReceived = addSetting(
        Setting.bool("showReceived", "Show Received", true)
    );

    private record LogEntry(String text, long timestamp, boolean sent) {}
    private final Deque<LogEntry> entries = new ArrayDeque<>(MAX_ENTRIES);

    private final Consumer<Events.PacketReceiveEvent> receiveListener = event -> {
        if (!showReceived.getValue()) return;
        addEntry("← " + shortName(event.packet), false);
    };
    private final Consumer<Events.PacketSendEvent> sendListener = event -> {
        if (!showSent.getValue()) return;
        addEntry("→ " + shortName(event.packet), true);
    };

    public PacketLogModule() {
        super("PacketLog", "Recent packet activity log", ModuleCategory.UTILITY);
        setEnabled(false);
        setHudPosition(200, 200);
    }

    @Override
    public void onEnable() {
        eventBus.subscribe(Events.PacketReceiveEvent.class, receiveListener, EventBus.Priority.LOWEST);
        eventBus.subscribe(Events.PacketSendEvent.class, sendListener, EventBus.Priority.LOWEST);
    }

    @Override
    public void onDisable() {
        eventBus.unsubscribeAll(receiveListener);
        eventBus.unsubscribeAll(sendListener);
        entries.clear();
    }

    @Override
    public void onTick(int tickCount) {
        // Prune entries older than 3 seconds
        long cutoff = System.currentTimeMillis() - 3000;
        entries.removeIf(e -> e.timestamp() < cutoff);
    }

    private void addEntry(String text, boolean sent) {
        if (entries.size() >= MAX_ENTRIES) entries.pollFirst();
        entries.addLast(new LogEntry(text, System.currentTimeMillis(), sent));
    }

    private static String shortName(Packet<?> packet) {
        String name = packet.getClass().getSimpleName();
        // Strip common prefixes for readability
        return name.replace("Packet", "").replace("C2S", "").replace("S2C", "");
    }

    @Override
    public void onRender(DrawContext context, float partialTick, int screenW, int screenH) {
        if (entries.isEmpty()) return;

        BoogerFontRenderer font = BoogerClient.hud().getFontRenderer();
        var theme = ThemeEngine.current();

        float y = 0;
        float fh = font.getFontHeight() + 1;

        for (LogEntry entry : entries) {
            long age = System.currentTimeMillis() - entry.timestamp();
            float alpha = Math.max(0f, 1f - age / 3000f);
            int color = BoogerFontRenderer.scaleAlpha(
                entry.sent() ? theme.colorWarning() : theme.accentPrimary(), alpha);
            font.drawText(context, entry.text(), 0, y, color, false);
            y += fh;
        }
    }
}
