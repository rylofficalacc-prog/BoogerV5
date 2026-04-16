package dev.booger.client.render.font;

import dev.booger.client.BoogerClient;
import net.minecraft.client.gui.DrawContext;

/**
 * Atlas-based text renderer — wraps GlyphCache for use by BoogerFontRenderer.
 *
 * INTEGRATION STRATEGY:
 * BoogerFontRenderer currently wraps vanilla TextRenderer (Phase 3).
 * AtlasTextRenderer sits alongside it. BoogerFontRenderer checks
 * atlasRenderer.isReady() and routes to it when available.
 *
 * This means the GPU path activates transparently once the atlas is built,
 * with zero changes to any module code. Modules call the same
 * BoogerFontRenderer.drawText() API regardless of which renderer is active.
 *
 * FRAME LIFECYCLE:
 * 1. HudRenderer.onRenderHud() begins
 * 2. Modules call BoogerFontRenderer.drawText() → routes to AtlasTextRenderer.queue()
 * 3. AtlasTextRenderer.queue() → GlyphCache.drawString() (appends to buffer)
 * 4. After all modules render, HudRenderer calls atlasRenderer.flush()
 * 5. flush() → GlyphCache.flush() → ONE glDrawArrays
 * 6. Buffer cleared, ready for next frame
 *
 * WHY NOT INTEGRATE DIRECTLY INTO HUDRENDERER:
 * Keeping flush() separate from the module render loop means we can add
 * non-HUD text (world-space labels, chat overlay, etc.) to the same batch
 * in Phase 5 by simply calling queue() from those systems before flush().
 *
 * SHADOW RENDERING:
 * Shadow is a second queue() call with the shadow color at (x+1, y+1).
 * Both the main glyph and its shadow go into the SAME batch.
 * Shadow always renders before main (added first to buffer = earlier in draw order).
 * Net cost: 2× vertex data, 0× extra GL calls.
 *
 * METRICS / PROFILING:
 * We track quads and flushes per frame for the FpsModule debug display.
 */
public final class AtlasTextRenderer {

    private final GlyphCache glyphCache;

    // Per-frame metrics
    private int frameQuads = 0;
    private int totalFlushes = 0;
    private long lastFlushNs = 0;
    private long flushTimeNs = 0;

    // Shadow color — translucent black (60% alpha of whatever the text color is)
    private static final float SHADOW_ALPHA_FACTOR = 0.5f;

    public AtlasTextRenderer() {
        this.glyphCache = new GlyphCache();
    }

    /**
     * Must be called from render thread after GL context is ready.
     */
    public void init() {
        glyphCache.init();
    }

    public boolean isReady() {
        return glyphCache.isInitialized();
    }

    /**
     * Queue a string for rendering with optional shadow.
     * Does NOT call GL — appends to frame vertex buffer.
     *
     * @param text   String to render
     * @param x      Screen X (top-left)
     * @param y      Screen Y (top of line)
     * @param argb   Packed ARGB color
     * @param shadow Whether to draw a drop shadow
     */
    public void queue(String text, float x, float y, int argb, boolean shadow) {
        if (shadow) {
            // Shadow: same alpha channel, but RGB = 0 (black), alpha scaled down
            int a = (argb >>> 24) & 0xFF;
            int shadowArgb = (int)(a * SHADOW_ALPHA_FACTOR) << 24; // ARGB with black RGB
            glyphCache.drawString(text, x + 1, y + 1, shadowArgb);
        }
        glyphCache.drawString(text, x, y, argb);
        frameQuads += text.length() * (shadow ? 2 : 1);
    }

    /**
     * Flush all queued text to GPU in one draw call.
     * Called once per frame by HudRenderer after all modules have rendered.
     */
    public void flush() {
        long start = System.nanoTime();
        glyphCache.flush();
        flushTimeNs = System.nanoTime() - start;
        totalFlushes++;
        frameQuads = 0;
    }

    /**
     * Measure text width using atlas glyph metrics.
     * More accurate than vanilla TextRenderer for our font.
     */
    public float measureWidth(String text) {
        return glyphCache.measureWidth(text);
    }

    public int getLineHeight() {
        return glyphCache.getLineHeight();
    }

    // ─── Profiling accessors (for FpsModule debug mode) ──────────────────────

    /** Quads queued this frame (before flush) */
    public int getFrameQuads() { return frameQuads; }

    /** Total flush calls since init */
    public int getTotalFlushes() { return totalFlushes; }

    /** Last flush duration in microseconds */
    public long getFlushTimeMicros() { return flushTimeNs / 1000; }

    public void destroy() {
        glyphCache.destroy();
    }
}
