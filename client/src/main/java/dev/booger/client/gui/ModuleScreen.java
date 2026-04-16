package dev.booger.client.gui;

import dev.booger.client.BoogerClient;
import dev.booger.client.module.BoogerModule;
import dev.booger.client.module.ModuleCategory;
import dev.booger.client.module.Setting;
import dev.booger.client.render.BoogerFontRenderer;
import dev.booger.client.render.theme.Theme;
import dev.booger.client.render.theme.ThemeEngine;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * In-game module settings screen.
 *
 * LAYOUT:
 * ┌──────────┬──────────────────────────────────────────┐
 * │ CATEGORY │  MODULE LIST (filtered by category)       │
 * │ SIDEBAR  ├──────────────────────────────────────────┤
 * │          │  SETTINGS PANEL (selected module)         │
 * │          │  - Toggle, sliders, color pickers         │
 * │          │  - HUD position reset                     │
 * │          │  - Keybind assignment                     │
 * └──────────┴──────────────────────────────────────────┘
 *
 * DESIGN DECISIONS vs Lunar's UI:
 * - Lunar's settings screen is a flat list with no category filtering
 * - We group by category with a sidebar — finding modules is O(1) not O(n)
 * - Lunar opens a separate screen per module — we show settings inline
 * - Our sliders are draggable — Lunar's require text input
 * - We support real-time preview — changing a color shows it in HUD instantly
 *
 * WIDGET SYSTEM:
 * Each Setting<T> type maps to a widget renderer:
 * - Boolean → toggle switch
 * - Integer → draggable slider with numeric readout
 * - Float   → draggable slider with decimal readout
 * - String  → text input field
 * - Enum    → cycling button
 * - Color   → color swatch that opens inline color picker
 *
 * All widgets are rendered procedurally — no Minecraft widget classes.
 * This avoids vanilla's layout system (which is designed for simple menus,
 * not dense settings UIs) and gives us full control over appearance.
 */
public final class ModuleScreen extends Screen {

    // Layout constants
    private static final int SIDEBAR_W     = 110;
    private static final int MODULE_LIST_W = 180;
    private static final int ROW_H         = 20;
    private static final int PADDING       = 8;
    private static final int SLIDER_H      = 12;

    // State
    private ModuleCategory selectedCategory = ModuleCategory.HUD;
    private BoogerModule selectedModule = null;
    private int moduleScrollY = 0;
    private int settingsScrollY = 0;

    // Active slider drag
    private Setting<?> draggingSlider = null;
    private float sliderStartX = 0;

    // Search
    private String searchQuery = "";
    private boolean searchFocused = false;

    // Color picker state
    private Setting<Integer> colorPickerSetting = null;
    private float pickerHue = 0, pickerSat = 1, pickerVal = 1, pickerAlpha = 1;

    private final BoogerFontRenderer font;

    public ModuleScreen() {
        super(Text.literal("Booger Settings"));
        this.font = BoogerClient.hud().getFontRenderer();
    }

    @Override
    public boolean shouldPause() { return false; }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        int w = width, h = height;
        Theme theme = ThemeEngine.current();

        // ── Background overlay ─────────────────────────────────────────────
        ctx.fill(0, 0, w, h, 0xCC0A0A0A);

        // ── Category sidebar ───────────────────────────────────────────────
        renderSidebar(ctx, mouseX, mouseY, h, theme);

        // ── Module list ────────────────────────────────────────────────────
        int listX = SIDEBAR_W;
        int listH = h;
        renderModuleList(ctx, mouseX, mouseY, listX, listH, theme);

        // ── Settings panel ─────────────────────────────────────────────────
        int panelX = SIDEBAR_W + MODULE_LIST_W;
        int panelW = w - panelX;
        if (selectedModule != null) {
            renderSettingsPanel(ctx, mouseX, mouseY, panelX, panelW, h, theme);
        }

        // ── Color picker overlay ───────────────────────────────────────────
        if (colorPickerSetting != null) {
            renderColorPicker(ctx, mouseX, mouseY, w, h, theme);
        }

        // ── Title bar ─────────────────────────────────────────────────────
        renderTitleBar(ctx, w, theme);

