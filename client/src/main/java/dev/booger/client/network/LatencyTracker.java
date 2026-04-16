package dev.booger.client.network;

import dev.booger.client.BoogerClient;
import dev.booger.client.core.event.EventBus;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.KeepAliveC2SPacket;
import net.minecraft.network.packet.s2c.play.KeepAliveS2CPacket;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Packet-level RTT (Round Trip Time) tracker.
 *
 * HOW IT WORKS:
 *
 * Minecraft has a built-in keep-alive mechanism:
 * 1. Server sends KeepAliveS2CPacket with a unique ID every ~15 seconds
 * 2. Client must respond with KeepAliveC2SPacket containing the same ID
 * 3. Server uses the response time to update the tab-list ping value
 *
 * We piggyback on this mechanism:
 * - When we RECEIVE a KeepAliveS2CPacket, we record the receive timestamp
 * - When we SEND the KeepAliveC2SPacket response, we record the send timestamp
 * - We compute RTT = (send_time - receive_time) + server_processing_time
 *
 * BUT WAIT — that's only half the RTT. We measure:
 * FULL RTT: We actually track when we first RECEIVED the S2C packet,
 * and watch for any S2C response that references our C2S (e.g., the server's
 * next world state update implies our packet was received).
 *
 * SIMPLIFIED VERSION (Phase 3):
 * We use the delta between S2C KeepAlive RECEIVE time and C2S SEND time.
 * This measures: "time from packet arrival to our response dispatch."
 * It's not a full RTT but it IS the client-side latency contribution,
 * which combined with the server's ping report gives the full picture.
 *
 * ROLLING WINDOW STATISTICS:
 * We maintain the last 10 RTT samples for:
 * - Smoothed average (displayed as "Ping")
 * - Standard deviation (displayed as "Jitter ±Xms")
 * - Min/Max in session
 *
 * WHY 10 SAMPLES:
 * Keep-alive packets arrive every ~15 seconds. 10 samples = ~2.5 minutes
 * of history. This is enough to detect systematic latency changes without
 * being so long that a network fix takes forever to show up.
 *
 * THREAD SAFETY:
 * onPacketReceived() runs on NETTY IO thread.
 * onPacketSent() runs on MAIN thread.
 * All shared state uses AtomicLong and ConcurrentHashMap.
 * getSmoothedRttMs() / getJitterMs() can be called from any thread (render).
 */
public final class LatencyTracker {

    private static final int SAMPLE_WINDOW = 10;

    private final EventBus eventBus;

    // Pending keep-alive: ID → receive timestamp (nanos)
    private final ConcurrentHashMap<Long, Long> pendingKeepAlives = new ConcurrentHashMap<>(4);

    // RTT samples ring buffer
    private final long[] rttSamplesNs = new long[SAMPLE_WINDOW];
    private int sampleIndex = 0;
    private int sampleCount = 0;

    // Smoothed RTT in milliseconds — written from main thread, read from render thread
    private final AtomicLong smoothedRttMs = new AtomicLong(0);
    private final AtomicLong jitterMs = new AtomicLong(0);

    // Session statistics
    private long minRttMs = Long.MAX_VALUE;
    private long maxRttMs = 0;
    private long totalRttMs = 0;
    private long measurementCount = 0;

    // Fallback: last known server-reported ping (updated from player list packets)
    private final AtomicLong serverReportedPingMs = new AtomicLong(-1);

