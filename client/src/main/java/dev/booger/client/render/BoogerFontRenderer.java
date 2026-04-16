package dev.booger.client.render;

import dev.booger.client.BoogerClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/**
 * Booger font renderer.
 *
 * PHASE 1 IMPLEMENTATION:
 * We wrap Minecraft's TextRenderer for correctness and ship fast.
 * Phase 3 will replace this with a true GPU glyph atlas renderer that:
 * - Pre-bakes all ASCII glyphs into a single 512x512 RGBA texture
 * - Batches all text draw calls into one VBO submission per frame
 * - Eliminates the per-character vertex buffer allocation Minecraft does
 *
 * WHY NOT DO THE FULL THING NOW?
 * Minecraft's text system (TextRenderer + FontStorage) changed significantly
 * in 1.20-1.21 with the font provider overhaul. Building on top of their
 * system correctly in Phase 1 avoids subtle Unicode/emoji/RTL rendering bugs
 * that would take weeks to fix in a custom renderer.
 *
 * We DO add on top of their renderer:
 * - Shadow control (optional, with color-correct shadow tinting)
 * - Background box rendering (used by most HUD modules)
 * - Outline rendering (2-pass or 8-direction technique)
 * - Color helpers (ARGB packing, alpha scaling)
 * - String width caching (avoids repeated TextRenderer.getWidth() calls)
 *
 * The vanilla TextRenderer.getWidth() iterates the entire string on every call.
 * For stable HUD elements that never change (FPS counter updates at 4Hz),
 * we cache the result and invalidate only when the string changes.
 */
public final class BoogerFontRenderer {

    private TextRenderer vanillaRenderer;

    // Width cache: string hash → pixel width
    // We use a simple open-addressing cache — not a HashMap — to avoid
    // boxing int values and the overhead of Map.Entry allocations
    private static final int CACHE_SIZE = 256; // Must be power of 2
    private static final int CACHE_MASK = CACHE_SIZE - 1;
    private final long[] cacheKeys = new long[CACHE_SIZE];   // String hashCode as long
    private final int[] cacheValues = new int[CACHE_SIZE];   // Pixel widths

    // Standard colors as pre-packed ARGB ints — avoid Color.pack() on hot path
    public static final int WHITE       = 0xFFFFFFFF;
    public static final int BLACK       = 0xFF000000;
    public static final int GRAY        = 0xFF808080;
    public static final int DARK_GRAY   = 0xFF404040;
    public static final int RED         = 0xFFFF5555;
    public static final int GREEN       = 0xFF55FF55;
    public static final int BLUE        = 0xFF5555FF;
    public static final int YELLOW      = 0xFFFFFF55;
    public static final int AQUA        = 0xFF55FFFF;
    public static final int GOLD        = 0xFFFFAA00;
    public static final int WHITE_90    = 0xE6FFFFFF; // 90% alpha white — softer than full white

    // Booger brand color
    public static final int BOOGER_GREEN = 0xFF4CAF50;
    public static final int BOOGER_ACCENT = 0xFF69F0AE;

    public void init() {
        vanillaRenderer = MinecraftClient.getInstance().textRenderer;
        BoogerClient.LOGGER.debug("BoogerFontRenderer initialized (wrapped vanilla)");
    }

    // ─── Core draw methods ───────────────────────────────────────────────────

    /**
     * Draw text at (x, y) with the given ARGB color.
     * Shadow is a soft drop-shadow at +1,+1 offset (not the harsh Minecraft shadow).
     */
    public void drawText(DrawContext context, String text, float x, float y, int color, boolean shadow) {
        if (shadow) {
            // Soft shadow: 60% alpha of the text color at +1,+1
            int shadowColor = (color & 0x00FFFFFF) | (((int)((color >>> 24) * 0.6f) & 0xFF) << 24);
            context.drawText(vanillaRenderer, text, (int)(x + 1), (int)(y + 1), shadowColor, false);
        }
        context.drawText(vanillaRenderer, text, (int)x, (int)y, color, false);
    }

    /**
     * Draw centered text. Width is cached.
     */
    public void drawCenteredText(DrawContext context, String text, float centerX, float y,
                                  int color, boolean shadow) {
        float x = centerX - (getStringWidth(text) / 2.0f);
        drawText(context, text, x, y, color, shadow);
    }

