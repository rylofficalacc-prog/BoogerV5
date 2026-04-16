package dev.booger.client.core.event;

/**
 * Base class for all Booger Client events.
 *
 * Intentionally minimal — no extra fields, no timestamp, no source tracking.
 * Subclasses add only what they need. Keep events as value-like as possible
 * so the JIT can scalar-replace them and avoid heap allocation on the hot path.
 *
 * Java 21 record classes are ideal for events — use them for all event types
 * that don't need mutability (e.g., TickEvent, PacketReceiveEvent).
 * Use regular classes only for events that need mutable fields (e.g., RenderEvent
 * where we want to allow listeners to inject draw calls).
 */
public abstract class BusEvent {
    // Marker class — intentionally empty
    // The JIT sees this as a sealed hierarchy after warmup and optimizes dispatch
}
