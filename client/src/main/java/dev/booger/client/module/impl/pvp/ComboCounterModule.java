package dev.booger.client.module.impl.pvp;

import dev.booger.client.BoogerClient;
import dev.booger.client.module.BoogerModule;
import dev.booger.client.module.ModuleCategory;
import dev.booger.client.module.Setting;
import dev.booger.client.render.BoogerFontRenderer;
import dev.booger.client.render.theme.ThemeEngine;
import net.minecraft.client.gui.DrawContext;

/**
 * Combo counter module.
 *
 * Tracks consecutive hits on any entity without taking a hit yourself.
 * Combo resets when:
 * - You take damage from any source
 * - More than 3 seconds pass without landing a hit
 * - You die
 *
 * Display:
 * - Current combo number
 * - Animated scale pulse on new hit (grows then shrinks back to normal)
 * - Color shifts from white → yellow → orange → red as combo climbs
 * - Fades out after 3 seconds of no hits
 *
 * Mixins involved:
 * - MixinLivingEntity.damage() → detects hits landed and received
 */
public final class ComboCounterModule extends BoogerModule {

    private final Setting<Boolean> animateScale = addSetting(
        Setting.bool("animateScale", "Animate", "Scale pulse animation on new hit", true)
    );
    private final Setting<Integer> comboTimeout = addSetting(
        Setting.integer("comboTimeout", "Combo Timeout (s)", 3, 1, 10)
    );
    private final Setting<Boolean> showBackground = addSetting(
        Setting.bool("showBackground", "Background", true)
    );

    // State
    private int combo = 0;
    private long lastHitMs = 0;
    private long lastHitAnimMs = 0;
    private static final long ANIM_DURATION_MS = 200;

    public ComboCounterModule() {
        super("ComboCounter", "Hit combo tracker", ModuleCategory.PVP);
        setEnabled(true);
        setHudPosition(200, 100);
    }

    /** Called by mixin when local player lands a hit on an entity. */
    public void onHitLanded() {
        combo++;
        lastHitMs = System.currentTimeMillis();
        lastHitAnimMs = lastHitMs;
    }

    /** Called by mixin when local player takes damage. */
    public void onDamageTaken() {
        combo = 0;
    }

    @Override
    public void onRender(DrawContext context, float partialTick, int screenW, int screenH) {
        BoogerFontRenderer font = BoogerClient.hud().getFontRenderer();
        var theme = ThemeEngine.current();

        long now = System.currentTimeMillis();

        // Reset combo on timeout
        long timeoutMs = (long) comboTimeout.getValue() * 1000;
        if (combo > 0 && now - lastHitMs > timeoutMs) {
            combo = 0;
        }

        if (combo <= 0) return;

        // Fade alpha based on time since last hit
        long elapsed = now - lastHitMs;
        float alpha = elapsed < timeoutMs - 1000 ? 1.0f :
            1.0f - (float)(elapsed - (timeoutMs - 1000)) / 1000f;
        alpha = Math.max(0f, Math.min(1f, alpha));

        // Scale animation on hit
        float scale = 1.0f;
        if (animateScale.getValue()) {
            long animElapsed = now - lastHitAnimMs;
            if (animElapsed < ANIM_DURATION_MS) {
                float t = (float) animElapsed / ANIM_DURATION_MS;
                // Ease out: scale 1.4 → 1.0
                scale = 1.4f - 0.4f * t;
            }
        }

        // Color by combo level
        int baseColor;
        if (combo >= 20)      baseColor = theme.colorDanger();   // red
        else if (combo >= 10) baseColor = 0xFFFF8800;            // orange
        else if (combo >= 5)  baseColor = theme.colorWarning();  // yellow
        else                  baseColor = theme.textValue();     // white

        int color = BoogerFontRenderer.scaleAlpha(baseColor, alpha);

        String comboStr = combo + "x";
        String label = "Combo";

        context.getMatrices().push();
        context.getMatrices().scale(scale, scale, 1f);

        if (showBackground.getValue()) {
            int maxW = Math.max(font.getStringWidth(comboStr), font.getStringWidth(label));
            font.drawBox(context, -theme.elementPadding(), -theme.elementPadding(),
                maxW + theme.elementPadding() * 2, font.getFontHeight() * 2 + 4 + theme.elementPadding() * 2,
                BoogerFontRenderer.scaleAlpha(theme.bgPrimary(), alpha));
        }

        font.drawCenteredText(context, comboStr,
            font.getStringWidth(comboStr) / 2f, 0, color, theme.shadowText());
        int labelColor = BoogerFontRenderer.scaleAlpha(theme.textSecondary(), alpha);
        font.drawCenteredText(context, label,
            font.getStringWidth(comboStr) / 2f, font.getFontHeight() + 1, labelColor, false);

        context.getMatrices().pop();
    }
}
