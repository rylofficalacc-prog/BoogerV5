package dev.booger.client.network.quality;

import dev.booger.client.BoogerClient;
import dev.booger.client.core.event.EventBus;
import dev.booger.client.core.event.events.Events;
import net.minecraft.network.packet.s2c.play.WorldTimeUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.KeepAliveS2CPacket;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Network quality monitor.
 *
 * Tracks three metrics that Lunar/Badlion do NOT expose:
 *
 * 1. PACKET LOSS ESTIMATION
 *    We track KeepAlive packet IDs. The server sends them sequentially.
 *    If we receive ID 5 after ID 3 (skipping 4), packet 4 was lost.
 *    We express loss as a percentage over the last 100 keep-alive cycles.
 *
 *    NOTE: This is an approximation. True packet loss requires ICMP ping
 *    or TCP sequence analysis. Since Minecraft uses TCP, "lost" packets
 *    are actually retransmitted by TCP — what we're really detecting is
 *    packets delivered OUT OF ORDER or with abnormal delays. This maps
 *    closely to perceived connection quality.
 *
 * 2. SERVER TPS (Ticks Per Second)
 *    The server sends WorldTimeUpdateS2CPacket every 20 ticks.
 *    Each packet contains the world time. By measuring the real-world
 *    elapsed time between consecutive WorldTimeUpdate packets, we can
 *    calculate how many game ticks the server processed per second.
 *
 *    A healthy server = 20 TPS.
 *    A lagging server = <20 TPS (time slows relative to real time).
 *    Server-side lag causes rubber-banding and hit registration issues
 *    in PvP. Displaying TPS helps players distinguish "my connection is
 *    bad" from "the server is lagging."
 *
 * 3. LATENCY GRAPH DATA
 *    Rolling 60-sample array of RTT measurements (one per KeepAlive cycle).
 *    Used by the NetworkQualityModule to render a live latency graph.
 *    Graph shows trends — a steadily rising line means connection degradation.
 *
 * THREAD SAFETY:
 * onPacketReceived() runs on Netty IO thread.
 * All getters are called from main/render thread.
 * Using AtomicInteger/AtomicLong/AtomicReference for shared state.
 */
public final class NetworkQualityMonitor {

    // TPS measurement
    private final AtomicLong lastTimeUpdateNs = new AtomicLong(0);
    private final AtomicLong lastWorldTime = new AtomicLong(-1);
    private volatile float smoothedTps = 20.0f;
    private static final float TPS_SMOOTH = 0.85f;

    // Packet loss tracking
    private final AtomicLong lastKeepAliveId = new AtomicLong(-1);
    private final AtomicInteger lostPacketCount = new AtomicInteger(0);
    private final AtomicInteger totalKeepAliveCount = new AtomicInteger(0);
    private volatile float packetLossPercent = 0.0f;

    // Latency graph — rolling 60 samples
    private static final int GRAPH_SAMPLES = 60;
    private final long[] latencyGraph = new long[GRAPH_SAMPLES];
    private int graphIndex = 0;
    private int graphCount = 0;

    // Current connection quality tier
    public enum Quality { EXCELLENT, GOOD, FAIR, POOR, CRITICAL }
    private volatile Quality currentQuality = Quality.GOOD;

    // Stats
    private final AtomicLong peakRttMs = new AtomicLong(0);
    private final AtomicLong sessionPacketsReceived = new AtomicLong(0);

    private final EventBus eventBus;

