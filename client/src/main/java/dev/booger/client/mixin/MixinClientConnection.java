package dev.booger.client.mixin;

import dev.booger.client.BoogerClient;
import dev.booger.client.core.event.events.Events;
import dev.booger.client.network.LatencyTracker;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.PacketCallbacks;
import net.minecraft.network.listener.PacketListener;
import net.minecraft.network.packet.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ClientConnection mixin — packet interception layer.
 *
 * CRITICAL ARCHITECTURE NOTE:
 * Minecraft's networking runs on Netty — a dedicated IO thread pool separate
 * from the main game thread. Packets are received on Netty threads and then
 * dispatched to the main thread via the tick queue.
 *
 * We intercept at TWO levels:
 *
 * 1. RECEIVE: In channelRead0() — this fires on the NETTY IO thread immediately
 *    when bytes arrive. This is the earliest possible point to timestamp a packet.
 *    We record System.nanoTime() here for RTT calculation.
 *
 *    WARNING: Do NOT call any Minecraft game state from the Netty thread.
 *    Only thread-safe operations (atomic writes, event bus post to concurrent queue).
 *
 * 2. SEND: In send() — fires on the main thread before the packet is written
 *    to the Netty pipeline. We record the send timestamp here.
 *
 * For RTT measurement:
 *    Send timestamp stored in LatencyTracker when packet is sent.
 *    Receive timestamp compared against send timestamp on corresponding response.
 *    We correlate via packet type pairs (e.g. C2S_KEEP_ALIVE → S2C_KEEP_ALIVE).
 *
 * This gives us TRUE round-trip time, not server-reported ping.
 * Server-reported ping can be manipulated or rounded. Ours is hardware-accurate.
 */
@Mixin(ClientConnection.class)
public class MixinClientConnection {

    /**
     * Intercept incoming packets — fires on NETTY IO THREAD.
     * We only do thread-safe work here: record timestamp, post to concurrent event queue.
     */
    @Inject(
        method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/packet/Packet;)V",
        at = @At("HEAD")
    )
    private void onPacketReceive(ChannelHandlerContext ctx, Packet<?> packet, CallbackInfo ci) {
        if (BoogerClient.getInstance() == null) return;

        long receiveTimeNs = System.nanoTime();

        try {
            // Notify LatencyTracker — thread-safe operation
            BoogerClient.latency().onPacketReceived(packet, receiveTimeNs);

            // Post to event bus — EventBus.post() is thread-safe (CopyOnWriteArrayList read)
            // Listeners should not call Minecraft game state from this callback
            BoogerClient.getInstance().getCore().getEventBus()
                .post(new Events.PacketReceiveEvent(packet, receiveTimeNs));
        } catch (Exception e) {
            BoogerClient.LOGGER.error("PacketReceive hook threw exception for {}",
                packet.getClass().getSimpleName(), e);
        }
    }

    /**
     * Intercept outgoing packets — fires on MAIN THREAD (usually).
     * Safe to access game state here.
     */
    @Inject(
        method = "send(Lnet/minecraft/network/packet/Packet;Lnet/minecraft/network/PacketCallbacks;)V",
        at = @At("HEAD")
    )
    private void onPacketSend(Packet<?> packet, PacketCallbacks callbacks, CallbackInfo ci) {
        if (BoogerClient.getInstance() == null) return;

        try {
            BoogerClient.latency().onPacketSent(packet, System.nanoTime());

            var sendEvent = new Events.PacketSendEvent(packet);
            BoogerClient.getInstance().getCore().getEventBus().post(sendEvent);

            // NOTE: Even if sendEvent is cancelled, we don't cancel the actual send here.
            // True packet cancellation requires modifying the CI + return value handling.
            // We'll add that in Phase 4 for modules that legitimately need it.
        } catch (Exception e) {
            BoogerClient.LOGGER.error("PacketSend hook threw exception", e);
        }
    }
}
