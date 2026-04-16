package dev.booger.client.mixin;

import dev.booger.client.BoogerClient;
import dev.booger.client.core.event.events.Events;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * MinecraftClient mixin — game loop and tick event firing.
 *
 * tick() in MinecraftClient is the 20Hz game logic tick.
 * We fire our TickEvent here so all modules get consistent 20Hz updates.
 *
 * We also hook:
 * - onInitFinished: start FramePacingThread after all subsystems are ready
 * - disconnect: clean up per-session state (LatencyTracker history, combo counters)
 */
@Mixin(MinecraftClient.class)
public class MixinMinecraftClient {

    private int boogerTickCount = 0;

    /**
     * Fire TickEvent at the start of each game tick.
     * Injection at HEAD of tick() means our modules tick before vanilla logic.
     * This is usually preferable — our state is fresh when vanilla reads it.
     */
    @Inject(
        method = "tick",
        at = @At("HEAD")
    )
    private void onTickHead(CallbackInfo ci) {
        if (BoogerClient.getInstance() == null) return;
        if (!BoogerClient.getInstance().getCore().isHealthy()) return;

        try {
            BoogerClient.getInstance().getCore().getEventBus()
                .post(new Events.TickEvent(boogerTickCount++));
        } catch (Exception e) {
            BoogerClient.LOGGER.error("TickEvent dispatch threw exception", e);
        }
    }

    /**
     * Clean up session state when disconnecting from a server.
     * Fires on: disconnect button, server kick, connection timeout.
     */
    @Inject(
        method = "disconnect(Lnet/minecraft/client/gui/screen/Screen;Z)V",
        at = @At("HEAD")
    )
    private void onDisconnect(net.minecraft.client.gui.screen.Screen screen,
                               boolean transferring, CallbackInfo ci) {
        if (BoogerClient.getInstance() == null) return;

        try {
            // Reset per-session state
            BoogerClient.latency().reset();

            // Reset combo counter — don't carry combos across sessions
            BoogerClient.modules().getModule(
                dev.booger.client.module.impl.pvp.ComboCounterModule.class
            ).ifPresent(m -> m.onDamageTaken()); // resets combo to 0

            BoogerClient.LOGGER.debug("Booger Client: session state reset on disconnect");
        } catch (Exception e) {
            BoogerClient.LOGGER.error("Disconnect hook threw exception", e);
        }
    }
}
