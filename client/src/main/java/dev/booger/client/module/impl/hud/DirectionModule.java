package dev.booger.client.module.impl.hud;

import dev.booger.client.BoogerClient;
import dev.booger.client.module.BoogerModule;
import dev.booger.client.module.ModuleCategory;
import dev.booger.client.render.BoogerFontRenderer;
import dev.booger.client.render.theme.ThemeEngine;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;

public final class DirectionModule extends BoogerModule {

    public DirectionModule() {
        super("Direction", "Facing direction display", ModuleCategory.HUD);
        setEnabled(true);
        setHudPosition(2, 240);
    }

    @Override
    public void onRender(DrawContext context, float partialTick, int screenW, int screenH) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        BoogerFontRenderer font = BoogerClient.hud().getFontRenderer();
        var theme = ThemeEngine.current();

        float yaw = MathHelper.lerp(partialTick, mc.player.prevYaw, mc.player.getYaw());
        Direction dir = Direction.fromRotation(yaw);
        String dirName = switch (dir) {
            case NORTH -> "North (-Z)";
            case SOUTH -> "South (+Z)";
            case EAST  -> "East (+X)";
            case WEST  -> "West (-X)";
            default    -> dir.getName();
        };

        float pitch = MathHelper.lerp(partialTick, mc.player.prevPitch, mc.player.getPitch());
        String text = String.format("Facing: %s (%.1f°)", dirName, yaw < 0 ? yaw + 360 : yaw);
        font.drawText(context, text, 0, 0, theme.textPrimary(), theme.shadowText());
    }
}
