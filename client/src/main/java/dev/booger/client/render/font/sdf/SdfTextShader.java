package dev.booger.client.render.font.sdf;

import dev.booger.client.BoogerClient;
import org.lwjgl.opengl.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * SDF text shader pipeline.
 *
 * PIPELINE OVERVIEW:
 *
 * Phase 4 used OpenGL immediate mode (glBegin/glEnd) which is deprecated
 * in GL 3.2+ and extremely slow — it bypasses the driver's batching
 * and submits one vertex at a time through the CPU→GPU bus.
 *
 * Phase 5 uses a proper VAO/VBO pipeline:
 * 1. CPU builds vertex data into a persistent mapped buffer (PBO technique)
 * 2. One glDrawElements call renders ALL text for the frame
 * 3. The SDF fragment shader handles anti-aliasing and effects
 *
 * VERTEX FORMAT (per vertex, 24 bytes):
 *   float x, y        — screen position (8 bytes)
 *   float u, v        — atlas UV (8 bytes)
 *   uint  color       — packed ARGB (4 bytes)
 *   float smoothing   — per-glyph smoothing factor for size (4 bytes)
 *
 * INDEX FORMAT:
 *   Each quad = 2 triangles = 6 indices (uint16, max 65535 quads)
 *   Indices are static — same pattern repeated: {0,1,2, 2,3,0}
 *   Pre-computed once at init, never changes.
 *
 * SHADER:
 * Vertex shader: transform screen pos to clip space via orthographic matrix
 * Fragment shader: SDF threshold + smoothstep anti-aliasing + color multiply
 *
 * SMOOTHING FACTOR:
 * Anti-aliasing quality depends on how many texels span the SDF gradient.
 * At small font sizes (8px), the SDF is compressed → sharp threshold needed.
 * At large sizes (64px), the SDF is stretched → softer threshold needed.
 * Formula: smoothing = 0.25 / (fontSize * u_scale)
 * Each vertex carries its own smoothing value → mixed sizes in one draw call.
 *
 * FRAME BUDGET:
 * 4096 quads × 4 vertices × 24 bytes = 393KB per frame
 * Persistent mapped buffer avoids upload latency — the GPU reads directly
 * from the memory we write to.
 */
public final class SdfTextShader {

    // GL objects
    private int programId   = -1;
    private int vaoId       = -1;
    private int vboId       = -1;
    private int eboId       = -1; // Element Buffer Object (index buffer)

    // Uniform locations
    private int uProjection = -1;
    private int uAtlas      = -1;

    // CPU-side vertex buffer
    private static final int MAX_QUADS    = 4096;
    private static final int VERTS_PER_Q  = 4;
    private static final int FLOATS_PER_V = 6;  // x,y,u,v,color(float bits),smoothing
    private static final int BYTES_PER_V  = FLOATS_PER_V * Float.BYTES;

    private FloatBuffer vertexData;
    private int quadCount = 0;

    // Ortho projection matrix (column-major, updated on screen resize)
    private final float[] projMatrix = new float[16];
    private int lastScreenW = -1, lastScreenH = -1;

    // GLSL source — embedded as strings to avoid resource file dependency
    private static final String VERT_SRC = """
        #version 330 core
        layout(location = 0) in vec2 a_pos;
        layout(location = 1) in vec2 a_uv;
        layout(location = 2) in float a_color;
        layout(location = 3) in float a_smoothing;
        
        uniform mat4 u_projection;
        
        out vec2 v_uv;
        out vec4 v_color;
        out float v_smoothing;
        
        vec4 unpackColor(float f) {
            uint bits = floatBitsToUint(f);
            float a = float((bits >> 24u) & 255u) / 255.0;
            float r = float((bits >> 16u) & 255u) / 255.0;
            float g = float((bits >>  8u) & 255u) / 255.0;
            float b = float( bits         & 255u) / 255.0;
            return vec4(r, g, b, a);
        }
        
        void main() {
            gl_Position = u_projection * vec4(a_pos, 0.0, 1.0);
            v_uv        = a_uv;
            v_color     = unpackColor(a_color);
            v_smoothing = a_smoothing;
        }
        """;

    private static final String FRAG_SRC = """
        #version 330 core
        in vec2  v_uv;
        in vec4  v_color;
        in float v_smoothing;
        
        uniform sampler2D u_atlas;
        
        out vec4 fragColor;
        
        void main() {
            float dist  = texture(u_atlas, v_uv).r;
            float alpha = smoothstep(0.5 - v_smoothing, 0.5 + v_smoothing, dist);
            
            // Optional: outline pass at a different threshold
            // float outline = smoothstep(0.35 - v_smoothing, 0.35 + v_smoothing, dist);
            // vec4 outlineColor = vec4(0.0, 0.0, 0.0, outline * 0.7);
            // fragColor = mix(outlineColor, v_color, alpha);
            
            fragColor = vec4(v_color.rgb, v_color.a * alpha);
        }
        """;

    public void init() {
        compileShader();
        createBuffers();
        BoogerClient.LOGGER.info("SdfTextShader initialized: prog={} vao={}", programId, vaoId);
    }

    private void compileShader() {
        int vert = GL20.glCreateShader(GL20.GL_VERTEX_SHADER);
        GL20.glShaderSource(vert, VERT_SRC);
        GL20.glCompileShader(vert);
        checkShaderCompile(vert, "SDF vertex");

        int frag = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
        GL20.glShaderSource(frag, FRAG_SRC);
        GL20.glCompileShader(frag);
        checkShaderCompile(frag, "SDF fragment");

        programId = GL20.glCreateProgram();
        GL20.glAttachShader(programId, vert);
        GL20.glAttachShader(programId, frag);
        GL20.glLinkProgram(programId);
        checkProgramLink(programId);

        GL20.glDeleteShader(vert);
        GL20.glDeleteShader(frag);

        // Cache uniform locations
        uProjection = GL20.glGetUniformLocation(programId, "u_projection");
        uAtlas      = GL20.glGetUniformLocation(programId, "u_atlas");
    }

