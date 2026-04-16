package dev.booger.client.security;

import dev.booger.client.BoogerClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.effect.StatusEffects;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Movement speed auditor — wired from MixinClientPlayerEntityCoords.
 *
 * This class implements the real velocity audit that was stubbed in Phase 5.
 * It's separate from SafeModeSandbox to keep concerns split and to allow
 * unit testing without a full Minecraft environment.
 *
 * WHAT WE AUDIT:
 * Player horizontal speed (blocks/sec) vs the maximum LEGITIMATE speed
 * for the current game state.
 *
 * LEGITIMATE SPEED SOURCES:
 * - Walk:      ~4.3 b/s
 * - Sprint:    ~5.6 b/s
 * - Sprint+Jump: ~5.6 b/s (same — jump doesn't increase horizontal speed in 1.21)
 * - Speed I:   ~6.5 b/s
 * - Speed II:  ~7.8 b/s
 * - Speed III: ~9.1 b/s (commands only — survival cap is II)
 * - Elytra:    up to ~35 b/s with rockets, theoretically unlimited with fireworks
 * - Ice+sprint: ~25 b/s (slippery surface momentum)
 * - Riptide:   burst ~15 b/s
 * - Dolphin's Grace: increases max swim speed
 * - Boat on ice: ~40+ b/s (legitimate high-speed travel)
 *
 * TOLERANCE:
 * We use a generous 25% tolerance above the computed maximum to avoid
 * false positives from:
 * - Network jitter (packets arrive in bursts)
 * - Server knockback (server applies velocity we didn't predict)
 * - Lag compensation artifacts
 * - Our own smoothing math imprecision
 *
 * CONSECUTIVE VIOLATION THRESHOLD:
 * A single over-speed reading is NOT flagged as an anomaly.
 * We require 3 consecutive readings above the threshold before flagging.
 * This prevents false positives from momentary server knockback.
 *
 * ELYTRA DETECTION:
 * We check player equipment to detect elytra use and apply
 * the much higher speed ceiling when in flight.
 */
public final class SafeModeAuditor {

    // Base movement speeds in blocks/second
    private static final double WALK_SPEED_BPS   = 4.3;
    private static final double SPRINT_SPEED_BPS = 5.6;
    private static final double SPEED_PER_LEVEL  = 1.3; // b/s per speed level
    private static final double ELYTRA_MAX_BPS   = 80.0; // Very generous — rockets can be fast
    private static final double ICE_SPRINT_BPS   = 28.0;
    private static final double BOAT_ICE_BPS     = 50.0; // Boats on blue ice

    // Tolerance multiplier — 25% above computed max before flagging
    private static final double TOLERANCE = 1.25;

    // Consecutive violations required before flagging
    private static final int VIOLATION_THRESHOLD = 3;

    private final SafeModeSandbox sandbox;

    // Consecutive violation counter
    private final AtomicInteger consecutiveViolations = new AtomicInteger(0);
    private long lastAuditMs = 0;

    public SafeModeAuditor(SafeModeSandbox sandbox) {
        this.sandbox = sandbox;
    }

    /**
     * Called from MixinClientPlayerEntityCoords.onMovementPacketSent().
     * Audits the measured player speed against the expected maximum.
     *
     * @param measuredSpeedBps  Actual measured speed in blocks/second
     * @param x, y, z           Current player position (for context in anomaly log)
     */
    public void auditSpeed(double measuredSpeedBps, double x, double y, double z) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null) return;

        long now = System.currentTimeMillis();
        if (now - lastAuditMs < 50) return; // Rate limit: max 20 audits/sec
        lastAuditMs = now;

        double maxLegitimate = computeMaxLegitimateSpeed(mc);

        if (measuredSpeedBps > maxLegitimate * TOLERANCE) {
            int violations = consecutiveViolations.incrementAndGet();

            if (violations >= VIOLATION_THRESHOLD) {
                consecutiveViolations.set(0); // Reset after flagging

                double excess = measuredSpeedBps - maxLegitimate;
                String description = String.format(
                    "Speed anomaly: measured %.1f b/s, max legitimate %.1f b/s " +
                    "(excess: %.1f b/s) at pos (%.1f, %.1f, %.1f)",
                    measuredSpeedBps, maxLegitimate, excess, x, y, z
                );

                SafeModeSandbox.Severity severity =
                    measuredSpeedBps > maxLegitimate * 2.0
                        ? SafeModeSandbox.Severity.CRITICAL
                        : SafeModeSandbox.Severity.WARNING;

                sandbox.recordAnomalyPublic("SpeedAuditor", description, severity);
            }
        } else {
            // Under limit — reset consecutive counter
            consecutiveViolations.set(Math.max(0, consecutiveViolations.get() - 1));
        }
    }

    private double computeMaxLegitimateSpeed(MinecraftClient mc) {
        var player = mc.player;

        // Elytra check — must be first, overrides everything
        boolean hasElytra = player.isFallFlying(); // True when elytra is deployed
        if (hasElytra) return ELYTRA_MAX_BPS;

        // Boat check
        if (player.hasVehicle()) {
            // Boats can go very fast on ice
            return BOAT_ICE_BPS;
        }

        // Base speed
        double base = player.isSprinting() ? SPRINT_SPEED_BPS : WALK_SPEED_BPS;

        // Speed effect
        if (player.hasStatusEffect(StatusEffects.SPEED)) {
            int amplifier = player.getStatusEffect(StatusEffects.SPEED).getAmplifier();
            base += SPEED_PER_LEVEL * (amplifier + 1);
        }

        // Dolphin's Grace (swimming)
        if (player.hasStatusEffect(StatusEffects.DOLPHINS_GRACE)) {
            base = Math.max(base, 8.0);
        }

        // Riptide trident burst
        if (player.isUsingRiptide()) {
            base = Math.max(base, 18.0);
        }

        // Ice surface — slippery terrain allows momentum buildup
        // We check this heuristically via the "on ice" flag
        // (In 1.21, player.isOnIce() is accessible via the block below)
        if (isOnSlipperyBlock(mc)) {
            base = Math.max(base, ICE_SPRINT_BPS);
        }

        return base;
    }

    private boolean isOnSlipperyBlock(MinecraftClient mc) {
        if (mc.world == null || mc.player == null) return false;
        // Get block below player
        var blockPos = mc.player.getBlockPos().down();
        var block = mc.world.getBlockState(blockPos).getBlock();
        // Ice, packed ice, blue ice all have slipperiness > 0.6
        // In 1.21: Block.getSlipperiness() > 0.6 indicates slippery
        return block.getSlipperiness() > 0.6f;
    }
}
