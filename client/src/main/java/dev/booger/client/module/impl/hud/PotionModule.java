package dev.booger.client.module.impl.hud;

import dev.booger.client.BoogerClient;
import dev.booger.client.module.BoogerModule;
import dev.booger.client.module.ModuleCategory;
import dev.booger.client.module.Setting;
import dev.booger.client.render.BoogerFontRenderer;
import dev.booger.client.render.theme.ThemeEngine;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.registry.entry.RegistryEntry;

import java.util.Collection;

/**
 * Active potion effects display.
 * Shows effect name + remaining duration, color-coded by effect type.
 */
public final class PotionModule extends BoogerModule {

    private final Setting<Boolean> showAmplifier = addSetting(
        Setting.bool("showAmplifier", "Show Level", "Show effect level (e.g. Speed II)", true)
    );
    private final Setting<Boolean> showDuration = addSetting(
        Setting.bool("showDuration", "Show Duration", true)
    );

    public PotionModule() {
        super("Potions", "Active potion effects", ModuleCategory.HUD);
        setEnabled(true);
        setHudPosition(2, 100);
    }

    @Override
    public void onRender(DrawContext context, float partialTick, int screenW, int screenH) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        BoogerFontRenderer font = BoogerClient.hud().getFontRenderer();
        var theme = ThemeEngine.current();

        Collection<StatusEffectInstance> effects = mc.player.getStatusEffects();
        if (effects.isEmpty()) return;

        float y = 0;
        for (StatusEffectInstance effect : effects) {
            RegistryEntry<StatusEffect> type = effect.getEffectType();
            String name = type.value().getName().getString();

            if (showAmplifier.getValue() && effect.getAmplifier() > 0) {
                name += " " + toRoman(effect.getAmplifier() + 1);
            }

            String dur = "";
            if (showDuration.getValue()) {
                int ticks = effect.getDuration();
                if (ticks == Integer.MAX_VALUE) {
                    dur = " ∞";
                } else {
                    int secs = ticks / 20;
                    dur = " " + (secs >= 60 ? secs/60 + "m" + secs%60 + "s" : secs + "s");
                }
            }

            String line = name + dur;

            // Color: beneficial = green, harmful = red
            boolean beneficial = type.value().isBeneficial();
            int color = beneficial ? theme.colorGood() : theme.colorDanger();

            // Flash when < 10 seconds
            if (effect.getDuration() < 200 && (System.currentTimeMillis() / 500) % 2 == 0) {
                color = theme.colorWarning();
            }

            font.drawText(context, line, 0, y, color, theme.shadowText());
            y += font.getFontHeight() + 2;
        }
    }

    private static String toRoman(int n) {
        return switch (n) {
            case 2 -> "II"; case 3 -> "III"; case 4 -> "IV"; case 5 -> "V";
            default -> String.valueOf(n);
        };
    }
}
