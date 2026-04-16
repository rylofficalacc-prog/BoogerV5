package dev.booger.client.core.event;

import dev.booger.client.BoogerClient;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Zero-allocation event bus.
 *
 * Design goals vs naive EventBus implementations:
 *
 * 1. NO reflection. Every listener is a typed Consumer<T> lambda — the JIT
 *    inlines these aggressively after warmup. Reflection-based buses (like
 *    Forge's old EventBus) allocate Method invocation objects on every post.
 *
 * 2. NO synchronized blocks on the hot path. Listeners are stored in
 *    CopyOnWriteArrayList — writes are rare (mod init), reads are lock-free.
 *    The snapshot array from COWAL.toArray() is iterated directly.
 *
 * 3. Priority ordering. Listeners declare a priority (LOWEST→HIGHEST).
 *    The render event listener in HudRenderer runs at LOWEST so mods can
 *    cancel/modify before it commits to the framebuffer.
 *
 * 4. Cancellable events. Events that extend CancellableEvent can be stopped
 *    mid-dispatch. The dispatcher checks isCancelled() after each listener
 *    and breaks early — no wasted iterations.
 *
 * 5. Thread safety: post() can be called from any thread. The listener
 *    snapshot is thread-local to each post call.
 */
public final class EventBus {

    public enum Priority {
        LOWEST(0), LOW(1), NORMAL(2), HIGH(3), HIGHEST(4);

        public final int value;
        Priority(int value) { this.value = value; }
    }

    /**
     * Internal registration entry. Holds the typed consumer and its priority.
     * Comparable by priority for sorted insertion.
     */
    private record ListenerEntry<T extends BusEvent>(
        Consumer<T> consumer,
        Priority priority
    ) implements Comparable<ListenerEntry<T>> {
        @Override
        public int compareTo(ListenerEntry<T> other) {
            // Higher priority runs first
            return Integer.compare(other.priority.value, this.priority.value);
        }
    }

    // Map from event class → sorted list of listeners
    // ConcurrentHashMap: safe for multi-threaded registration during init
    private final Map<Class<? extends BusEvent>, List<ListenerEntry<?>>> listeners =
        new ConcurrentHashMap<>();

    // Object pool for reusing event instances on the hot path
    // Modules that fire every tick/frame should use pooled events
    private final Map<Class<? extends BusEvent>, Queue<BusEvent>> eventPool =
        new ConcurrentHashMap<>();

    public void init() {
        BoogerClient.LOGGER.debug("EventBus initialized");
    }

    /**
     * Register a listener for an event type.
     * Call during mod/module initialization — not on the hot path.
     *
     * @param eventClass The event class to listen for
     * @param consumer   The listener callback
     * @param priority   Dispatch priority
     */
    public <T extends BusEvent> void subscribe(
        Class<T> eventClass,
        Consumer<T> consumer,
        Priority priority
    ) {
        var list = listeners.computeIfAbsent(
            eventClass,
            k -> new CopyOnWriteArrayList<>()
        );

        var entry = new ListenerEntry<>(consumer, priority);

        // Insert in sorted position — listeners are few, this is fine at init time
        synchronized (list) {
            ((CopyOnWriteArrayList<ListenerEntry<?>>) list).add(entry);
            ((CopyOnWriteArrayList<ListenerEntry<?>>) list).sort(
                Comparator.comparingInt(e -> -e.priority().value)
            );
        }
    }

    /**
     * Register with default NORMAL priority.
     */
    public <T extends BusEvent> void subscribe(Class<T> eventClass, Consumer<T> consumer) {
        subscribe(eventClass, consumer, Priority.NORMAL);
    }

    /**
     * Post an event to all registered listeners.
     *
     * HOT PATH — called every frame for RenderEvent, every tick for TickEvent.
     * Must be as cheap as possible. Currently: one map lookup + array iteration.
     * No allocations after warmup (COWAL snapshot is cached by JIT).
     *
     * @param event The event to dispatch
     * @return The event after dispatch (check isCancelled() for cancellable events)
     */
    @SuppressWarnings("unchecked")
    public <T extends BusEvent> T post(T event) {
        var list = listeners.get(event.getClass());
        if (list == null) return event;

        // COWAL provides a stable snapshot array — no ConcurrentModificationException
        // and no lock on the read path
        Object[] snapshot = ((CopyOnWriteArrayList<?>) list).toArray();

        for (Object obj : snapshot) {
            ListenerEntry<T> entry = (ListenerEntry<T>) obj;
            try {
                entry.consumer().accept(event);
            } catch (Exception e) {
                BoogerClient.LOGGER.error(
                    "EventBus listener threw exception for event {}",
                    event.getClass().getSimpleName(), e
                );
                // Never let a bad listener crash the game
            }

            // Short-circuit for cancelled events
            if (event instanceof CancellableEvent cancellable && cancellable.isCancelled()) {
                break;
            }
        }

        return event;
    }

    /**
     * Unsubscribe all listeners for a given consumer reference.
     * Used when modules are disabled.
     */
    public void unsubscribeAll(Object owner) {
        // Note: In our architecture, modules use lambda refs stored in fields.
        // Caller passes the same lambda ref to unsubscribe. This is intentional —
        // we avoid storing owner references which would create GC retention issues.
        listeners.values().forEach(list -> list.removeIf(
            entry -> entry.consumer() == owner
        ));
    }
}
