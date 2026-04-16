package dev.booger.client.module.impl.hud;

import dev.booger.client.BoogerClient;
import dev.booger.client.module.BoogerModule;
import dev.booger.client.module.ModuleCategory;
import dev.booger.client.module.Setting;
import dev.booger.client.render.BoogerFontRenderer;
import dev.booger.client.render.theme.ThemeEngine;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.entity.EquipmentSlot;

/**
 * Armor status module.
 *
 * Renders current armor durability as:
 * - Text: "H:348 C:421 L:80 B:55" (Helmet/Chestplate/Leggings/Boots)
 * - Optional: color-coded durability bars
 * - Warning: flashes red when any piece is <10% durability
 *
 * We read directly from the player's equipment slots.
 * Update rate: 20Hz (every tick) since durability changes on hit.
 *
 * WHY TRACK ARMOR IN PvP:
 * Dying because you didn't notice your chestplate was 2 hits from breaking
 * is a common problem. Our module makes this impossible to miss.
 */
public final class ArmorModule extends BoogerModule {

    private final Setting<Boolean> showBars = addSetting(
        Setting.bool("showBars", "Show Bars", "Show durability bars", true)
    );
    private final Setting<Boolean> showText = addSetting(
        Setting.bool("showText", "Show Numbers", "Show durability numbers", true)
    );
    private final Setting<Boolean> warnFlash = addSetting(
        Setting.bool("warnFlash", "Low Durability Warning", "Flash when armor is about to break", true)
    );

    // Armor slots in render order (head to boots)
    private static final EquipmentSlot[] SLOTS = {
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };
    private static final String[] LABELS = { "H", "C", "L", "B" };

    private boolean flashState = false;
    private long lastFlashToggle = 0;

    public ArmorModule() {
        super("Armor", "Armor durability display", ModuleCategory.HUD);
        setEnabled(true);
        setHudPosition(2, 38);
    }

    @Override
    public void onRender(DrawContext context, float partialTick, int screenW, int screenH) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        BoogerFontRenderer font = BoogerClient.hud().getFontRenderer();
        var theme = ThemeEngine.current();

        // Check for low durability warning
        boolean anyLow = false;
        for (EquipmentSlot slot : SLOTS) {
            ItemStack stack = mc.player.getEquippedStack(slot);
            if (!stack.isEmpty() && stack.isDamageable()) {
                float pct = 1.0f - (float) stack.getDamage() / stack.getMaxDamage();
                if (pct < 0.1f) { anyLow = true; break; }
            }
        }

        // Flash logic for low durability
        if (anyLow && warnFlash.getValue()) {
            long now = System.currentTimeMillis();
            if (now - lastFlashToggle > 500) {
                flashState = !flashState;
                lastFlashToggle = now;
            }
        } else {
            flashState = false;
        }

        float y = 0;
        float barWidth = 60;
        float barHeight = 3;
        float textHeight = font.getFontHeight();
        float rowHeight = showBars.getValue() ? textHeight + barHeight + 2 : textHeight + 2;

        for (int i = 0; i < SLOTS.length; i++) {
            ItemStack stack = mc.player.getEquippedStack(SLOTS[i]);
            if (stack.isEmpty()) continue;

            float durPct;
            String durText;

            if (stack.isDamageable()) {
                int remaining = stack.getMaxDamage() - stack.getDamage();
                durPct = 1.0f - (float) stack.getDamage() / stack.getMaxDamage();
                durText = LABELS[i] + ":" + remaining;
            } else {
                // Unbreakable item (e.g., carved pumpkin)
                durPct = 1.0f;
                durText = LABELS[i] + ":∞";
            }

            // Color based on durability percentage
            int barColor;
            if (durPct > 0.5f)       barColor = theme.colorGood();
            else if (durPct > 0.2f)  barColor = theme.colorWarning();
            else                     barColor = anyLow && flashState
                                         ? BoogerFontRenderer.WHITE : theme.colorDanger();

            if (showText.getValue()) {
                int textColor = durPct < 0.1f && flashState ? theme.colorDanger() : theme.textPrimary();
                font.drawText(context, durText, 0, y, textColor, theme.shadowText());
            }

            if (showBars.getValue()) {
                float barY = y + textHeight + 1;
                // Background bar
                font.drawBox(context, 0, barY, barWidth, barHeight, theme.bgSecondary());
                // Filled portion
                font.drawBox(context, 0, barY, barWidth * durPct, barHeight, barColor);
                // Border
                font.drawBorder(context, 0, barY, barWidth, barHeight,
                    BoogerFontRenderer.withAlpha(theme.bgBorder(), 120));
            }

            y += rowHeight;
        }
    }
}
