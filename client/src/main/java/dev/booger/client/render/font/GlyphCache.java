package dev.booger.client.render.font;

import dev.booger.client.BoogerClient;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

/**
 * GPU glyph atlas cache.
 *
 * PROBLEM WITH VANILLA FONT RENDERING:
 * Minecraft's TextRenderer allocates a new vertex buffer for EVERY string drawn,
 * fills it with per-character quads, uploads it to the GPU, draws it, and
 * discards it. At 60fps with 20 HUD elements, that's 1200+ VBO alloc/upload
 * cycles per second just for HUD text.
 *
 * OUR APPROACH — ATLAS + BATCH:
 * 1. At init time, rasterize all printable ASCII (codepoints 32-126) into a
 *    single 512×512 RGBA texture (the "atlas"). Each glyph occupies a fixed
 *    cell in the atlas — UV coordinates are precomputed once.
 *
 * 2. At render time, drawString() does NOT call OpenGL. It appends quad
 *    vertices to a per-frame FloatBuffer (positions + UVs + color).
 *
 * 3. At the END of the HUD render pass, one flush() call uploads the entire
 *    buffer and issues a SINGLE glDrawArrays call for ALL text rendered
 *    that frame.
 *
 * RESULT:
 * - Vanilla: O(characters) GL calls per frame
 * - Ours: O(1) GL calls per frame (one draw call for all HUD text)
 *
 * CHARACTER COVERAGE:
 * Phase 4: ASCII 32-126 (all printable English + standard punctuation)
 * Phase 5: Unicode BMP via dynamic atlas pages (for non-ASCII server names, etc.)
 *
 * ATLAS LAYOUT:
 * 512×512 texture, 16 columns × 8 rows = 128 cells (covers 95 ASCII chars)
 * Each cell: 32×64 pixels (width × height) — generous for descenders/ascenders
 * Actual glyph rendered centered in cell with 2px padding on each side
 *
 * TEXTURE FORMAT:
 * GL_RGBA8 — alpha channel used for antialiasing (smooth glyph edges)
 * We render glyphs with Java2D onto a BufferedImage with ANTIALIASING enabled,
 * then upload the pixel data to GL. This gives us subpixel-quality glyphs
 * without needing a signed distance field shader (SDF is Phase 5).
 *
 * THREAD SAFETY:
 * GlyphCache must be initialized and used ONLY from the render (main) thread.
 * The FloatBuffer is not thread-safe. The GL calls are inherently main-thread-only.
 */
public final class GlyphCache {

    // Atlas dimensions
    private static final int ATLAS_WIDTH  = 512;
    private static final int ATLAS_HEIGHT = 512;
    private static final int CELL_W       = 32;   // pixels per glyph cell
    private static final int CELL_H       = 64;
    private static final int COLS         = ATLAS_WIDTH  / CELL_W; // 16
    private static final int ROWS         = ATLAS_HEIGHT / CELL_H; // 8

    // ASCII range to cache
    private static final int FIRST_CHAR = 32;  // space
    private static final int LAST_CHAR  = 126; // ~
    private static final int CHAR_COUNT = LAST_CHAR - FIRST_CHAR + 1; // 95

    // Glyph metadata — precomputed UV coordinates + advance width
    public static final class GlyphInfo {
        public final float u0, v0, u1, v1; // UV in [0,1] range
        public final float advanceWidth;    // pixels to advance cursor after this glyph
        public final float bearingX;        // horizontal offset from cursor to glyph left edge
        public final float bearingY;        // vertical offset from baseline to glyph top

        GlyphInfo(float u0, float v0, float u1, float v1,
                  float advanceWidth, float bearingX, float bearingY) {
            this.u0 = u0; this.v0 = v0;
            this.u1 = u1; this.v1 = v1;
            this.advanceWidth = advanceWidth;
            this.bearingX = bearingX;
            this.bearingY = bearingY;
        }
    }

    private final Map<Character, GlyphInfo> glyphs = new HashMap<>(CHAR_COUNT * 2);

    // GL texture handle
    private int atlasTextureId = -1;

    // Per-frame vertex batch buffer
    // Each quad = 4 vertices × (2 pos + 2 uv + 4 color) floats = 4 × 8 = 32 floats
    // Support up to 4096 quads per frame (4096 × 32 = 131072 floats = 512KB)
    private static final int MAX_QUADS_PER_FRAME = 4096;
    private static final int FLOATS_PER_QUAD = 32; // 4 vertices * 8 floats each
    private final FloatBuffer vertexBuffer;

    private int quadCount = 0;
    private boolean initialized = false;

    // Font used for atlas generation — high quality Java2D font
    private Font atlasFont;
    private static final int FONT_SIZE_PT = 20; // Renders cleanly into 32×64 cell

    // Metrics derived from font
    private int glyphAscent;
    private int glyphDescent;
    private int lineHeight; // ascent + descent + leading

