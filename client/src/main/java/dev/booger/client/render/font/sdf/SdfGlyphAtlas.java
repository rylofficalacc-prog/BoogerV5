package dev.booger.client.render.font.sdf;

import dev.booger.client.BoogerClient;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

/**
 * Signed Distance Field glyph atlas.
 *
 * WHY SDF OVER RASTER ATLAS (Phase 4 GlyphCache):
 *
 * The Phase 4 atlas bakes glyphs at a fixed pixel size (20pt).
 * When the HUD scale is 1.5× or the user has a 4K monitor, those
 * pre-baked glyphs get upscaled — they blur. The only fix is baking at
 * a larger size, which wastes atlas space.
 *
 * SDF solves this fundamentally:
 * Instead of storing pixel colors, each texel stores a DISTANCE VALUE —
 * "how far am I from the nearest glyph edge, in pixels?"
 * The fragment shader reconstructs the glyph boundary at any scale by
 * thresholding on 0.5 (the "exactly on the edge" distance).
 *
 * Result: one 512×512 atlas renders crisply at 0.5× (tiny) through 8×
 * (massive) with a SINGLE texture sample per fragment.
 * No blurring. No pixelation. Infinitely scalable glyphs.
 *
 * ADDITIONAL SDF CAPABILITIES (zero extra cost):
 * - Outline: threshold at 0.5 - outlineWidth instead of 0.5
 * - Soft shadow: sample SDF at offset, alpha = smoothstep around 0.4
 * - Glow: multiply alpha by SDF value beyond 0.5
 * - Bold: threshold at 0.45 instead of 0.5 (fills glyph slightly)
 * All of these are shader-only — no texture changes, no re-upload.
 *
 * SDF GENERATION — THE 8SSEDT ALGORITHM:
 * 8SSEDT (8-point Signed Sequential Euclidean Distance Transform) is
 * the standard algorithm for SDF generation from bitmap glyphs.
 * It runs in O(n) time (two passes over the image), far faster than
 * naive O(n²) brute-force distance calculation.
 *
 * Steps:
 * 1. Rasterize glyph at HIGH resolution (128pt, 4× our display size)
 *    into a binary bitmap (on/off per pixel)
 * 2. Run 8SSEDT forward pass (top-left to bottom-right)
 * 3. Run 8SSEDT backward pass (bottom-right to top-left)
 * 4. Normalize distances to [0,1] range and pack into GL_R8 (1 byte/texel)
 * 5. Downsample the high-res SDF to atlas cell size (32×32 per glyph)
 *    Downsampling SDF data (not pixel data) preserves edge accuracy
 * 6. Pack into atlas texture row by row
 *
 * ATLAS FORMAT:
 * GL_R8 single-channel texture (1 byte per texel vs 4 bytes RGBA in Phase 4)
 * 75% less VRAM and texture cache usage.
 * 512×512 = 256KB vs 1MB for RGBA atlas.
 *
 * SHADER INTEGRATION:
 * SdfTextShader.glsl reads the R channel and applies:
 *   float dist = texture(u_atlas, v_uv).r;
 *   float alpha = smoothstep(0.5 - u_smoothing, 0.5 + u_smoothing, dist);
 *   fragColor = vec4(u_color.rgb, u_color.a * alpha);
 * u_smoothing scales with font size: smaller = sharper, larger = softer/anti-aliased
 *
 * CELL LAYOUT:
 * 512px wide, 32px cell width → 16 columns
 * 512px tall, 32px cell height → 16 rows
 * 16×16 = 256 cells — covers full ASCII (95) + common Unicode (161 extra)
 */
public final class SdfGlyphAtlas {

    // Atlas dimensions
    private static final int ATLAS_W    = 512;
    private static final int ATLAS_H    = 512;
    private static final int CELL_W     = 32;
    private static final int CELL_H     = 32;
    private static final int COLS       = ATLAS_W / CELL_W; // 16
    private static final int ROWS       = ATLAS_H / CELL_H; // 16

    // SDF generation resolution multiplier
    // Render at 4× cell size, then downsample → preserves sub-pixel edge accuracy
    private static final int SDF_SCALE  = 4;
    private static final int SDF_W      = CELL_W * SDF_SCALE; // 128px
    private static final int SDF_H      = CELL_H * SDF_SCALE; // 128px

