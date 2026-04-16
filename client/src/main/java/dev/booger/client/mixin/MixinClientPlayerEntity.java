package dev.booger.client.mixin;

import dev.booger.client.BoogerClient;
import dev.booger.client.module.impl.pvp.ComboCounterModule;
import dev.booger.client.module.impl.pvp.HitColorModule;
import dev.booger.client.module.impl.pvp.KillEffectModule;
import dev.booger.client.module.impl.pvp.TargetHudModule;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ClientPlayerEntity mixin — PvP event detection.
 *
 * We hook into the client-side damage application to detect:
 * 1. When the LOCAL PLAYER takes damage → reset combo, flash self-hit overlay
 * 2. When the LOCAL PLAYER attacks an entity → increment combo
 * 3. When an entity dies after the local player attacked it → kill effect
 *
 * IMPORTANT LIMITATION:
 * Client-side damage events fire based on what the CLIENT knows. Due to server
 * authoritative gameplay, the client may receive damage notifications slightly
 * late. For visual feedback purposes this is fine (±1-2 ticks delay is
 * imperceptible). For gameplay logic, always trust server packets.
 *
 * We hook damage() on LivingEntity for entities near the player (detected via
 * client-side prediction), and hook the player's own damage reception.
 */
@Mixin(ClientPlayerEntity.class)
public class MixinClientPlayerEntity {

    /**
     * Detect when the local player takes damage.
     * Vanilla calls this during the S2C_DAMAGE_EVENT packet processing.
     */
    @Inject(
        method = "damage",
        at = @At("HEAD")
    )
    private void onDamageReceived(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        if (BoogerClient.getInstance() == null) return;
        if (amount <= 0) return; // Ignore zero-damage events

        try {
            // Notify HitColor module to flash the self-hit overlay
            BoogerClient.modules().getModule(HitColorModule.class)
                .ifPresent(HitColorModule::onSelfHit);

            // Reset combo — taking damage breaks combo
            BoogerClient.modules().getModule(ComboCounterModule.class)
                .ifPresent(ComboCounterModule::onDamageTaken);

        } catch (Exception e) {
            BoogerClient.LOGGER.error("Damage receive hook threw", e);
        }
    }
}