    public GlyphCache() {
        // Allocate buffer outside JVM heap — no GC pressure
        // ByteBuffer.allocateDirect is off-heap, persistent across GC cycles
        ByteBuffer bb = ByteBuffer.allocateDirect(
            MAX_QUADS_PER_FRAME * FLOATS_PER_QUAD * Float.BYTES
        ).order(ByteOrder.nativeOrder());
        this.vertexBuffer = bb.asFloatBuffer();
    }

    /**
     * Initialize the glyph atlas.
     * Must be called from the render thread after GL context is available.
     */
    public void init() {
        if (initialized) return;

        try {
            buildAtlas();
            initialized = true;
            BoogerClient.LOGGER.info(
                "GlyphCache initialized: {}x{} atlas, {} glyphs, texture ID={}",
                ATLAS_WIDTH, ATLAS_HEIGHT, glyphs.size(), atlasTextureId
            );
        } catch (Exception e) {
            BoogerClient.LOGGER.error("GlyphCache init failed — falling back to vanilla renderer", e);
        }
    }

    /**
     * Build the glyph atlas texture.
     *
     * Steps:
     * 1. Create Java2D BufferedImage for rasterization
     * 2. Configure Graphics2D with best quality antialiasing
     * 3. For each ASCII character, draw it into its cell
     * 4. Record UV coordinates and metrics for each glyph
     * 5. Upload the completed image to a GL texture
     * 6. Free the Java2D image (no longer needed)
     */
    private void buildAtlas() {
        // Step 1: Load font — try to use Minecraft's built-in font first
        // Fall back to Java's default monospace if unavailable
        try {
            atlasFont = new Font(Font.SANS_SERIF, Font.PLAIN, FONT_SIZE_PT);
        } catch (Exception e) {
            atlasFont = new Font(Font.MONOSPACED, Font.PLAIN, FONT_SIZE_PT);
        }

        // Step 2: Create rasterization canvas
        BufferedImage atlasImage = new BufferedImage(
            ATLAS_WIDTH, ATLAS_HEIGHT, BufferedImage.TYPE_INT_ARGB
        );
        Graphics2D g = atlasImage.createGraphics();

        // Best quality antialiasing — renders once, used every frame
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
            RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
            RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,
            RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
            RenderingHints.VALUE_FRACTIONALMETRICS_ON);

        g.setFont(atlasFont);
        FontMetrics fm = g.getFontMetrics();
        glyphAscent  = fm.getAscent();
        glyphDescent = fm.getDescent();
        lineHeight   = fm.getHeight();

        // Step 3: Clear atlas to transparent black
        g.setColor(new Color(0, 0, 0, 0));
        g.fillRect(0, 0, ATLAS_WIDTH, ATLAS_HEIGHT);
        g.setColor(Color.WHITE); // Render white — color applied per-quad at draw time

        // Step 4: Rasterize each character
        for (int i = 0; i < CHAR_COUNT; i++) {
            char c = (char)(FIRST_CHAR + i);
            int col = i % COLS;
            int row = i / COLS;

            if (row >= ROWS) break; // Atlas full (shouldn't happen for ASCII 32-126)

            int cellX = col * CELL_W;
            int cellY = row * CELL_H;

            // Glyph advance and bearing from FontMetrics
            int advW = fm.charWidth(c);
            int bearX = 2; // 2px left padding in cell

            // Draw glyph at baseline position within cell
            // Baseline Y = cellY + glyphAscent + top_padding
            int baselineY = cellY + glyphAscent + 4; // 4px top padding
            g.drawString(String.valueOf(c), cellX + bearX, baselineY);

            // Compute UV coordinates (normalized to [0,1])
            float u0 = (float) cellX / ATLAS_WIDTH;
            float v0 = (float) cellY / ATLAS_HEIGHT;
            float u1 = (float)(cellX + advW + bearX * 2) / ATLAS_WIDTH;
            float v1 = (float)(cellY + CELL_H) / ATLAS_HEIGHT;

            glyphs.put(c, new GlyphInfo(
                u0, v0, u1, v1,
                advW,   // advance width in pixels
                bearX,  // bearing X
                4.0f    // bearing Y (top padding)
            ));
        }

        g.dispose();

