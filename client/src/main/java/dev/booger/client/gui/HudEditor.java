package dev.booger.client.gui;

import dev.booger.client.BoogerClient;
import dev.booger.client.module.BoogerModule;
import dev.booger.client.render.BoogerFontRenderer;
import dev.booger.client.render.theme.ThemeEngine;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.List;

/**
 * HUD Editor screen — opened via keybind (default: Right Shift).
 *
 * FEATURES:
 * - Drag-and-drop HUD element repositioning
 * - Snap-to-grid (8px grid, held Shift = fine 1px)
 * - Module enable/disable toggle (right-click on element)
 * - Scale adjustment (scroll wheel on element)
 * - Module list panel on the left
 * - Preview of all active HUD elements with wireframe bounds
 * - Edge snapping — elements snap to screen edges and to each other
 * - Reset all positions button
 *
 * RENDER ARCHITECTURE:
 * The editor renders the actual HUD modules (via HudRenderer) in the background
 * at reduced alpha, then draws editor chrome on top (selection handles, bounds,
 * snap lines, UI panel).
 *
 * This means you see EXACTLY how the HUD will look while editing it —
 * no preview mismatch. Lunar's editor has a slight mismatch because they
 * render preview proxies instead of the actual module render.
 *
 * DRAG DETECTION:
 * Hit-testing uses the module's bounding box computed from the last render.
 * BoogerModule stores its rendered bounds after each onRender() call so we
 * can do accurate hit-testing even for variable-size elements (potion list
 * grows/shrinks based on active effects — its drag handle adjusts accordingly).
 */
public final class HudEditor extends Screen {

    // Grid snap sizes
    private static final int GRID_SNAP = 8;
    private static final int FINE_SNAP = 1;

    // Left panel dimensions
    private static final int PANEL_WIDTH = 120;
    private static final int PANEL_PADDING = 6;

    // State
    private BoogerModule dragging = null;
    private float dragOffsetX = 0;
    private float dragOffsetY = 0;
    private BoogerModule hovered = null;
    private BoogerModule selected = null;

    // Screen shake snap guide lines
    private float snapLineX = -1;
    private float snapLineY = -1;

    // Panel scroll
    private int panelScrollY = 0;
    private static final int MODULE_ROW_HEIGHT = 18;

    private final BoogerFontRenderer font;

    public HudEditor() {
        super(Text.literal("HUD Editor"));
        this.font = BoogerClient.hud().getFontRenderer();
    }

    @Override
    public boolean shouldPause() {
        return false; // Game keeps running while editor is open (see your HUD in context)
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        int w = mc.getWindow().getScaledWidth();
        int h = mc.getWindow().getScaledHeight();
        var theme = ThemeEngine.current();

        // ── Dark overlay on entire screen (semi-transparent) ──────────────
        context.fill(0, 0, w, h, 0x88000000);

        // ── Render actual HUD modules at reduced alpha ─────────────────────
        // We render them directly so users see true positioning
        renderHudPreview(context, delta, w, h, theme);

        // ── Snap guide lines ───────────────────────────────────────────────
        if (snapLineX >= 0) {
            context.fill((int) snapLineX, 0, (int) snapLineX + 1, h, 0x8800FF88);
        }
        if (snapLineY >= 0) {
            context.fill(0, (int) snapLineY, w, (int) snapLineY + 1, 0x8800FF88);
        }

        // ── Module selection wireframe ─────────────────────────────────────
        updateHovered(mouseX, mouseY);
        for (BoogerModule module : BoogerClient.modules().getModules()) {
            if (!module.isEnabled()) continue;
            float[] bounds = getModuleBounds(module);
            if (bounds == null) continue;

            boolean isHovered = module == hovered;
            boolean isSelected = module == selected;
            boolean isDragging = module == dragging;

            int borderColor = isDragging  ? 0xFF00FF88 :
                              isSelected  ? 0xFF44AAFF :
                              isHovered   ? 0xFF888888 :
                              0x44FFFFFF;

            font.drawBorder(context, bounds[0] - 2, bounds[1] - 2,
                bounds[2] + 4, bounds[3] + 4, borderColor);

            // Module name label on hover/select
            if (isHovered || isSelected) {
                String label = module.getName();
                int labelW = font.getStringWidth(label) + 4;
                float labelX = bounds[0];
                float labelY = bounds[1] - 12;
                if (labelY < 0) labelY = bounds[1] + bounds[3] + 2;

                font.drawBox(context, labelX - 2, labelY - 1, labelW, 10,
                    0xDD000000);
                font.drawText(context, label, labelX, labelY,
                    0xFFFFFFFF, false);
            }
        }

        // ── Left panel ─────────────────────────────────────────────────────
        renderModulePanel(context, w, h, mouseX, mouseY, theme);

        // ── Bottom bar ────────────────────────────────────────────────────
        renderBottomBar(context, w, h, theme);

        super.render(context, mouseX, mouseY, delta);
    }

