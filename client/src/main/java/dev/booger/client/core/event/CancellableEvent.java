package dev.booger.client.core.event;

/**
 * Base class for events that can be cancelled by a listener.
 *
 * When cancelled, EventBus stops dispatching to remaining listeners.
 * Use for: packet events (cancel a packet send), input events (suppress key press),
 * render events (suppress a draw call).
 *
 * NOT for: tick events, world load events — cancelling those would break game state.
 */
public abstract class CancellableEvent extends BusEvent {

    private boolean cancelled = false;

    public void cancel() {
        this.cancelled = true;
    }

    public boolean isCancelled() {
        return cancelled;
    }
}