    public NetworkQualityMonitor(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    public void init() {
        eventBus.subscribe(Events.PacketReceiveEvent.class, this::onPacketReceived);
        BoogerClient.LOGGER.debug("NetworkQualityMonitor initialized");
    }

    private void onPacketReceived(Events.PacketReceiveEvent event) {
        sessionPacketsReceived.incrementAndGet();

        if (event.packet instanceof WorldTimeUpdateS2CPacket timePacket) {
            onTimeUpdate(timePacket, event.receiveTimeNanos);
        } else if (event.packet instanceof KeepAliveS2CPacket keepAlive) {
            onKeepAlive(keepAlive.getId(), event.receiveTimeNanos);
        }
    }

    private void onTimeUpdate(WorldTimeUpdateS2CPacket packet, long receiveNs) {
        long worldTime = packet.getTime();
        long prevNs = lastTimeUpdateNs.getAndSet(receiveNs);
        long prevWorldTime = lastWorldTime.getAndSet(worldTime);

        if (prevNs == 0 || prevWorldTime < 0) return; // First packet

        long elapsedNs = receiveNs - prevNs;
        long worldTicksElapsed = worldTime - prevWorldTime;

        if (elapsedNs <= 0 || worldTicksElapsed <= 0) return;

        // TPS = ticks elapsed / real seconds elapsed
        double elapsedSec = elapsedNs / 1_000_000_000.0;
        double instantTps = worldTicksElapsed / elapsedSec;

        // Clamp — server can't exceed 20 TPS, don't go below 0
        instantTps = Math.max(0, Math.min(20.0, instantTps));

        // Exponential moving average
        smoothedTps = (float)(smoothedTps * TPS_SMOOTH + instantTps * (1.0 - TPS_SMOOTH));

        updateQuality();
    }

    private void onKeepAlive(long id, long receiveNs) {
        long prevId = lastKeepAliveId.getAndSet(id);
        int total = totalKeepAliveCount.incrementAndGet();

        if (prevId >= 0) {
            // Detect skipped IDs — indicative of reordering/loss
            long skipped = id - prevId - 1;
            if (skipped > 0 && skipped < 100) { // Sanity check — skip impossible jumps
                lostPacketCount.addAndGet((int) skipped);
            }

            // Recalculate loss percentage
            if (total > 0) {
                packetLossPercent = (float) lostPacketCount.get() / total * 100f;
            }
        }

        // Record RTT from LatencyTracker
        long rtt = BoogerClient.latency().getSmoothedRttMs();
        if (rtt > 0) {
            recordLatencySample(rtt);
            peakRttMs.accumulateAndGet(rtt, Math::max);
        }

        updateQuality();
    }

    private synchronized void recordLatencySample(long rttMs) {
        latencyGraph[graphIndex % GRAPH_SAMPLES] = rttMs;
        graphIndex++;
        graphCount = Math.min(graphCount + 1, GRAPH_SAMPLES);
    }

    private void updateQuality() {
        long rtt = BoogerClient.latency().getSmoothedRttMs();
        float loss = packetLossPercent;
        float tps = smoothedTps;

        Quality q;
        if (rtt < 50 && loss < 1f && tps > 19.5f)       q = Quality.EXCELLENT;
        else if (rtt < 100 && loss < 3f && tps > 18f)    q = Quality.GOOD;
        else if (rtt < 150 && loss < 8f && tps > 15f)    q = Quality.FAIR;
        else if (rtt < 300 && loss < 20f && tps > 10f)   q = Quality.POOR;
        else                                               q = Quality.CRITICAL;

        currentQuality = q;
    }

    /**
     * Get the latency graph data for rendering.
     * Returns a defensive copy — safe to call from render thread.
     */
    public synchronized long[] getLatencyGraphSnapshot() {
        int count = Math.min(graphCount, GRAPH_SAMPLES);
        long[] snapshot = new long[count];
        for (int i = 0; i < count; i++) {
            int idx = (graphIndex - count + i + GRAPH_SAMPLES) % GRAPH_SAMPLES;
            snapshot[i] = latencyGraph[idx];
        }
        return snapshot;
    }

    public float getSmoothedTps() { return smoothedTps; }
    public float getPacketLossPercent() { return packetLossPercent; }
    public Quality getCurrentQuality() { return currentQuality; }
    public long getPeakRttMs() { return peakRttMs.get(); }
    public long getSessionPacketsReceived() { return sessionPacketsReceived.get(); }

    public void reset() {
        lastTimeUpdateNs.set(0);
        lastWorldTime.set(-1);
        smoothedTps = 20.0f;
        lastKeepAliveId.set(-1);
        lostPacketCount.set(0);
        totalKeepAliveCount.set(0);
        packetLossPercent = 0f;
        peakRttMs.set(0);
        sessionPacketsReceived.set(0);
        graphIndex = 0;
        graphCount = 0;
        currentQuality = Quality.GOOD;
    }
}