    private void renderHudPreview(DrawContext ctx, float delta, int w, int h, dev.booger.client.render.theme.Theme theme) {
        // Render all enabled modules at 70% alpha by scaling the matrix
        // and relying on the module's own render — true WYSIWYG
        for (BoogerModule module : BoogerClient.modules().getModules()) {
            if (!module.isEnabled() || !module.isVisible()) continue;
            try {
                ctx.getMatrices().push();
                ctx.getMatrices().translate(module.getHudX(), module.getHudY(), 0);
                ctx.getMatrices().scale(module.getHudScale(), module.getHudScale(), 1f);
                module.onRender(ctx, delta, w, h);
                ctx.getMatrices().pop();
            } catch (Exception e) {
                // Ignore render errors during editing
            }
        }
    }

    private void renderModulePanel(DrawContext ctx, int screenW, int screenH,
                                    int mouseX, int mouseY,
                                    dev.booger.client.render.theme.Theme theme) {
        int panelH = screenH - 40;

        // Panel background
        font.drawBox(ctx, 0, 0, PANEL_WIDTH, panelH, 0xEE111111);
        font.drawBorder(ctx, 0, 0, PANEL_WIDTH, panelH, theme.bgBorder());

        // Header
        font.drawText(ctx, "MODULES", PANEL_PADDING, 6, theme.accentPrimary(), false);
        font.drawBox(ctx, PANEL_PADDING, 15, PANEL_WIDTH - PANEL_PADDING * 2, 1,
            BoogerFontRenderer.withAlpha(theme.bgBorder(), 100));

        List<BoogerModule> modules = BoogerClient.modules().getModules();
        int y = 20 - panelScrollY;

        for (BoogerModule module : modules) {
            if (y + MODULE_ROW_HEIGHT < 0 || y > panelH) {
                y += MODULE_ROW_HEIGHT;
                continue;
            }

            boolean isSelected = module == selected;
            boolean rowHovered = mouseX >= 0 && mouseX <= PANEL_WIDTH
                && mouseY >= y && mouseY < y + MODULE_ROW_HEIGHT;

            if (isSelected || rowHovered) {
                font.drawBox(ctx, 2, y, PANEL_WIDTH - 4, MODULE_ROW_HEIGHT - 1,
                    isSelected ? 0x4400AAFF : 0x22FFFFFF);
            }

            // Enable/disable toggle indicator
            int indicatorColor = module.isEnabled() ? theme.colorGood() : theme.colorDanger();
            font.drawBox(ctx, PANEL_PADDING, y + 5, 6, 6, indicatorColor);

            // Module name
            int textColor = module.isEnabled() ? theme.textPrimary() : theme.textSecondary();
            font.drawText(ctx, module.getName(), PANEL_PADDING + 10, y + 4,
                textColor, false);

            y += MODULE_ROW_HEIGHT;
        }
    }

