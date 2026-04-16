package dev.booger.client.module;

/**
 * Marker interface for modules that implement onTick().
 * ModuleManager uses this to build the tick dispatch list without reflection.
 */
public interface TickableModule {}