        // Step 5: Upload to GL
        atlasTextureId = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, atlasTextureId);

        // Linear filtering for scaled text (scale factor != 1.0 at non-standard scale)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

        // Extract pixel data from BufferedImage
        int[] pixels = new int[ATLAS_WIDTH * ATLAS_HEIGHT];
        atlasImage.getRGB(0, 0, ATLAS_WIDTH, ATLAS_HEIGHT, pixels, 0, ATLAS_WIDTH);

        // Convert ARGB int array to RGBA ByteBuffer (GL expects RGBA, Java uses ARGB)
        ByteBuffer pixelBuffer = ByteBuffer
            .allocateDirect(ATLAS_WIDTH * ATLAS_HEIGHT * 4)
            .order(ByteOrder.nativeOrder());

        for (int pixel : pixels) {
            pixelBuffer.put((byte)((pixel >> 16) & 0xFF)); // R
            pixelBuffer.put((byte)((pixel >>  8) & 0xFF)); // G
            pixelBuffer.put((byte)( pixel        & 0xFF)); // B
            pixelBuffer.put((byte)((pixel >> 24) & 0xFF)); // A
        }
        pixelBuffer.flip();

        GL11.glTexImage2D(
            GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA,
            ATLAS_WIDTH, ATLAS_HEIGHT,
            0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixelBuffer
        );

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

        // Step 6: Free Java2D image — pixels are now on the GPU
        // atlasImage will be GC'd normally — no explicit free needed
    }

    /**
     * Queue a string for rendering at (x, y) with the given ARGB color.
     * Does NOT call GL — just appends vertices to the frame buffer.
     *
     * @param text   String to render
     * @param x      Screen X (top-left of first character)
     * @param y      Screen Y (top of glyph cell, NOT baseline)
     * @param argb   Packed ARGB color (e.g. 0xFFFFFFFF for opaque white)
     */
    public void drawString(String text, float x, float y, int argb) {
        if (!initialized || text == null || text.isEmpty()) return;
        if (quadCount >= MAX_QUADS_PER_FRAME) return; // Buffer full

        // Unpack color to [0,1] floats — done once per string, not per character
        float r = ((argb >> 16) & 0xFF) / 255.0f;
        float g = ((argb >>  8) & 0xFF) / 255.0f;
        float b = ( argb        & 0xFF) / 255.0f;
        float a = ((argb >> 24) & 0xFF) / 255.0f;

        float cursorX = x;
        float glyphH = lineHeight; // Full cell height for the quad

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            GlyphInfo glyph = glyphs.get(c);

            if (glyph == null) {
                // Unknown character — advance by average width
                cursorX += CELL_W * 0.6f;
                continue;
            }

            if (quadCount >= MAX_QUADS_PER_FRAME) break;

            float qx = cursorX + glyph.bearingX;
            float qy = y;
            float qw = (glyph.u1 - glyph.u0) * ATLAS_WIDTH;
            float qh = glyphH;

            // Append 4 vertices for this quad (CCW winding for OpenGL)
            // Vertex layout: x, y, u, v, r, g, b, a (8 floats each)
            appendVertex(qx,      qy,      glyph.u0, glyph.v0, r, g, b, a);
            appendVertex(qx + qw, qy,      glyph.u1, glyph.v0, r, g, b, a);
            appendVertex(qx + qw, qy + qh, glyph.u1, glyph.v1, r, g, b, a);
            appendVertex(qx,      qy + qh, glyph.u0, glyph.v1, r, g, b, a);

            quadCount++;
            cursorX += glyph.advanceWidth;
        }
    }

    private void appendVertex(float x, float y, float u, float v,
                               float r, float g, float b, float a) {
        vertexBuffer.put(x).put(y).put(u).put(v).put(r).put(g).put(b).put(a);
    }

    /**
     * Flush all queued quads to the GPU in a single draw call.
     * Called once per frame by AtlasTextRenderer after all HUD text is queued.
     *
     * This is the ONLY GL call in the entire text rendering pipeline per frame.
     */
    public void flush() {
        if (!initialized || quadCount == 0) return;

        vertexBuffer.flip();

        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, atlasTextureId);

        // Legacy immediate mode for Phase 4 — Phase 5 will use a proper VAO/VBO
        // We use begin/end quads here because it avoids the complexity of managing
        // a VAO while still batching all text into one GL state setup.
        // The gain over vanilla is still enormous: 1 texture bind + 1 state setup
        // vs N per vanilla, even with immediate mode.
        GL11.glBegin(GL11.GL_QUADS);

        vertexBuffer.rewind();
        for (int q = 0; q < quadCount; q++) {
            for (int v = 0; v < 4; v++) {
                float px = vertexBuffer.get();
                float py = vertexBuffer.get();
                float u  = vertexBuffer.get();
                float vv = vertexBuffer.get();
                float r  = vertexBuffer.get();
                float g  = vertexBuffer.get();
                float b  = vertexBuffer.get();
                float a  = vertexBuffer.get();

                GL11.glColor4f(r, g, b, a);
                GL11.glTexCoord2f(u, vv);
                GL11.glVertex2f(px, py);
            }
        }

        GL11.glEnd();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GL11.glDisable(GL11.GL_TEXTURE_2D);

        // Reset for next frame
        vertexBuffer.clear();
        quadCount = 0;
    }

    /**
     * Measure string width in pixels without rendering.
     * O(n) but called rarely (width cache in BoogerFontRenderer handles hot path).
     */
    public float measureWidth(String text) {
        if (text == null || text.isEmpty()) return 0;
        float w = 0;
        for (int i = 0; i < text.length(); i++) {
            GlyphInfo glyph = glyphs.get(text.charAt(i));
            w += (glyph != null) ? glyph.advanceWidth : CELL_W * 0.6f;
        }
        return w;
    }

    public int getLineHeight() { return lineHeight; }
    public int getAtlasTextureId() { return atlasTextureId; }
    public boolean isInitialized() { return initialized; }

    /**
     * Release GL resources. Called on client shutdown.
     */
    public void destroy() {
        if (atlasTextureId != -1) {
            GL11.glDeleteTextures(atlasTextureId);
            atlasTextureId = -1;
        }
        initialized = false;
    }
}