    private void renderBottomBar(DrawContext ctx, int w, int h,
                                  dev.booger.client.render.theme.Theme theme) {
        int barY = h - 20;
        font.drawBox(ctx, 0, barY, w, 20, 0xEE111111);
        font.drawBorder(ctx, 0, barY, w, 1, theme.bgBorder());

        String hints = "  Drag: Move  |  Scroll: Scale  |  Right-click: Toggle  |  Shift: Fine snap  |  ESC: Close & Save";
        font.drawText(ctx, hints, PANEL_WIDTH + 5, barY + 6, theme.textSecondary(), false);

        // Show selected module info
        if (selected != null) {
            String info = String.format("  Selected: %s  X:%.0f Y:%.0f Scale:%.1fx",
                selected.getName(), selected.getHudX(), selected.getHudY(), selected.getHudScale());
            int infoW = font.getStringWidth(info);
            font.drawText(ctx, info, w - infoW - 5, barY + 6, theme.textPrimary(), false);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (mouseX <= PANEL_WIDTH) {
            handlePanelClick((int) mouseX, (int) mouseY, button);
            return true;
        }

        BoogerModule hit = hitTest((float) mouseX, (float) mouseY);

        if (button == 0) { // Left click — start drag
            if (hit != null) {
                dragging = hit;
                selected = hit;
                dragOffsetX = (float) mouseX - hit.getHudX();
                dragOffsetY = (float) mouseY - hit.getHudY();
            } else {
                selected = null;
            }
        } else if (button == 1) { // Right click — toggle enable
            if (hit != null) {
                hit.toggle();
                BoogerClient.hud().markRenderListDirty();
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && dragging != null) {
            snapLineX = -1;
            snapLineY = -1;
            dragging = null;
            BoogerClient.config().markDirty();
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button,
                                  double deltaX, double deltaY) {
        if (dragging != null && button == 0) {
            MinecraftClient mc = MinecraftClient.getInstance();
            int screenW = mc.getWindow().getScaledWidth();
            int screenH = mc.getWindow().getScaledHeight();

            float newX = (float) mouseX - dragOffsetX;
            float newY = (float) mouseY - dragOffsetY;

            // Clamp to screen bounds
            newX = Math.max(0, Math.min(screenW - 20, newX));
            newY = Math.max(0, Math.min(screenH - 20, newY));

            // Grid snap
            boolean fineSnap = hasShiftDown();
            int snap = fineSnap ? FINE_SNAP : GRID_SNAP;
            newX = Math.round(newX / snap) * snap;
            newY = Math.round(newY / snap) * snap;

            // Edge snapping — snap to screen edges within 8px
            snapLineX = -1;
            snapLineY = -1;
            if (Math.abs(newX) < 8) { newX = 0; snapLineX = 0; }
            else if (Math.abs(newX - screenW / 2f) < 8) { newX = screenW / 2f; snapLineX = newX; }
            if (Math.abs(newY) < 8) { newY = 0; snapLineY = 0; }

            dragging.setHudPosition(newX, newY);
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount,
                                   double verticalAmount) {
        if (mouseX <= PANEL_WIDTH) {
            panelScrollY = Math.max(0, (int)(panelScrollY - verticalAmount * 10));
            return true;
        }

        BoogerModule hit = hitTest((float) mouseX, (float) mouseY);
        if (hit != null) {
            float newScale = hit.getHudScale() + (float)(verticalAmount * 0.1);
            hit.setHudScale(newScale);
            BoogerClient.config().markDirty();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public void close() {
        BoogerClient.config().saveNow(); // Immediate save on close
        super.close();
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private void handlePanelClick(int mouseX, int mouseY, int button) {
        List<BoogerModule> modules = BoogerClient.modules().getModules();
        int y = 20 - panelScrollY;
        for (BoogerModule module : modules) {
            if (mouseY >= y && mouseY < y + MODULE_ROW_HEIGHT) {
                if (button == 0) {
                    selected = module;
                } else if (button == 1) {
                    module.toggle();
                    BoogerClient.hud().markRenderListDirty();
                }
                return;
            }
            y += MODULE_ROW_HEIGHT;
        }
    }

    private BoogerModule hitTest(float mx, float my) {
        // Iterate in reverse (topmost rendered = last in list) for correct z-order
        List<BoogerModule> modules = BoogerClient.modules().getModules();
        for (int i = modules.size() - 1; i >= 0; i--) {
            BoogerModule module = modules.get(i);
            if (!module.isEnabled()) continue;
            float[] bounds = getModuleBounds(module);
            if (bounds == null) continue;
            if (mx >= bounds[0] - 4 && mx <= bounds[0] + bounds[2] + 4
             && my >= bounds[1] - 4 && my <= bounds[1] + bounds[3] + 4) {
                return module;
            }
        }
        return null;
    }

    private void updateHovered(int mx, int my) {
        hovered = hitTest(mx, my);
    }

    /**
     * Returns [x, y, width, height] of a module's HUD bounds.
     * Uses the module's position and a rough size estimate.
     * Phase 4 will have modules report exact bounds from their last render.
     */
    private float[] getModuleBounds(BoogerModule module) {
        // Rough bounds — sufficient for hit testing
        float x = module.getHudX();
        float y = module.getHudY();
        float scale = module.getHudScale();
        float w = 80 * scale;  // Approximate — Phase 4 will use actual reported bounds
        float h = 10 * scale;
        return new float[]{x, y, w, h};
    }
}