    // Search radius for distance transform (in SDF-space pixels)
    // Larger = smoother edges at small sizes, more blur at large sizes
    private static final float SEARCH_RADIUS = 8.0f;

    // ASCII range
    private static final int FIRST_CHAR = 32;
    private static final int LAST_CHAR  = 127 + 128; // ASCII + Latin-1 Supplement

    // Glyph UV and metric data
    public record GlyphData(
        float u0, float v0,  // Atlas UV top-left
        float u1, float v1,  // Atlas UV bottom-right
        float advance,       // Cursor advance in em units
        float bearingX,      // Left bearing in em units
        float bearingY,      // Top bearing from baseline in em units
        float width,         // Glyph width in em units
        float height         // Glyph height in em units
    ) {}

    private final Map<Integer, GlyphData> glyphMap = new HashMap<>(512);

    private int textureId = -1;
    private float emSize;          // Pixels per em at reference size
    private boolean initialized = false;

    // Reference font for metrics
    private Font font;
    private static final int REFERENCE_PT = 16; // Display reference size

    public SdfGlyphAtlas() {}

    public void init() {
        if (initialized) return;

        try {
            long startNs = System.nanoTime();

            font = loadBestFont();
            emSize = (float) REFERENCE_PT;

            byte[] atlasData = generateAtlas();
            uploadToGL(atlasData);

            long ms = (System.nanoTime() - startNs) / 1_000_000;
            BoogerClient.LOGGER.info(
                "SdfGlyphAtlas: built {}×{} GL_R8 atlas in {}ms, {} glyphs, texture={}",
                ATLAS_W, ATLAS_H, ms, glyphMap.size(), textureId
            );
            initialized = true;

        } catch (Exception e) {
            BoogerClient.LOGGER.error("SdfGlyphAtlas init failed", e);
        }
    }

    /**
     * Generate the full SDF atlas as a flat byte array (GL_R8 format).
     */
    private byte[] generateAtlas() {
        byte[] atlas = new byte[ATLAS_W * ATLAS_H]; // One byte per texel

        FontMetrics fm = getFontMetrics(font, SDF_W);

        int glyphIdx = 0;
        for (int cp = FIRST_CHAR; cp < LAST_CHAR && glyphIdx < COLS * ROWS; cp++) {
            if (!font.canDisplay(cp)) continue;

            char c = (char) cp;
            int col = glyphIdx % COLS;
            int row = glyphIdx / COLS;
            int cellX = col * CELL_W;
            int cellY = row * CELL_H;

            // Step 1: Rasterize at SDF resolution
            boolean[] bitmap = rasterizeGlyph(c, fm);

            // Step 2: Compute SDF via 8SSEDT
            float[] sdf = computeSdf8SSEDT(bitmap, SDF_W, SDF_H);

            // Step 3: Downsample SDF into atlas cell
            downsampleSdf(sdf, SDF_W, SDF_H, atlas, ATLAS_W, cellX, cellY, CELL_W, CELL_H);

            // Step 4: Record glyph metrics
            float advance = fm.charWidth(c) / (float) SDF_W;
            float bx = 1.0f / SDF_W;
            float by = (float) fm.getAscent() / SDF_H;

            glyphMap.put(cp, new GlyphData(
                (float) cellX / ATLAS_W,
                (float) cellY / ATLAS_H,
                (float)(cellX + CELL_W) / ATLAS_W,
                (float)(cellY + CELL_H) / ATLAS_H,
                advance * emSize,
                bx * emSize,
                by * emSize,
                (float) CELL_W / ATLAS_W * emSize,
                (float) CELL_H / ATLAS_H * emSize
            ));

            glyphIdx++;
        }

        return atlas;
    }

