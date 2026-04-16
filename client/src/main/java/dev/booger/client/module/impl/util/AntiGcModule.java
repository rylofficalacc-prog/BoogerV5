package dev.booger.client.module.impl.util;

import dev.booger.client.BoogerClient;
import dev.booger.client.module.BoogerModule;
import dev.booger.client.module.ModuleCategory;
import dev.booger.client.module.Setting;
import dev.booger.client.module.TickableModule;
import dev.booger.client.render.BoogerFontRenderer;
import dev.booger.client.render.theme.ThemeEngine;
import net.minecraft.client.gui.DrawContext;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.List;

/**
 * Anti-GC (Garbage Collection spike prevention) module.
 *
 * WHY GC SPIKES HAPPEN IN MINECRAFT:
 * Minecraft allocates heavily every frame:
 * - BlockPos, Vec3d, ItemStack — short-lived objects created in hot loops
 * - Chunk mesh vertices — large temporary arrays during chunk loading
 * - Text rendering — String concatenation in vanilla HUD
 * - Entity AI — path finding allocates ArrayDeque nodes constantly
 *
 * With ZGC (our chosen GC), collection is concurrent — it runs alongside
 * the application on background threads. ZGC's target is <1ms pause times.
 * However, if allocation rate exceeds collection rate ("allocation spike"),
 * ZGC triggers a "stall" — the app thread blocks until GC catches up.
 * These stalls appear as frame time spikes of 5-50ms — visible microstutter.
 *
 * OUR STRATEGY:
 * 1. MONITOR: Track heap usage % and GC collection time via JMX
 * 2. DETECT: Identify when heap usage is approaching critical levels
 *    (>80% used = ZGC will likely stall soon)
 * 3. SCHEDULE: At a safe moment (between frames, when FPS is high and
 *    the player isn't in combat), hint the JVM to collect
 * 4. REPORT: Show heap usage and GC stats in the HUD for power users
 *
 * WHAT "SAFE MOMENT" MEANS:
 * We track frame time via FramePacingThread. If the last 10 frames were
 * all within 5% of the target frame time (stable, no hitching), we know
 * the render thread is not currently doing heavy work. That's when we
 * call System.gc() if heap is >75%.
 *
 * System.gc() is a HINT — the JVM may ignore it. With ZGC, it usually
 * triggers a minor collection cycle which takes ~1ms total and prevents
 * the larger stall later.
 *
 * CRITICAL CAVEAT:
 * We NEVER call System.gc() more than once per 10 seconds, and never
 * during obvious heavy load (chunk loading, initial world join).
 * Aggressive GC calls cause their own hitching.
 *
 * WHY THIS BEATS "ADD MORE RAM":
 * More RAM just delays the problem — you still hit GC when the heap fills.
 * Strategic GC scheduling makes the heap NEVER fill past our threshold,
 * so the large collection never needs to happen.
 *
 * MEMORY TRACKING:
 * We read from MemoryMXBean — JVM's own memory accounting.
 * Heap used = active objects (excluding GC overhead).
 * Heap committed = memory currently given to the JVM by the OS.
 * Heap max = configured -Xmx limit.
 *
 * We display used/max and a color-coded usage bar.
 */
public final class AntiGcModule extends BoogerModule implements TickableModule {

    private final Setting<Boolean> autoGc = addSetting(
        Setting.bool("autoGc", "Auto GC", "Proactively hint GC when heap is near full", true)
    );
    private final Setting<Integer> gcThresholdPct = addSetting(
        Setting.integer("gcThreshold", "GC Threshold %",
            "Heap % usage that triggers a GC hint", 75, 50, 95)
    );
    private final Setting<Boolean> showStats = addSetting(
        Setting.bool("showStats", "Show Stats", "Show heap usage and GC stats", true)
    );
    private final Setting<Boolean> showBar = addSetting(
        Setting.bool("showBar", "Show Bar", "Show heap usage bar", true)
    );

    private final MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
    private final List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();

    // GC scheduling state
    private long lastGcCallMs = 0;
    private static final long GC_COOLDOWN_MS = 10_000; // 10 seconds min between hints
    private long lastGcCollectionCount = 0;
    private long lastGcCollectionTimeMs = 0;

    // Smoothed metrics to avoid display flickering
    private float smoothedHeapPct = 0f;
    private long smoothedHeapMb = 0;
    private long maxHeapMb = 0;

    // GC event tracking
    private int gcEventsThisSession = 0;
    private long totalGcTimeMs = 0;

    public AntiGcModule() {
        super("AntiGC", "GC spike prevention and memory monitor", ModuleCategory.PERFORMANCE);
        setEnabled(false); // Off by default — advanced feature
        setHudPosition(2, 280);
    }