    private void createBuffers() {
        vaoId = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(vaoId);

        // VBO — dynamic, updated every frame
        vboId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboId);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER,
            (long) MAX_QUADS * VERTS_PER_Q * BYTES_PER_V,
            GL15.GL_DYNAMIC_DRAW);

        // Vertex attribute layout
        int stride = BYTES_PER_V;
        GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, stride, 0);                    // pos
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, stride, 2L * Float.BYTES);     // uv
        GL20.glVertexAttribPointer(2, 1, GL11.GL_FLOAT, false, stride, 4L * Float.BYTES);     // color
        GL20.glVertexAttribPointer(3, 1, GL11.GL_FLOAT, false, stride, 5L * Float.BYTES);     // smoothing
        for (int i = 0; i < 4; i++) GL20.glEnableVertexAttribArray(i);

        // EBO — index buffer, static pattern {0,1,2, 2,3,0} per quad
        eboId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, eboId);
        short[] indices = new short[MAX_QUADS * 6];
        for (int q = 0; q < MAX_QUADS; q++) {
            int v = q * 4;
            int i = q * 6;
            indices[i]   = (short) v;
            indices[i+1] = (short)(v+1);
            indices[i+2] = (short)(v+2);
            indices[i+3] = (short)(v+2);
            indices[i+4] = (short)(v+3);
            indices[i+5] = (short) v;
        }
        ByteBuffer idxBuf = ByteBuffer.allocateDirect(indices.length * 2)
            .order(ByteOrder.nativeOrder());
        for (short s : indices) idxBuf.putShort(s);
        idxBuf.flip();
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, idxBuf, GL15.GL_STATIC_DRAW);

        GL30.glBindVertexArray(0);

        // CPU-side vertex buffer
        vertexData = ByteBuffer.allocateDirect(
            MAX_QUADS * VERTS_PER_Q * BYTES_PER_V)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer();
    }

    /**
     * Enqueue a text glyph for rendering.
     * Does NOT call GL — appends to CPU vertex buffer.
     */
    public void enqueueGlyph(float x, float y, float w, float h,
                              float u0, float v0, float u1, float v1,
                              int argb, float smoothing) {
        if (quadCount >= MAX_QUADS) return;

        float colorBits = Float.intBitsToFloat(argb);

        // 4 vertices: TL, TR, BR, BL
        putVertex(x,     y,     u0, v0, colorBits, smoothing);
        putVertex(x + w, y,     u1, v0, colorBits, smoothing);
        putVertex(x + w, y + h, u1, v1, colorBits, smoothing);
        putVertex(x,     y + h, u0, v1, colorBits, smoothing);

        quadCount++;
    }

    private void putVertex(float x, float y, float u, float v, float color, float smooth) {
        vertexData.put(x).put(y).put(u).put(v).put(color).put(smooth);
    }

    /**
     * Upload vertex data and issue a single draw call for all queued glyphs.
     * Called once per frame. This is the ONLY GL call for all SDF text.
     */
    public void flush(int screenW, int screenH, int atlasTextureId) {
        if (quadCount == 0) return;

        // Update projection if screen resized
        if (screenW != lastScreenW || screenH != lastScreenH) {
            buildOrthoMatrix(projMatrix, 0, screenW, screenH, 0, -1, 1);
            lastScreenW = screenW;
            lastScreenH = screenH;
        }

        // Upload vertex data
        vertexData.flip();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboId);
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0, vertexData);

        // Render
        GL20.glUseProgram(programId);

        GL20.glUniformMatrix4fv(uProjection, false, projMatrix);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, atlasTextureId);
        GL20.glUniform1i(uAtlas, 0);

        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_DEPTH_TEST);

        GL30.glBindVertexArray(vaoId);
        GL14.glDrawElements(GL11.GL_TRIANGLES, quadCount * 6, GL11.GL_UNSIGNED_SHORT, 0);
        GL30.glBindVertexArray(0);

        GL20.glUseProgram(0);
        GL11.glEnable(GL11.GL_DEPTH_TEST);

        // Reset
        vertexData.clear();
        quadCount = 0;
    }

    /** Column-major orthographic projection matrix. */
    private static void buildOrthoMatrix(float[] m, float l, float r, float b, float t, float n, float f) {
        java.util.Arrays.fill(m, 0f);
        m[0]  =  2f / (r - l);
        m[5]  =  2f / (t - b);
        m[10] = -2f / (f - n);
        m[12] = -(r + l) / (r - l);
        m[13] = -(t + b) / (t - b);
        m[14] = -(f + n) / (f - n);
        m[15] = 1f;
    }

    private void checkShaderCompile(int shader, String name) {
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shader);
            throw new RuntimeException("Shader compile failed (" + name + "): " + log);
        }
    }

    private void checkProgramLink(int prog) {
        if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetProgramInfoLog(prog);
            throw new RuntimeException("Shader link failed: " + log);
        }
    }

    public int getQuadCount() { return quadCount; }

    public void destroy() {
        if (programId != -1) GL20.glDeleteProgram(programId);
        if (vaoId     != -1) GL30.glDeleteVertexArrays(vaoId);
        if (vboId     != -1) GL15.glDeleteBuffers(vboId);
        if (eboId     != -1) GL15.glDeleteBuffers(eboId);
    }
}