        super.render(ctx, mouseX, mouseY, delta);
    }

    private void renderTitleBar(DrawContext ctx, int w, Theme theme) {
        ctx.fill(0, 0, w, 24, 0xEE0D0D0D);
        font.drawText(ctx, "BOOGER CLIENT", PADDING, 8, theme.accentPrimary(), false);

        // Profile indicator
        String profile = BoogerClient.getInstance().getCore()
            .getProfileManager().getActiveProfile();
        String profileStr = "Profile: " + profile;
        int profileW = font.getStringWidth(profileStr);
        font.drawText(ctx, profileStr, w - profileW - PADDING, 8,
            theme.textSecondary(), false);

        // Horizontal divider
        ctx.fill(0, 24, w, 25, BoogerFontRenderer.withAlpha(theme.bgBorder(), 120));
    }

    private void renderSidebar(DrawContext ctx, int mx, int my, int h, Theme theme) {
        ctx.fill(0, 25, SIDEBAR_W, h, 0xEE0D0D0D);
        ctx.fill(SIDEBAR_W, 25, SIDEBAR_W + 1, h, BoogerFontRenderer.withAlpha(theme.bgBorder(), 80));

        int y = 32;
        for (ModuleCategory cat : ModuleCategory.values()) {
            boolean selected = cat == selectedCategory;
            boolean hovered = mx >= 0 && mx < SIDEBAR_W && my >= y && my < y + ROW_H;

            if (selected) {
                ctx.fill(0, y, SIDEBAR_W - 1, y + ROW_H,
                    BoogerFontRenderer.withAlpha(theme.accentPrimary(), 40));
                ctx.fill(0, y, 2, y + ROW_H, theme.accentPrimary()); // Accent left bar
            } else if (hovered) {
                ctx.fill(0, y, SIDEBAR_W - 1, y + ROW_H, 0x18FFFFFF);
            }

            int textColor = selected ? theme.accentPrimary()
                          : hovered  ? theme.textPrimary()
                          : theme.textSecondary();

            font.drawText(ctx, cat.displayName, PADDING, y + 6, textColor, false);

            // Module count badge
            long count = BoogerClient.modules().getModulesByCategory(cat)
                .stream().filter(BoogerModule::isEnabled).count();
            long total = BoogerClient.modules().getModulesByCategory(cat).size();
            String badge = count + "/" + total;
            int badgeW = font.getStringWidth(badge);
            font.drawText(ctx, badge, SIDEBAR_W - badgeW - PADDING, y + 6,
                theme.textSecondary(), false);

            y += ROW_H;
        }
    }

    private void renderModuleList(DrawContext ctx, int mx, int my,
                                   int x, int h, Theme theme) {
        ctx.fill(x, 25, x + MODULE_LIST_W, h, 0xEE111111);
        ctx.fill(x + MODULE_LIST_W, 25, x + MODULE_LIST_W + 1, h,
            BoogerFontRenderer.withAlpha(theme.bgBorder(), 60));

        List<BoogerModule> modules = getFilteredModules();
        int y = 32 - moduleScrollY;

        for (BoogerModule module : modules) {
            if (y + ROW_H < 32 || y > h) { y += ROW_H + 2; continue; }

            boolean isSelected = module == selectedModule;
            boolean isHovered = mx >= x && mx < x + MODULE_LIST_W
                && my >= y && my < y + ROW_H;

            if (isSelected) {
                ctx.fill(x, y, x + MODULE_LIST_W, y + ROW_H,
                    BoogerFontRenderer.withAlpha(theme.accentPrimary(), 30));
                ctx.fill(x, y, x + 2, y + ROW_H, theme.accentPrimary());
            } else if (isHovered) {
                ctx.fill(x, y, x + MODULE_LIST_W, y + ROW_H, 0x14FFFFFF);
            }

            // Enable indicator dot
            int dotColor = module.isEnabled() ? theme.colorGood() : theme.colorDanger();
            ctx.fill(x + PADDING, y + ROW_H / 2 - 2, x + PADDING + 4, y + ROW_H / 2 + 2, dotColor);

            int textColor = module.isEnabled() ? theme.textPrimary() : theme.textSecondary();
            font.drawText(ctx, module.getName(), x + PADDING + 8, y + 6, textColor, false);
            y += ROW_H + 2;
        }
    }

    private void renderSettingsPanel(DrawContext ctx, int mx, int my,
                                      int x, int w, int h, Theme theme) {
        ctx.fill(x, 25, x + w, h, 0xEE0D0D0D);

        int y = 32 - settingsScrollY;

        // Module header
        ctx.fill(x, y, x + w, y + 28, 0x18FFFFFF);
        font.drawText(ctx, selectedModule.getName(), x + PADDING, y + 8,
            theme.textPrimary(), false);
        font.drawText(ctx, selectedModule.getDescription(),
            x + PADDING + font.getStringWidth(selectedModule.getName()) + 8,
            y + 9, theme.textSecondary(), false);

        // Enable toggle
        renderToggle(ctx, mx, my, x + w - 50, y + 8, selectedModule.isEnabled(),
            theme, "enable_module");
        y += 36;

        // Settings
        for (Setting<?> setting : selectedModule.getSettings()) {
            if (y + 24 > h) break;
            y = renderSetting(ctx, mx, my, x, y, w, setting, theme);
        }

        // HUD position reset button
        if (y + 24 < h) {
            renderButton(ctx, mx, my, x + PADDING, y + 4, 100, 16,
                "Reset Position", theme, "reset_pos");
            y += 28;
        }
    }

    private int renderSetting(DrawContext ctx, int mx, int my, int x, int y,
                               int w, Setting<?> setting, Theme theme) {
        font.drawText(ctx, setting.getDisplayName(), x + PADDING, y + 4,
            theme.textSecondary(), false);

        int rightX = x + w - PADDING;
        int midY = y + 4;

        switch (setting.getType()) {
            case BOOLEAN -> {
                boolean val = (Boolean) setting.getValue();
                renderToggle(ctx, mx, my, rightX - 32, midY, val, theme,
                    "toggle_" + setting.getKey());
            }
            case INTEGER, FLOAT -> {
                renderSlider(ctx, mx, my, rightX - 100, midY, 100, setting, theme);
            }
            case ENUM -> {
                String valStr = ((Enum<?>)setting.getValue()).name();
                renderButton(ctx, mx, my, rightX - 80, midY, 80, 14,
                    valStr, theme, "enum_" + setting.getKey());
            }
            case COLOR -> {
                int color = (Integer) setting.getValue();
                renderColorSwatch(ctx, mx, my, rightX - 24, midY, 20, 14, color,
                    setting, theme);
            }
            case STRING -> {
                font.drawText(ctx, "[" + setting.getValue() + "]",
                    rightX - 80, midY, theme.textValue(), false);
            }
        }

        return y + ROW_H;
    }

    private void renderToggle(DrawContext ctx, int mx, int my, int x, int y,
                               boolean on, Theme theme, String id) {
        int trackW = 28, trackH = 14;
        int trackColor = on ? theme.accentPrimary() : theme.bgSecondary();
        int borderColor = on ? theme.accentPrimary() : theme.bgBorder();

        ctx.fill(x, y, x + trackW, y + trackH, trackColor);
        font.drawBorder(ctx, x, y, trackW, trackH, borderColor);

        // Thumb
        int thumbX = on ? x + trackW - trackH + 2 : x + 2;
        ctx.fill(thumbX, y + 2, thumbX + trackH - 4, y + trackH - 2, 0xFFFFFFFF);
    }

    private void renderSlider(DrawContext ctx, int mx, int my, int x, int y,
                               int w, Setting<?> setting, Theme theme) {
        int sliderH = SLIDER_H;
        ctx.fill(x, y + sliderH / 2 - 2, x + w, y + sliderH / 2 + 2, theme.bgSecondary());

        float pct = getSettingPercent(setting);
        int fillW = (int)(w * pct);
        if (fillW > 0) {
            ctx.fill(x, y + sliderH / 2 - 2, x + fillW, y + sliderH / 2 + 2,
                theme.accentPrimary());
        }

        // Thumb
        int thumbX = x + fillW - 4;
        ctx.fill(thumbX, y + 1, thumbX + 8, y + sliderH - 1, theme.textPrimary());
        font.drawBorder(ctx, thumbX, y + 1, 8, sliderH - 2, theme.accentPrimary());

        // Value readout
        String valStr = formatSettingValue(setting);
        font.drawText(ctx, valStr, x + w + 4, y + 2, theme.textValue(), false);
    }

    private void renderButton(DrawContext ctx, int mx, int my, int x, int y,
                               int w, int h, String label, Theme theme, String id) {
        boolean hovered = mx >= x && mx < x + w && my >= y && my < y + h;
        int bg = hovered ? BoogerFontRenderer.withAlpha(theme.accentPrimary(), 60) : theme.bgSecondary();
        ctx.fill(x, y, x + w, y + h, bg);
        font.drawBorder(ctx, x, y, w, h, theme.bgBorder());
        int labelW = font.getStringWidth(label);
        font.drawText(ctx, label, x + (w - labelW) / 2, y + h / 2 - 4, theme.textPrimary(), false);
    }

    private void renderColorSwatch(DrawContext ctx, int mx, int my, int x, int y,
                                    int w, int h, int color,
                                    Setting<Integer> setting, Theme theme) {
        ctx.fill(x, y, x + w, y + h, color);
        font.drawBorder(ctx, x, y, w, h, 0xFFFFFFFF);
        if (mx >= x && mx < x + w && my >= y && my < y + h) {
            ctx.fill(x, y, x + w, y + h, 0x22FFFFFF);
        }
    }

    private void renderColorPicker(DrawContext ctx, int mx, int my,
                                    int w, int h, Theme theme) {
        // Semi-transparent backdrop
        ctx.fill(0, 0, w, h, 0x88000000);

        // Picker panel centered
        int pw = 220, ph = 200;
        int px = (w - pw) / 2, py = (h - ph) / 2;
        ctx.fill(px, py, px + pw, py + ph, 0xFF1A1A1A);
        font.drawBorder(ctx, px, py, pw, ph, theme.bgBorder());
        font.drawText(ctx, "Color Picker", px + 8, py + 8, theme.textPrimary(), false);

        // Hue bar
        int hueY = py + 24;
        for (int i = 0; i < pw - 16; i++) {
            float hue = (float) i / (pw - 16);
            int c = hsvToArgb(hue, 1f, 1f, 1f);
            ctx.fill(px + 8 + i, hueY, px + 9 + i, hueY + 12, c);
        }
        font.drawBorder(ctx, px + 8, hueY, pw - 16, 12, theme.bgBorder());

        // Hue cursor
        int hueMarkerX = px + 8 + (int)(pickerHue * (pw - 16));
        ctx.fill(hueMarkerX - 1, hueY - 2, hueMarkerX + 1, hueY + 14, 0xFFFFFFFF);

        // Preview swatch
        int previewColor = hsvToArgb(pickerHue, pickerSat, pickerVal, pickerAlpha);
        ctx.fill(px + 8, hueY + 20, px + 48, hueY + 60, previewColor);
        font.drawBorder(ctx, px + 8, hueY + 20, 40, 40, theme.bgBorder());

        // Hex value
        String hex = String.format("#%08X", previewColor);
        font.drawText(ctx, hex, px + 56, hueY + 36, theme.textPrimary(), false);

        // Apply + Cancel buttons
        renderButton(ctx, mx, my, px + 8,  py + ph - 28, 90, 18, "Apply", theme, "cp_apply");
        renderButton(ctx, mx, my, px + 108, py + ph - 28, 90, 18, "Cancel", theme, "cp_cancel");
    }

    // ─── Input handling ───────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int x = (int)mx, y = (int)my;

        // Color picker intercepts all clicks when open
        if (colorPickerSetting != null) {
            handleColorPickerClick(x, y, button);
            return true;
        }

        // Category sidebar
        if (x < SIDEBAR_W && y > 25) {
            int catY = 32;
            for (ModuleCategory cat : ModuleCategory.values()) {
                if (y >= catY && y < catY + ROW_H) {
                    selectedCategory = cat;
                    selectedModule = null;
                    moduleScrollY = 0;
                    return true;
                }
                catY += ROW_H;
            }
        }

        // Module list
        int listX = SIDEBAR_W;
        if (x >= listX && x < listX + MODULE_LIST_W && y > 25) {
            List<BoogerModule> modules = getFilteredModules();
            int my2 = 32 - moduleScrollY;
            for (BoogerModule module : modules) {
                if (y >= my2 && y < my2 + ROW_H + 2) {
                    if (button == 0) {
                        selectedModule = module;
                        settingsScrollY = 0;
                    } else if (button == 1) {
                        module.toggle();
                        BoogerClient.hud().markRenderListDirty();
                    }
                    return true;
                }
                my2 += ROW_H + 2;
            }
        }

        // Settings panel toggles and buttons
        if (selectedModule != null && x >= SIDEBAR_W + MODULE_LIST_W) {
            handleSettingsClick(x, y, button);
        }

        return super.mouseClicked(mx, my, button);
    }

    private void handleSettingsClick(int x, int y, int button) {
        Theme theme = ThemeEngine.current();
        int panelX = SIDEBAR_W + MODULE_LIST_W;
        int toggleX = width - 50;
        int baseY = 32 - settingsScrollY;

        // Enable toggle for the module itself
        if (x >= toggleX && x < toggleX + 28 && y >= baseY + 8 && y < baseY + 22) {
            selectedModule.toggle();
            BoogerClient.hud().markRenderListDirty();
            return;
        }

        baseY += 36;

        for (Setting<?> setting : selectedModule.getSettings()) {
            int midY = baseY + 4;

            if (setting.getType() == Setting.Type.BOOLEAN) {
                int tx = width - 50;
                if (x >= tx && x < tx + 28 && y >= midY && y < midY + 14) {
                    setting.setValue(!(Boolean)setting.getValue());
                    BoogerClient.config().markDirty();
                }
            } else if (setting.getType() == Setting.Type.ENUM) {
                int bx = width - 80 - PADDING;
                if (x >= bx && x < bx + 80 && y >= midY && y < midY + 14) {
                    cycleEnum(setting);
                }
            } else if (setting.getType() == Setting.Type.COLOR) {
                int cx = width - 24 - PADDING;
                if (x >= cx && x < cx + 20 && y >= midY && y < midY + 14) {
                    @SuppressWarnings("unchecked")
                    Setting<Integer> colorSetting = (Setting<Integer>) setting;
                    openColorPicker(colorSetting);
                }
            }
            baseY += ROW_H;
        }
    }

    private void handleColorPickerClick(int x, int y, int button) {
        int pw = 220, ph = 200;
        int px = (width - pw) / 2, py = (height - ph) / 2;
        int hueY = py + 24;

        // Hue bar click
        if (x >= px + 8 && x < px + pw - 8 && y >= hueY && y < hueY + 12) {
            pickerHue = (float)(x - px - 8) / (pw - 16);
            pickerHue = Math.max(0f, Math.min(1f, pickerHue));
            updateColorFromPicker();
        }

        // Apply button
        if (x >= px + 8 && x < px + 98 && y >= py + ph - 28 && y < py + ph - 10) {
            colorPickerSetting.setValue(hsvToArgb(pickerHue, pickerSat, pickerVal, pickerAlpha));
            BoogerClient.config().markDirty();
            colorPickerSetting = null;
        }

        // Cancel button
        if (x >= px + 108 && x < px + 198 && y >= py + ph - 28 && y < py + ph - 10) {
            colorPickerSetting = null;
        }
    }

    private void openColorPicker(Setting<Integer> setting) {
        colorPickerSetting = setting;
        int argb = setting.getValue();
        // Convert ARGB to HSV for the picker
        float r = ((argb >> 16) & 0xFF) / 255f;
        float g = ((argb >>  8) & 0xFF) / 255f;
        float b = ( argb        & 0xFF) / 255f;
        pickerAlpha = ((argb >> 24) & 0xFF) / 255f;
        float[] hsv = rgbToHsv(r, g, b);
        pickerHue = hsv[0]; pickerSat = hsv[1]; pickerVal = hsv[2];
    }

    private void updateColorFromPicker() {
        if (colorPickerSetting != null) {
            colorPickerSetting.setValue(
                hsvToArgb(pickerHue, pickerSat, pickerVal, pickerAlpha));
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hAmount, double vAmount) {
        int x = (int)mx;
        if (x < SIDEBAR_W + MODULE_LIST_W) {
            moduleScrollY = Math.max(0, (int)(moduleScrollY - vAmount * 15));
        } else {
            settingsScrollY = Math.max(0, (int)(settingsScrollY - vAmount * 15));
        }
        return true;
    }

    @Override
    public void close() {
        BoogerClient.config().markDirty();
        super.close();
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private List<BoogerModule> getFilteredModules() {
        return BoogerClient.modules().getModulesByCategory(selectedCategory);
    }

    @SuppressWarnings("unchecked")
    private void cycleEnum(Setting<?> setting) {
        Object current = setting.getValue();
        if (!(current instanceof Enum)) return;
        Enum<?> e = (Enum<?>) current;
        Object[] constants = e.getClass().getEnumConstants();
        int nextIdx = (e.ordinal() + 1) % constants.length;
        ((Setting<Object>) setting).setValue(constants[nextIdx]);
        BoogerClient.config().markDirty();
    }

    private float getSettingPercent(Setting<?> setting) {
        Object val = setting.getValue();
        Number min = setting.getMinValue();
        Number max = setting.getMaxValue();
        if (min == null || max == null) return 0f;
        if (val instanceof Integer i)
            return (float)(i - min.intValue()) / (max.intValue() - min.intValue());
        if (val instanceof Float f)
            return (f - min.floatValue()) / (max.floatValue() - min.floatValue());
        return 0f;
    }

    private String formatSettingValue(Setting<?> setting) {
        Object val = setting.getValue();
        if (val instanceof Float f) return String.format("%.2f", f);
        return String.valueOf(val);
    }

    private static int hsvToArgb(float h, float s, float v, float a) {
        int rgb = java.awt.Color.HSBtoRGB(h, s, v);
        int alpha = (int)(a * 255) & 0xFF;
        return (alpha << 24) | (rgb & 0x00FFFFFF);
    }

    private static float[] rgbToHsv(float r, float g, float b) {
        float[] hsv = new float[3];
        java.awt.Color.RGBtoHSB((int)(r*255), (int)(g*255), (int)(b*255), hsv);
        return hsv;
    }
}
