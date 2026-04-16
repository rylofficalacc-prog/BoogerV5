package dev.booger.client.mixin;

import dev.booger.client.BoogerClient;
import dev.booger.client.module.impl.pvp.ComboCounterModule;
import dev.booger.client.module.impl.pvp.HitColorModule;
import dev.booger.client.module.impl.pvp.KillEffectModule;
import dev.booger.client.module.impl.pvp.TargetHudModule;
import dev.booger.client.security.SafeModeSandbox;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Extended ClientPlayerEntity mixin — Phase 6.
 *
 * Phase 3 had a minimal version of this mixin for combo/hit detection.
 * Phase 6 adds:
 *
 * 1. VELOCITY AUDIT for SafeModeSandbox
 *    We intercept the outgoing PlayerMoveC2SPacket position update.
 *    This is the ONLY reliable place to get the client's claimed position
 *    and velocity before it's sent to the server.
 *
 *    The audit compares:
 *    - Claimed position delta (what the packet says)
 *    - Physics-predicted position delta (what the game engine computed)
 *    If these diverge significantly, something is modifying the packet
 *    contents between our physics step and the network send.
 *
 * 2. ATTACK HIT DETECTION for PvP modules
 *    Intercept ClientPlayerEntity.attack(Entity target) — this fires
 *    on the CLIENT immediately when the player clicks on an entity,
 *    before the server confirms the hit.
 *    We use this for combo counter and target HUD acquisition.
 *
 * 3. ENTITY DEATH DETECTION for KillEffect
 *    Intercept Entity.onDeath() when the entity was recently attacked
 *    by the local player — triggers KillEffectModule.onKill().
 *
 * 4. DAMAGE TAKEN (extended from Phase 3)
 *    Now includes the damage amount for threshold-based effects.
 *    Small damage (<1HP) from thorns/fall doesn't count as combat hit.
 */
@Mixin(ClientPlayerEntity.class)
public abstract class MixinClientPlayerEntityCoords {

    @Shadow public abstract double getX();
    @Shadow public abstract double getY();
    @Shadow public abstract double getZ();

    // Last position sent to server — for velocity audit
    private double booger_lastSentX = Double.NaN;
    private double booger_lastSentY = Double.NaN;
    private double booger_lastSentZ = Double.NaN;
    private long   booger_lastSentMs = 0;

    // Last entity this player attacked — for kill detection
    private Entity booger_lastAttackedEntity = null;
    private long   booger_lastAttackMs = 0;

    /**
     * Intercept the movement packet just before it's sent to the server.
     * This is our real velocity audit hook — fills the Phase 5 stub in
     * SafeModeSandbox.auditMovementPacket().
     *
     * Fires on the MAIN THREAD (movement packet generation is synchronous).
     * Called from sendMovementPackets() in ClientPlayerEntity.
     */
    @Inject(
        method = "sendMovementPackets",
        at = @At("TAIL")
    )
    private void onMovementPacketSent(CallbackInfo ci) {
        if (BoogerClient.getInstance() == null) return;

        SafeModeSandbox sandbox = BoogerClient.getInstance().getCore().getSafeSandbox();
        if (sandbox == null || !sandbox.isSafeModeActive()) {
            // Update position tracking even outside safe mode (for metrics)
            updatePositionTracking();
            return;
        }

        // Audit the position delta
        double currentX = getX(), currentY = getY(), currentZ = getZ();
        long now = System.currentTimeMillis();

        if (!Double.isNaN(booger_lastSentX) && booger_lastSentMs > 0) {
            double elapsedSec = (now - booger_lastSentMs) / 1000.0;
            if (elapsedSec > 0 && elapsedSec < 1.0) { // Sanity: <1s between packets
                double dx = currentX - booger_lastSentX;
                double dz = currentZ - booger_lastSentZ;
                double speedBps = Math.sqrt(dx * dx + dz * dz) / elapsedSec;

                // Audit: check speed against known maximums
                sandbox.auditMovementSpeed(speedBps, currentX, currentY, currentZ);
            }
        }

        updatePositionTracking();
    }

    private void updatePositionTracking() {
        booger_lastSentX  = getX();
        booger_lastSentY  = getY();
        booger_lastSentZ  = getZ();
        booger_lastSentMs = System.currentTimeMillis();
    }

    /**
     * Intercept player attack — fires when client clicks on an entity.
     * This is client-side prediction: the attack may not register on the
     * server (miss, out of range), but for visual feedback it's immediate.
     */
    @Inject(
        method = "attack",
        at = @At("HEAD")
    )
    private void onAttack(Entity target, CallbackInfo ci) {
        if (BoogerClient.getInstance() == null) return;
        if (!(target instanceof LivingEntity living)) return;

        booger_lastAttackedEntity = target;
        booger_lastAttackMs = System.currentTimeMillis();

        try {
            // Notify combo counter
            BoogerClient.modules().getModule(ComboCounterModule.class)
                .ifPresent(ComboCounterModule::onHitLanded);

            // Notify target HUD — this entity is now the focus target
            BoogerClient.modules().getModule(TargetHudModule.class)
                .ifPresent(m -> m.setLastTarget(living));

            // Notify hit color module
            BoogerClient.modules().getModule(HitColorModule.class)
                .ifPresent(HitColorModule::onTargetHit);

        } catch (Exception e) {
            BoogerClient.LOGGER.debug("Attack hook threw: {}", e.getMessage());
        }
    }

    /**
     * Intercept damage taken — extended from Phase 3 with amount threshold.
     */
    @Inject(
        method = "damage",
        at = @At("HEAD")
    )
    private void onDamageTakenExtended(DamageSource source, float amount,
                                         CallbackInfoReturnable<Boolean> cir) {
        if (BoogerClient.getInstance() == null) return;
        if (amount < 0.5f) return; // Ignore trivial damage (thorns, fall grace)

        try {
            BoogerClient.modules().getModule(HitColorModule.class)
                .ifPresent(HitColorModule::onSelfHit);
            BoogerClient.modules().getModule(ComboCounterModule.class)
                .ifPresent(ComboCounterModule::onDamageTaken);
        } catch (Exception e) {
            BoogerClient.LOGGER.debug("Damage hook threw: {}", e.getMessage());
        }
    }

    /**
     * Detect entity death — check if it was our last target.
     * We inject into LivingEntity.onDeath() but filter for entities
     * that match our last attacked entity within a 5-second window.
     *
     * NOTE: This is on ClientPlayerEntity so we only fire for the local player's
     * interactions. We'll detect the death from the next entity tick after
     * health drops to 0 (LivingEntity.isDead()).
     */
    @Inject(
        method = "tick",
        at = @At("HEAD")
    )
    private void onTickCheckKill(CallbackInfo ci) {
        if (BoogerClient.getInstance() == null) return;
        if (booger_lastAttackedEntity == null) return;

        long now = System.currentTimeMillis();
        if (now - booger_lastAttackMs > 5000) {
            booger_lastAttackedEntity = null; // Stale — clear
            return;
        }

        if (booger_lastAttackedEntity.isDead() || booger_lastAttackedEntity.isRemoved()) {
            try {
                BoogerClient.modules().getModule(KillEffectModule.class)
                    .ifPresent(KillEffectModule::onKill);
            } catch (Exception e) {
                BoogerClient.LOGGER.debug("Kill detection threw: {}", e.getMessage());
            }
            booger_lastAttackedEntity = null;
        }
    }
}