    /**
     * Rasterize a single character into a binary bitmap at SDF resolution.
     */
    private boolean[] rasterizeGlyph(char c, FontMetrics fm) {
        BufferedImage img = new BufferedImage(SDF_W, SDF_H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);

        Font scaledFont = font.deriveFont(Font.PLAIN, (float)(SDF_H * 0.75));
        g.setFont(scaledFont);
        g.setColor(Color.WHITE);
        g.setBackground(new Color(0, 0, 0, 0));
        g.clearRect(0, 0, SDF_W, SDF_H);

        FontMetrics scaledFm = g.getFontMetrics();
        int baseline = (int)(SDF_H * 0.8);
        int bearingX = 2;
        g.drawString(String.valueOf(c), bearingX, baseline);
        g.dispose();

        // Convert to binary: pixel alpha > 127 → inside glyph
        boolean[] bitmap = new boolean[SDF_W * SDF_H];
        int[] pixels = img.getRGB(0, 0, SDF_W, SDF_H, null, 0, SDF_W);
        for (int i = 0; i < pixels.length; i++) {
            bitmap[i] = ((pixels[i] >>> 24) & 0xFF) > 127;
        }
        return bitmap;
    }

    /**
     * 8-point Signed Sequential Euclidean Distance Transform.
     *
     * Algorithm credit: Meijster et al. "A General Algorithm for Computing
     * Distance Transforms in Linear Time" (adapted for 8-connectivity).
     *
     * Two-pass forward/backward scan. Time complexity: O(W×H).
     * Returns normalized signed distances in [-1, 1]:
     *   +1.0 = deep inside glyph
     *    0.5 = exactly on glyph edge
     *   -1.0 = far outside glyph (background)
     *
     * We use separate outside/inside distance fields and combine them:
     * signed_dist = inside_dist - outside_dist (normalized)
     * Then remap to [0, 1]: stored = 0.5 + 0.5 * signed_dist / SEARCH_RADIUS
     */
    private float[] computeSdf8SSEDT(boolean[] bitmap, int w, int h) {
        // Distance fields: squared Euclidean distance to nearest opposite pixel
        float[] outsideDist = new float[w * h]; // Distance from background pixel to nearest inside
        float[] insideDist  = new float[w * h]; // Distance from inside pixel to nearest background

        float INF = (SEARCH_RADIUS + 1) * (SEARCH_RADIUS + 1);

        // Initialize
        for (int i = 0; i < w * h; i++) {
            outsideDist[i] = bitmap[i] ? 0 : INF;
            insideDist[i]  = bitmap[i] ? INF : 0;
        }

        // Run EDT on both fields
        edt2D(outsideDist, w, h);
        edt2D(insideDist, w, h);

        // Combine into signed distance, normalize to [0, 1]
        float[] result = new float[w * h];
        for (int i = 0; i < w * h; i++) {
            float outside = (float) Math.sqrt(outsideDist[i]);
            float inside  = (float) Math.sqrt(insideDist[i]);
            float signed  = inside - outside; // Positive inside, negative outside
            // Normalize: map [-SEARCH_RADIUS, +SEARCH_RADIUS] → [0, 1]
            result[i] = 0.5f + 0.5f * Math.max(-SEARCH_RADIUS,
                                      Math.min(SEARCH_RADIUS, signed)) / SEARCH_RADIUS;
        }
        return result;
    }