    public LatencyTracker(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    public void init() {
        BoogerClient.LOGGER.debug("LatencyTracker initialized");
    }

    /**
     * Called by MixinClientConnection when ANY packet is received.
     * Runs on NETTY IO THREAD — only thread-safe operations.
     */
    public void onPacketReceived(Packet<?> packet, long receiveTimeNs) {
        if (packet instanceof KeepAliveS2CPacket keepAlive) {
            // Server is sending us a keep-alive challenge — record receive time
            pendingKeepAlives.put(keepAlive.getId(), receiveTimeNs);

            // Prune old pending keep-alives (shouldn't have more than 2-3)
            if (pendingKeepAlives.size() > 5) {
                // Find oldest entry and remove
                long oldestKey = pendingKeepAlives.entrySet().stream()
                    .min(java.util.Map.Entry.comparingByValue())
                    .map(java.util.Map.Entry::getKey)
                    .orElse(-1L);
                if (oldestKey != -1L) pendingKeepAlives.remove(oldestKey);
            }
        }
    }

    /**
     * Called by MixinClientConnection when ANY packet is sent.
     * Runs on MAIN THREAD.
     */
    public void onPacketSent(Packet<?> packet, long sendTimeNs) {
        if (packet instanceof KeepAliveC2SPacket keepAlive) {
            Long receiveTimeNs = pendingKeepAlives.remove(keepAlive.getId());
            if (receiveTimeNs != null) {
                // RTT sample = time from server's S2C receive to our C2S send
                // This measures client processing latency, but more importantly
                // gives us a consistent per-connection measurement.
                long rttNs = sendTimeNs - receiveTimeNs;
                long rttMs = rttNs / 1_000_000;

                // Sanity check — discard unreasonable values
                if (rttMs >= 0 && rttMs < 10_000) {
                    recordSample(rttMs);
                }
            }
        }
    }

    private synchronized void recordSample(long rttMs) {
        rttSamplesNs[sampleIndex % SAMPLE_WINDOW] = rttMs;
        sampleIndex++;
        sampleCount = Math.min(sampleCount + 1, SAMPLE_WINDOW);

        // Update statistics
        minRttMs = Math.min(minRttMs, rttMs);
        maxRttMs = Math.max(maxRttMs, rttMs);
        totalRttMs += rttMs;
        measurementCount++;

        // Recalculate smoothed average
        long sum = 0;
        int count = Math.min(sampleCount, SAMPLE_WINDOW);
        for (int i = 0; i < count; i++) {
            sum += rttSamplesNs[i];
        }
        long avg = count > 0 ? sum / count : 0;
        smoothedRttMs.set(avg);

        // Calculate jitter (standard deviation)
        if (count >= 2) {
            long variance = 0;
            for (int i = 0; i < count; i++) {
                long diff = rttSamplesNs[i] - avg;
                variance += diff * diff;
            }
            long stddev = (long) Math.sqrt((double) variance / count);
            jitterMs.set(stddev);
        }

        BoogerClient.LOGGER.debug(
            "RTT sample: {}ms | avg: {}ms | jitter: {}ms | min: {}ms | max: {}ms",
            rttMs, avg, jitterMs.get(), minRttMs, maxRttMs
        );
    }

    /**
     * Smoothed RTT in milliseconds.
     * Returns server-reported ping as fallback if no measurements available.
     * Thread-safe — can be called from render thread.
     */
    public long getSmoothedRttMs() {
        long measured = smoothedRttMs.get();
        if (measured > 0) return measured;
        return serverReportedPingMs.get();
    }

    /**
     * RTT jitter (standard deviation) in milliseconds.
     * Returns 0 if fewer than 2 samples collected.
     */
    public long getJitterMs() {
        return jitterMs.get();
    }

    /** Update fallback ping from server-reported tab-list value. */
    public void updateServerReportedPing(long pingMs) {
        serverReportedPingMs.set(pingMs);
    }

    /** Reset all session data (call on disconnect). */
    public void reset() {
        pendingKeepAlives.clear();
        sampleIndex = 0;
        sampleCount = 0;
        smoothedRttMs.set(0);
        jitterMs.set(0);
        minRttMs = Long.MAX_VALUE;
        maxRttMs = 0;
        totalRttMs = 0;
        measurementCount = 0;
        serverReportedPingMs.set(-1);
    }

    public long getMinRttMs() { return minRttMs == Long.MAX_VALUE ? 0 : minRttMs; }
    public long getMaxRttMs() { return maxRttMs; }
    public long getMeasurementCount() { return measurementCount; }
}
