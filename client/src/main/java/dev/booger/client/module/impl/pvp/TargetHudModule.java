package dev.booger.client.module.impl.pvp;

import dev.booger.client.BoogerClient;
import dev.booger.client.module.BoogerModule;
import dev.booger.client.module.ModuleCategory;
import dev.booger.client.module.Setting;
import dev.booger.client.render.BoogerFontRenderer;
import dev.booger.client.render.theme.ThemeEngine;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;

/**
 * Target HUD module.
 *
 * Displays detailed information about the entity the player is currently
 * looking at or last attacked:
 * - Entity name
 * - Health bar (current/max)
 * - Armor value
 * - Active effects
 * - Distance
 *
 * Target acquisition:
 * 1. PRIMARY: Player's current crosshair target (via mc.targetedEntity)
 * 2. FALLBACK: Last entity the player attacked (set by combat mixin)
 *    — persists for 3 seconds after losing line of sight
 *
 * This is identical to how Lunar's Target HUD works but our health bar
 * is smoother (interpolated) and the layout is more information-dense.
 */
public final class TargetHudModule extends BoogerModule {

    private final Setting<Float> panelWidth = addSetting(
        Setting.floatSetting("panelWidth", "Panel Width", 140f, 80f, 220f)
    );
    private final Setting<Boolean> showEffects = addSetting(
        Setting.bool("showEffects", "Show Effects", true)
    );
    private final Setting<Boolean> showDistance = addSetting(
        Setting.bool("showDistance", "Show Distance", true)
    );
    private final Setting<Boolean> showArmor = addSetting(
        Setting.bool("showArmor", "Show Armor", true)
    );

    // Last attacked entity — used as fallback target
    private LivingEntity lastTarget = null;
    private long lastTargetMs = 0;
    private static final long TARGET_PERSIST_MS = 3000;

    // Smoothed health for animation
    private float smoothedHealth = 0f;
    private float smoothedMaxHealth = 20f;
    private static final float HEALTH_SMOOTH = 0.85f;

    public TargetHudModule() {
        super("TargetHUD", "Targeted entity information panel", ModuleCategory.PVP);
        setEnabled(true);
        setHudPosition(10, 60);
    }

    /** Called by mixin when local player attacks an entity. */
    public void setLastTarget(LivingEntity entity) {
        this.lastTarget = entity;
        this.lastTargetMs = System.currentTimeMillis();
    }

    @Override
    public void onRender(DrawContext context, float partialTick, int screenW, int screenH) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        BoogerFontRenderer font = BoogerClient.hud().getFontRenderer();
        var theme = ThemeEngine.current();

        // Acquire target
        LivingEntity target = null;
        if (mc.targetedEntity instanceof LivingEntity living) {
            target = living;
        } else if (lastTarget != null && !lastTarget.isDead()
                && System.currentTimeMillis() - lastTargetMs < TARGET_PERSIST_MS) {
            target = lastTarget;
        }

        if (target == null) {
            smoothedHealth = 0f;
            return;
        }

        float maxHp = (float) target.getAttributeValue(EntityAttributes.MAX_HEALTH);
        float hp = target.getHealth();

        // Smooth health animation
        smoothedMaxHealth = maxHp;
        smoothedHealth = smoothedHealth * HEALTH_SMOOTH + hp * (1f - HEALTH_SMOOTH);
        if (Math.abs(smoothedHealth - hp) < 0.01f) smoothedHealth = hp;

        float w = panelWidth.getValue();
        float h = buildPanelHeight(target, font);
        float pad = theme.elementPadding();

        // Background panel
        font.drawBox(context, -pad, -pad, w + pad * 2, h + pad * 2, theme.bgPrimary());
        font.drawBorder(context, -pad, -pad, w + pad * 2, h + pad * 2, theme.bgBorder());

        float fh = font.getFontHeight();
        float y = 0;

        // Entity name + distance
        String name = target.getDisplayName().getString();
        if (name.length() > 20) name = name.substring(0, 17) + "...";

        String distStr = "";
        if (showDistance.getValue() && mc.player != null) {
            double dist = mc.player.distanceTo(target);
            distStr = String.format(" %.1fm", dist);
        }

        font.drawText(context, name, 0, y, theme.textPrimary(), theme.shadowText());
        if (!distStr.isEmpty()) {
            font.drawText(context, distStr,
                font.getStringWidth(name), y, theme.textSecondary(), false);
        }
        y += fh + 3;

        // Health bar
        float hpPct = smoothedHealth / smoothedMaxHealth;
        hpPct = Math.max(0f, Math.min(1f, hpPct));
        int hpColor = BoogerFontRenderer.lerpColor(theme.colorDanger(), theme.colorGood(), hpPct);

        String hpStr = String.format("%.1f / %.0f", smoothedHealth, smoothedMaxHealth);
        font.drawText(context, hpStr, 0, y, theme.textSecondary(), false);
        y += fh + 1;

        // Health bar bg + fill
        float barH = 4f;
        font.drawBox(context, 0, y, w, barH, theme.bgSecondary());
        if (hpPct > 0) font.drawBox(context, 0, y, w * hpPct, barH, hpColor);
        font.drawBorder(context, 0, y, w, barH,
            BoogerFontRenderer.withAlpha(theme.bgBorder(), 160));
        y += barH + 4;

        // Armor
        if (showArmor.getValue()) {
            int armor = target.getArmor();
            font.drawText(context, "Armor: " + armor, 0, y, theme.textSecondary(), false);
            y += fh + 2;
        }

        // Active effects
        if (showEffects.getValue()) {
            for (StatusEffectInstance effect : target.getStatusEffects()) {
                String effectName = effect.getEffectType().value().getName().getString();
                int secs = effect.getDuration() / 20;
                String effectStr = effectName + " " + secs + "s";
                boolean beneficial = effect.getEffectType().value().isBeneficial();
                int color = beneficial ? theme.colorGood() : theme.colorDanger();
                font.drawText(context, effectStr, 0, y, color, false);
                y += fh + 1;
            }
        }
    }

    private float buildPanelHeight(LivingEntity target, BoogerFontRenderer font) {
        float fh = font.getFontHeight();
        float h = fh + 3 + fh + 1 + 4 + 4; // name + hp text + bar + gap
        if (showArmor.getValue()) h += fh + 2;
        if (showEffects.getValue()) h += target.getStatusEffects().size() * (fh + 1);
        return h;
    }
}