    /**
     * 2D Euclidean Distance Transform using the separable algorithm.
     * Modifies the array in-place. Input: 0 = source pixel, INF = empty.
     * Output: squared Euclidean distance to nearest source pixel.
     */
    private void edt2D(float[] grid, int w, int h) {
        float[] row = new float[Math.max(w, h)];
        float[] tmp = new float[Math.max(w, h)];

        // Row pass (x direction)
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) row[x] = grid[y * w + x];
            edt1D(row, tmp, w);
            for (int x = 0; x < w; x++) grid[y * w + x] = row[x];
        }

        // Column pass (y direction)
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) row[y] = grid[y * w + x];
            edt1D(row, tmp, h);
            for (int y = 0; y < h; y++) grid[y * w + x] = row[y];
        }
    }

    /**
     * 1D EDT via parabola envelope method (Felzenszwalb & Huttenlocher 2012).
     * O(n) time. Source pixels have value 0, empty pixels have value INF.
     */
    private void edt1D(float[] f, float[] d, int n) {
        int[] v = new int[n];
        float[] z = new float[n + 1];
        int k = 0;
        v[0] = 0;
        z[0] = Float.NEGATIVE_INFINITY;
        z[1] = Float.POSITIVE_INFINITY;

        for (int q = 1; q < n; q++) {
            float s;
            do {
                int r = v[k];
                s = ((f[q] + q * q) - (f[r] + r * r)) / (2.0f * (q - r));
            } while (s <= z[k] && --k >= 0);
            k++;
            v[k] = q;
            z[k] = s;
            z[k + 1] = Float.POSITIVE_INFINITY;
        }

        k = 0;
        for (int q = 0; q < n; q++) {
            while (z[k + 1] < q) k++;
            int r = v[k];
            float dx = q - r;
            d[q] = dx * dx + f[r];
        }
        System.arraycopy(d, 0, f, 0, n);
    }

    /**
     * Area-average downsample from high-res SDF into atlas cell.
     * Area averaging preserves the signed distance meaning better than
     * bilinear interpolation when downscaling by 4×.
     */
    private void downsampleSdf(float[] sdf, int sdfW, int sdfH,
                                byte[] atlas, int atlasW,
                                int cellX, int cellY, int cellW, int cellH) {
        float scaleX = (float) sdfW / cellW;
        float scaleY = (float) sdfH / cellH;

        for (int cy = 0; cy < cellH; cy++) {
            for (int cx = 0; cx < cellW; cx++) {
                // Source region in SDF space
                int sx0 = (int)(cx * scaleX);
                int sy0 = (int)(cy * scaleY);
                int sx1 = Math.min(sdfW, (int)((cx + 1) * scaleX));
                int sy1 = Math.min(sdfH, (int)((cy + 1) * scaleY));

                // Area average
                float sum = 0;
                int count = 0;
                for (int sy = sy0; sy < sy1; sy++) {
                    for (int sx = sx0; sx < sx1; sx++) {
                        sum += sdf[sy * sdfW + sx];
                        count++;
                    }
                }
                float avg = count > 0 ? sum / count : 0.5f;

                // Pack to [0, 255]
                int atlasIdx = (cellY + cy) * atlasW + (cellX + cx);
                atlas[atlasIdx] = (byte)(Math.max(0, Math.min(255, (int)(avg * 255.0f))));
            }
        }
    }

    private void uploadToGL(byte[] atlasData) {
        textureId = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);

        // Linear filtering — critical for SDF (nearest would alias at edges)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

        ByteBuffer buf = ByteBuffer.allocateDirect(atlasData.length)
            .order(ByteOrder.nativeOrder());
        buf.put(atlasData).flip();

        // GL_R8: single channel, 1 byte per texel — 75% less VRAM vs RGBA
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R8,
            ATLAS_W, ATLAS_H, 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, buf);

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    }

    private Font loadBestFont() {
        try {
            // Prefer a clean geometric sans — Inter is ideal for SDF rendering
            // because its curves are designed to be mathematically clean
            Font candidate = new Font("Inter", Font.PLAIN, REFERENCE_PT);
            if (!candidate.getFamily().equals("Dialog")) return candidate;
        } catch (Exception ignored) {}

        try {
            Font candidate = new Font("Segoe UI", Font.PLAIN, REFERENCE_PT);
            if (!candidate.getFamily().equals("Dialog")) return candidate;
        } catch (Exception ignored) {}

        return new Font(Font.SANS_SERIF, Font.PLAIN, REFERENCE_PT);
    }

    private FontMetrics getFontMetrics(Font f, int renderSize) {
        BufferedImage tmp = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = tmp.createGraphics();
        g.setFont(f.deriveFont(Font.PLAIN, (float) renderSize));
        FontMetrics fm = g.getFontMetrics();
        g.dispose();
        return fm;
    }

    // ─── Public API ─────────────────────────────────────────────────────────

    public GlyphData getGlyph(int codePoint) {
        return glyphMap.get(codePoint);
    }

    public int getTextureId() { return textureId; }
    public boolean isInitialized() { return initialized; }
    public float getEmSize() { return emSize; }

    public void destroy() {
        if (textureId != -1) {
            GL11.glDeleteTextures(textureId);
            textureId = -1;
        }
        initialized = false;
    }
}
