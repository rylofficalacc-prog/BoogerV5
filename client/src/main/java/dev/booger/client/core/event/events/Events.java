package dev.booger.client.core.event.events;

import dev.booger.client.core.event.BusEvent;
import dev.booger.client.core.event.CancellableEvent;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.network.packet.Packet;

/**
 * All Booger Client core events, co-located for easy reference.
 *
 * Naming convention:
 * - Events fired BEFORE an action: Pre suffix or no suffix
 * - Events fired AFTER an action: Post suffix
 *
 * Memory notes:
 * - RenderEvent is created once per frame and reused (cleared between frames)
 * - TickEvent is a record — JIT may scalar-replace it
 * - PacketEvents wrap existing packet objects — no extra allocation
 */
public final class Events {

    private Events() {} // Static container only

    // ─── Render Events ───────────────────────────────────────────────────────

    /**
     * Fired each frame during HUD render pass.
     * Listeners draw directly to the provided DrawContext.
     *
     * The partialTick value is CRITICAL for smooth animation:
     * it's the interpolation factor between the last tick and current frame.
     * Always use it for any position/animation calculations. Ignoring it
     * causes stuttering that's visible even at 240fps.
     */
    public static class RenderHudEvent extends BusEvent {
        private DrawContext context;
        private float partialTick;
        private int screenWidth;
        private int screenHeight;

        // Reuse this instance — set() instead of new RenderHudEvent() each frame
        public RenderHudEvent set(DrawContext context, float partialTick, int w, int h) {
            this.context = context;
            this.partialTick = partialTick;
            this.screenWidth = w;
            this.screenHeight = h;
            return this;
        }

        public DrawContext getContext() { return context; }
        public float getPartialTick() { return partialTick; }
        public int getScreenWidth() { return screenWidth; }
        public int getScreenHeight() { return screenHeight; }
    }

    /**
     * Fired before 3D world rendering begins.
     * Use for world-space overlays, ESP-style highlights (legal ones only).
     */
    public static class RenderWorldEvent extends BusEvent {
        public final float partialTick;
        public final long limitTime;

        public RenderWorldEvent(float partialTick, long limitTime) {
            this.partialTick = partialTick;
            this.limitTime = limitTime;
        }
    }

    // ─── Tick Events ─────────────────────────────────────────────────────────

    /**
     * Fired every client tick (20 Hz nominal).
     * Use for per-tick state updates — NOT for rendering.
     * Records are ideal here: immutable, potentially stack-allocated.
     */
    public record ClientTickEvent(int tickCount) implements java.io.Serializable {
        // Note: doesn't extend BusEvent because we want record semantics.
        // EventBus uses a separate map key for this type.
        // TODO: Java 21 sealed interfaces would let us unify this cleanly.
    }

    // Wrapper to make ClientTickEvent work with EventBus
    public static class TickEvent extends BusEvent {
        public final int tickCount;
        public TickEvent(int tickCount) { this.tickCount = tickCount; }
    }

    // ─── Packet Events ───────────────────────────────────────────────────────

    /**
     * Fired when a packet is received from the server.
     * Cancel to suppress the packet (use with extreme care — can desync).
     *
     * The raw packet is provided for inspection. Do NOT hold references
     * to the packet after the event — packets are pooled in Netty.
     */
    public static class PacketReceiveEvent extends CancellableEvent {
        public final Packet<?> packet;
        public final long receiveTimeNanos; // System.nanoTime() at receipt

        public PacketReceiveEvent(Packet<?> packet, long receiveTimeNanos) {
            this.packet = packet;
            this.receiveTimeNanos = receiveTimeNanos;
        }
    }

    /**
     * Fired when a packet is about to be sent to the server.
     * Cancel to suppress sending (use with extreme care).
     */
    public static class PacketSendEvent extends CancellableEvent {
        public final Packet<?> packet;

        public PacketSendEvent(Packet<?> packet) {
            this.packet = packet;
        }
    }

    // ─── Input Events ────────────────────────────────────────────────────────

    /**
     * Fired from the dedicated input polling thread.
     * Timestamp is from the polling thread's own high-res timer —
     * more accurate than a main-thread timestamp.
     *
     * Key is GLFW key code. Action: 0=release, 1=press, 2=repeat.
     */
    public static class KeyInputEvent extends CancellableEvent {
        public final int key;
        public final int action;
        public final int mods;
        public final long timestampNanos;

        public KeyInputEvent(int key, int action, int mods, long timestampNanos) {
            this.key = key;
            this.action = action;
            this.mods = mods;
            this.timestampNanos = timestampNanos;
        }
    }

    /**
     * Fired on mouse click from input polling thread.
     * button: 0=left, 1=right, 2=middle
     */
    public static class MouseClickEvent extends CancellableEvent {
        public final int button;
        public final int action;
        public final double mouseX;
        public final double mouseY;
        public final long timestampNanos;

        public MouseClickEvent(int button, int action, double x, double y, long ts) {
            this.button = button;
            this.action = action;
            this.mouseX = x;
            this.mouseY = y;
            this.timestampNanos = ts;
        }
    }

    /**
     * Raw mouse delta — fired every polling cycle.
     * Use for aim smoothness analysis (TargetHUD module uses this).
     * NOT for changing mouse input — that's a cheat.
     */
    public static class MouseMoveEvent extends BusEvent {
        public final double deltaX;
        public final double deltaY;
        public final long timestampNanos;

        public MouseMoveEvent(double dx, double dy, long ts) {
            this.deltaX = dx;
            this.deltaY = dy;
            this.timestampNanos = ts;
        }
    }
}
