package dev.booger.client.module.impl.pvp;

import dev.booger.client.BoogerClient;
import dev.booger.client.module.BoogerModule;
import dev.booger.client.module.ModuleCategory;
import dev.booger.client.module.Setting;
import dev.booger.client.render.BoogerFontRenderer;
import net.minecraft.client.gui.DrawContext;

/**
 * Hit Color module.
 *
 * Vanilla Minecraft shows a red damage overlay when YOU are hit.
 * But for the target you're hitting, feedback is minimal — just a brief
 * red tint on the entity model that lasts a few frames.
 *
 * This module lets players configure:
 * 1. Their OWN hit flash color (default: red → could be white, yellow, custom)
 * 2. Hit sound emphasis (screen shake intensity)
 * 3. Hit particle color tint (via world render mixin)
 *
 * WHAT WE DON'T DO:
 * We do NOT alter entity hit registration, reach, or timing.
 * This is purely visual feedback enhancement — making visible what's already
 * happening in the game, more clearly.
 *
 * IMPLEMENTATION NOTE:
 * The actual color application happens in MixinGameRenderer (vignette pass)
 * and MixinWorldRenderer (entity tint). This module stores the configuration
 * and current state that those mixins read.
 *
 * Hit detection is via the EntityDamageEvent which we subscribe to in the mixin
 * that hooks LivingEntity.damage() — we track when entities near the player are
 * damaged so we know when to trigger the visual effect.
 */
public final class HitColorModule extends BoogerModule {

    private final Setting<Integer> selfHitColor = addSetting(
        Setting.color("selfHitColor", "Self Hit Color", 0xAAFF0000) // red, 67% alpha
    );
    private final Setting<Integer> targetHitColor = addSetting(
        Setting.color("targetHitColor", "Target Hit Color", 0x66FFFFFF) // white flash
    );
    private final Setting<Float> flashDuration = addSetting(
        Setting.floatSetting("flashDuration", "Flash Duration (ms)", 150f, 50f, 500f)
    );
    private final Setting<Boolean> screenShake = addSetting(
        Setting.bool("screenShake", "Hit Shake", "Subtle camera shake when landing hits", false)
    );
    private final Setting<Float> shakeIntensity = addSetting(
        Setting.floatSetting("shakeIntensity", "Shake Intensity", 0.3f, 0.1f, 1.0f)
    );

    // State read by mixins
    private volatile long selfHitTimestamp = 0;
    private volatile long targetHitTimestamp = 0;

    public HitColorModule() {
        super("HitColor", "Visual feedback enhancement on hits", ModuleCategory.PVP);
        setEnabled(true);
    }

    /**
     * Called by MixinLivingEntity when the local player is damaged.
     * Thread: main tick thread.
     */
    public void onSelfHit() {
        selfHitTimestamp = System.currentTimeMillis();
    }

    /**
     * Called by MixinLivingEntity when a nearby entity is damaged by the local player.
     */
    public void onTargetHit() {
        targetHitTimestamp = System.currentTimeMillis();
    }

    /**
     * Get the current self-hit overlay color with alpha scaled by remaining flash time.
     * Returns 0 (transparent) if no active flash.
     * Called by MixinGameRenderer every frame.
     */
    public int getSelfHitOverlayColor() {
        long elapsed = System.currentTimeMillis() - selfHitTimestamp;
        float duration = flashDuration.getValue();
        if (elapsed >= duration) return 0;

        float progress = elapsed / duration; // 0.0 = just hit, 1.0 = done
        int baseColor = selfHitColor.getValue();
        return BoogerFontRenderer.scaleAlpha(baseColor, 1.0f - progress);
    }

    /**
     * Get the current target-hit tint.
     * Called by MixinWorldRenderer when rendering entities.
     */
    public int getTargetHitTintColor() {
        long elapsed = System.currentTimeMillis() - targetHitTimestamp;
        float duration = flashDuration.getValue();
        if (elapsed >= duration) return 0;

        float progress = elapsed / duration;
        int baseColor = targetHitColor.getValue();
        return BoogerFontRenderer.scaleAlpha(baseColor, 1.0f - progress);
    }

    /**
     * Current screen shake offset — read by camera mixin.
     * Returns 0 when shake is disabled or expired.
     */
    public float getShakeOffset() {
        if (!screenShake.getValue()) return 0f;
        long elapsed = System.currentTimeMillis() - targetHitTimestamp;
        float duration = flashDuration.getValue();
        if (elapsed >= duration) return 0f;

        float progress = elapsed / duration;
        float decay = 1.0f - progress;
        // Oscillate with decaying amplitude
        return (float)(Math.sin(elapsed * 0.05) * shakeIntensity.getValue() * decay);
    }

    @Override
    public void onRender(DrawContext context, float partialTick, int screenW, int screenH) {
        // This module has no direct HUD element.
        // Its state is read by mixins in the render pipeline.
    }
}
