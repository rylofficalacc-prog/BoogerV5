package dev.booger.client.module.impl.hud;

import dev.booger.client.BoogerClient;
import dev.booger.client.module.BoogerModule;
import dev.booger.client.module.ModuleCategory;
import dev.booger.client.module.Setting;
import dev.booger.client.render.BoogerFontRenderer;
import dev.booger.client.render.theme.ThemeEngine;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.MathHelper;

public final class CoordinatesModule extends BoogerModule {

    private final Setting<Boolean> showNether = addSetting(
        Setting.bool("showNether", "Show Nether Coords", "Show Nether equivalent coordinates", false)
    );
    private final Setting<Boolean> showBackground = addSetting(
        Setting.bool("showBackground", "Background", true)
    );

    public CoordinatesModule() {
        super("Coordinates", "Player XYZ coordinates", ModuleCategory.HUD);
        setEnabled(true);
        setHudPosition(2, 200);
    }

    @Override
    public void onRender(DrawContext context, float partialTick, int screenW, int screenH) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        BoogerFontRenderer font = BoogerClient.hud().getFontRenderer();
        var theme = ThemeEngine.current();

        // Interpolate position using partialTick for smooth display at high FPS
        double x = MathHelper.lerp(partialTick,
            mc.player.lastRenderX, mc.player.getX());
        double y = MathHelper.lerp(partialTick,
            mc.player.lastRenderY, mc.player.getY());
        double z = MathHelper.lerp(partialTick,
            mc.player.lastRenderZ, mc.player.getZ());

        String xStr = String.format("X: %.1f", x);
        String yStr = String.format("Y: %.1f", y);
        String zStr = String.format("Z: %.1f", z);

        if (showBackground.getValue()) {
            int maxWidth = Math.max(font.getStringWidth(xStr),
                          Math.max(font.getStringWidth(yStr), font.getStringWidth(zStr)));
            float fh = font.getFontHeight();
            float pad = theme.elementPadding();
            font.drawBox(context, -pad, -pad,
                maxWidth + pad * 2, fh * 3 + 4 + pad * 2, theme.bgPrimary());
        }

        float fh = font.getFontHeight() + 1;
        font.drawText(context, xStr, 0, 0,        theme.colorDanger(),  theme.shadowText());
        font.drawText(context, yStr, 0, fh,       theme.colorGood(),    theme.shadowText());
        font.drawText(context, zStr, 0, fh * 2,   theme.accentPrimary(),theme.shadowText());

        if (showNether.getValue()) {
            boolean inNether = mc.world != null &&
                mc.world.getDimension().ultrawarm();
            double nx = inNether ? x * 8 : x / 8;
            double nz = inNether ? z * 8 : z / 8;
            String netherStr = String.format("N: %.0f, %.0f", nx, nz);
            font.drawText(context, netherStr, 0, fh * 3, theme.textSecondary(), theme.shadowText());
        }
    }
}
