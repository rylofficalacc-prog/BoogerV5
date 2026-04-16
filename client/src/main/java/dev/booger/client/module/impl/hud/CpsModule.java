package dev.booger.client.module.impl.hud;

import dev.booger.client.BoogerClient;
import dev.booger.client.core.event.EventBus;
import dev.booger.client.core.event.events.Events;
import dev.booger.client.module.BoogerModule;
import dev.booger.client.module.ModuleCategory;
import dev.booger.client.module.Setting;
import dev.booger.client.render.BoogerFontRenderer;
import dev.booger.client.render.theme.ThemeEngine;
import net.minecraft.client.gui.DrawContext;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Clicks-per-second tracker.
 *
 * WHY NOT USE VANILLA ATTACK EVENTS:
 * Minecraft's attack handling goes through GameRenderer → MinecraftClient.doAttack()
 * and is throttle-gated at 4 CPS in 1.9+ (attack cooldown). Tracking at that
 * layer would give wrong CPS for rapid-clicking players.
 *
 * We track at the RAW INPUT level via our InputPollingThread event.
 * This shows true CPS from the hardware, unaffected by game throttling.
 * Players can see exactly how fast they're clicking and whether the game
 * is throttling them (if their raw CPS > displayed attack rate, attack cooldown
 * is the bottleneck, not their click speed).
 *
 * IMPLEMENTATION:
 * We maintain a timestamp deque of recent click events.
 * CPS = clicks whose timestamp is within the last 1000ms.
 * We prune old entries on every calculation — O(n) but n is tiny (<20 entries).
 *
 * Separate tracking for LMB and RMB:
 * - LMB: Attack CPS (relevant for PvP)
 * - RMB: Use CPS (relevant for blocking, eating speed)
 */
public final class CpsModule extends BoogerModule {

    private static final long WINDOW_MS = 1000;
    private static final int MAX_TRACKED = 50; // Sanity cap — nobody clicks >50 CPS

    private final Deque<Long> leftClicks = new ArrayDeque<>(MAX_TRACKED);
    private final Deque<Long> rightClicks = new ArrayDeque<>(MAX_TRACKED);

    // Peak tracking — show personal best in session
    private int peakLeftCps = 0;
    private int peakRightCps = 0;

    private final Setting<Boolean> showRight = addSetting(
        Setting.bool("showRight", "Show RMB", "Show right-click CPS", false)
    );
    private final Setting<Boolean> showPeak = addSetting(
        Setting.bool("showPeak", "Show Peak", "Show peak CPS for this session", false)
    );
    private final Setting<Boolean> showBackground = addSetting(
        Setting.bool("showBackground", "Background", true)
    );

    private String displayStr = "CPS: -";

    // Listener reference — stored for unsubscribe on disable
    private final java.util.function.Consumer<Events.MouseClickEvent> clickListener =
        this::onMouseClick;

    public CpsModule() {
        super("CPS", "Clicks per second tracker", ModuleCategory.HUD);
        setEnabled(true);
        setHudPosition(2, 26);
    }

    @Override
    public void onEnable() {
        eventBus.subscribe(Events.MouseClickEvent.class, clickListener);
    }

    @Override
    public void onDisable() {
        eventBus.unsubscribeAll(clickListener);
        leftClicks.clear();
        rightClicks.clear();
    }

    private void onMouseClick(Events.MouseClickEvent event) {
        if (event.action != GLFW.GLFW_PRESS) return; // Only count presses, not releases

        long now = System.currentTimeMillis();
        if (event.button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            if (leftClicks.size() < MAX_TRACKED) leftClicks.addLast(now);
        } else if (event.button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            if (rightClicks.size() < MAX_TRACKED) rightClicks.addLast(now);
        }
    }

    @Override
    public void onRender(DrawContext context, float partialTick, int screenW, int screenH) {
        BoogerFontRenderer font = BoogerClient.hud().getFontRenderer();
        var theme = ThemeEngine.current();

        long now = System.currentTimeMillis();
        int lCps = countAndPrune(leftClicks, now);
        int rCps = countAndPrune(rightClicks, now);

        peakLeftCps = Math.max(peakLeftCps, lCps);
        peakRightCps = Math.max(peakRightCps, rCps);

        // Build display string
        StringBuilder sb = new StringBuilder(24);
        sb.append("CPS: ").append(lCps);
        if (showRight.getValue()) {
            sb.append(" | R: ").append(rCps);
        }
        if (showPeak.getValue()) {
            sb.append(" (").append(peakLeftCps).append(")");
        }
        displayStr = sb.toString();

        if (showBackground.getValue()) {
            font.drawTextBackground(context, displayStr, 0, 0,
                (int) theme.elementPadding(), theme.bgPrimary());
        }

        // Color-code CPS value for attack speed awareness
        int textColor = lCps >= 10 ? theme.colorGood()
                      : lCps >= 6  ? theme.colorWarning()
                      : theme.textValue();

        font.drawText(context, displayStr, 0, 0, textColor, theme.shadowText());
    }

    /** Count clicks in the last WINDOW_MS and prune older ones. */
    private int countAndPrune(Deque<Long> deque, long now) {
        long cutoff = now - WINDOW_MS;
        // Remove from front (oldest first)
        while (!deque.isEmpty() && deque.peekFirst() < cutoff) {
            deque.pollFirst();
        }
        return deque.size();
    }
}