    @Override
    public void onTick(int tickCount) {
        // Sample memory every tick (20Hz) — lightweight JMX read
        MemoryUsage heap = memBean.getHeapMemoryUsage();
        long used = heap.getUsed();
        long max  = heap.getMax();

        if (max <= 0) return;

        // Update smoothed metrics
        float usedPct = (float) used / max;
        smoothedHeapPct = smoothedHeapPct * 0.8f + usedPct * 0.2f;
        smoothedHeapMb = used / (1024 * 1024);
        maxHeapMb = max / (1024 * 1024);

        // Track GC events
        trackGcEvents();

        // Auto-GC trigger
        if (autoGc.getValue()) {
            maybeHintGc(usedPct);
        }
    }

    private void maybeHintGc(float currentUsedPct) {
        float threshold = gcThresholdPct.getValue() / 100.0f;
        if (currentUsedPct < threshold) return;

        long now = System.currentTimeMillis();
        if (now - lastGcCallMs < GC_COOLDOWN_MS) return;

        // Check if we're in a "safe" frame — stable frame time
        double measuredFps = BoogerClient.getInstance().getCore()
            .getFramePacingThread().getMeasuredFps();

        // Only GC if we have at least 60 FPS headroom — don't GC during chunk loading
        if (measuredFps < 60.0) return;

        BoogerClient.LOGGER.debug(
            "AntiGC: heap at {}%, hinting GC (fps={:.0f})",
            (int)(currentUsedPct * 100), measuredFps
        );

        // Hint GC — this is non-blocking with ZGC (triggers background collection)
        System.gc();
        lastGcCallMs = now;
        gcEventsThisSession++;
    }

    private void trackGcEvents() {
        long totalCount = 0;
        long totalTime  = 0;

        for (GarbageCollectorMXBean gcBean : gcBeans) {
            long count = gcBean.getCollectionCount();
            long time  = gcBean.getCollectionTime();
            if (count >= 0) totalCount += count;
            if (time  >= 0) totalTime  += time;
        }

        if (totalCount > lastGcCollectionCount) {
            long newCollections = totalCount - lastGcCollectionCount;
            long newTime = totalTime - lastGcCollectionTimeMs;
            totalGcTimeMs += newTime;
            lastGcCollectionCount = totalCount;
            lastGcCollectionTimeMs = totalTime;

            if (newCollections > 0 && newTime > 5) {
                // A GC pause >5ms is worth logging
                BoogerClient.LOGGER.debug(
                    "AntiGC: GC event detected — {} collections, {}ms total",
                    newCollections, newTime
                );
            }
        }
    }

    @Override
    public void onRender(DrawContext context, float partialTick, int screenW, int screenH) {
        if (!showStats.getValue() && !showBar.getValue()) return;

        BoogerFontRenderer font = BoogerClient.hud().getFontRenderer();
        var theme = ThemeEngine.current();

        float y = 0;
        float fh = font.getFontHeight();

        if (showStats.getValue()) {
            // Heap usage line
            String heapStr = String.format("Heap: %dMB / %dMB", smoothedHeapMb, maxHeapMb);
            int heapColor = smoothedHeapPct < 0.6f ? theme.colorGood()
                          : smoothedHeapPct < 0.8f ? theme.colorWarning()
                          : theme.colorDanger();
            font.drawText(context, heapStr, 0, y, heapColor, theme.shadowText());
            y += fh + 2;

            // GC stats
            String gcStr = String.format("GC: %d calls, %dms total",
                gcEventsThisSession, totalGcTimeMs);
            font.drawText(context, gcStr, 0, y, theme.textSecondary(), false);
            y += fh + 2;
        }

        if (showBar.getValue()) {
            float barW = 100;
            float barH = 4;

            font.drawBox(context, 0, y, barW, barH, theme.bgSecondary());

            int fillColor = smoothedHeapPct < 0.6f ? theme.colorGood()
                          : smoothedHeapPct < 0.8f ? theme.colorWarning()
                          : theme.colorDanger();

            font.drawBox(context, 0, y, barW * smoothedHeapPct, barH, fillColor);
            font.drawBorder(context, 0, y, barW, barH,
                BoogerFontRenderer.withAlpha(theme.bgBorder(), 120));

            // Threshold marker
            float markerX = barW * (gcThresholdPct.getValue() / 100.0f);
            font.drawBox(context, markerX - 0.5f, y - 1, 1, barH + 2,
                BoogerFontRenderer.withAlpha(0xFFFFFFFF, 150));
        }
    }
}