    /**
     * Draw text with an 8-direction outline — used for visibility on any background.
     * More expensive than shadow but much more readable.
     */
    public void drawOutlinedText(DrawContext context, String text, float x, float y,
                                  int color, int outlineColor) {
        int ox = (int)x, oy = (int)y;
        // 8 directions
        context.drawText(vanillaRenderer, text, ox - 1, oy - 1, outlineColor, false);
        context.drawText(vanillaRenderer, text, ox,     oy - 1, outlineColor, false);
        context.drawText(vanillaRenderer, text, ox + 1, oy - 1, outlineColor, false);
        context.drawText(vanillaRenderer, text, ox - 1, oy,     outlineColor, false);
        context.drawText(vanillaRenderer, text, ox + 1, oy,     outlineColor, false);
        context.drawText(vanillaRenderer, text, ox - 1, oy + 1, outlineColor, false);
        context.drawText(vanillaRenderer, text, ox,     oy + 1, outlineColor, false);
        context.drawText(vanillaRenderer, text, ox + 1, oy + 1, outlineColor, false);
        // Foreground
        context.drawText(vanillaRenderer, text, ox, oy, color, false);
    }

    // ─── Background boxes ────────────────────────────────────────────────────

    /**
     * Draw a rounded background box behind text.
     * padding: pixels added around the text bounds.
     * Used by virtually all HUD modules.
     */
    public void drawTextBackground(DrawContext context, String text, float x, float y,
                                    int padding, int backgroundColor) {
        int w = getStringWidth(text);
        int h = 8; // Vanilla font height
        context.fill(
            (int)(x - padding),
            (int)(y - padding),
            (int)(x + w + padding),
            (int)(y + h + padding),
            backgroundColor
        );
    }

    /**
     * Draw a background box of explicit dimensions.
     * Supports gradient (top/bottom color) for a more modern look.
     */
    public void drawBox(DrawContext context, float x, float y, float w, float h,
                        int color) {
        context.fill((int)x, (int)y, (int)(x + w), (int)(y + h), color);
    }

    public void drawGradientBox(DrawContext context, float x, float y, float w, float h,
                                 int colorTop, int colorBottom) {
        context.fillGradient((int)x, (int)y, (int)(x + w), (int)(y + h), colorTop, colorBottom);
    }

    /**
     * Draw a 1px border around a box region.
     */
    public void drawBorder(DrawContext context, float x, float y, float w, float h,
                            int borderColor) {
        int xi = (int)x, yi = (int)y, wi = (int)w, hi = (int)h;
        context.fill(xi,          yi,      xi + wi,     yi + 1,  borderColor); // top
        context.fill(xi,          yi + hi - 1, xi + wi, yi + hi, borderColor); // bottom
        context.fill(xi,          yi,      xi + 1,      yi + hi, borderColor); // left
        context.fill(xi + wi - 1, yi,      xi + wi,     yi + hi, borderColor); // right
    }

    // ─── Width caching ───────────────────────────────────────────────────────

    /**
     * Get string width in pixels with open-addressing cache.
     * Cache hit: O(1), no allocation.
     * Cache miss: delegates to TextRenderer.getWidth(), stores result.
     */
    public int getStringWidth(String text) {
        if (text == null || text.isEmpty()) return 0;

        long hash = text.hashCode() & 0xFFFFFFFFL; // Unsigned
        int slot = (int)(hash & CACHE_MASK);

        if (cacheKeys[slot] == hash) {
            return cacheValues[slot];
        }

        int width = vanillaRenderer.getWidth(text);
        cacheKeys[slot] = hash;
        cacheValues[slot] = width;
        return width;
    }

    /** Font height in pixels (vanilla = 9 with shadow, 8 without) */
    public int getFontHeight() {
        return vanillaRenderer.fontHeight;
    }

    // ─── Color helpers ───────────────────────────────────────────────────────

    public static int argb(int a, int r, int g, int b) {
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    /** Scale alpha channel by a factor [0.0, 1.0] */
    public static int scaleAlpha(int color, float factor) {
        int a = (int)(((color >>> 24) & 0xFF) * factor);
        return (color & 0x00FFFFFF) | (a << 24);
    }

    /** Linearly interpolate between two ARGB colors */
    public static int lerpColor(int c1, int c2, float t) {
        int a1 = (c1 >>> 24) & 0xFF, r1 = (c1 >>> 16) & 0xFF;
        int g1 = (c1 >>> 8)  & 0xFF, b1 = c1 & 0xFF;
        int a2 = (c2 >>> 24) & 0xFF, r2 = (c2 >>> 16) & 0xFF;
        int g2 = (c2 >>> 8)  & 0xFF, b2 = c2 & 0xFF;
        return argb(
            (int)(a1 + (a2 - a1) * t),
            (int)(r1 + (r2 - r1) * t),
            (int)(g1 + (g2 - g1) * t),
            (int)(b1 + (b2 - b1) * t)
        );
    }

    public TextRenderer getVanillaRenderer() { return vanillaRenderer; }
}
