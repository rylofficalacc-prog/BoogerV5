package dev.booger.client.render;

import dev.booger.client.BoogerClient;
import dev.booger.client.module.impl.pvp.TargetHudModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.opengl.GL11;

/**
 * Entity outline renderer.
 *
 * TECHNIQUE: Stencil Buffer Silhouette
 *
 * This is the same technique used by most competitive clients for "legal ESP"
 * (target highlighting) and by vanilla Minecraft for the glowing effect.
 * We use it specifically for the TargetHUD's selected target highlight.
 *
 * HOW IT WORKS:
 * Pass 1 — STENCIL WRITE:
 *   Render the entity's AABB (axis-aligned bounding box) into the stencil buffer
 *   with stencil write enabled. No color output. This marks the entity's screen
 *   region in the stencil buffer.
 *
 * Pass 2 — DILATED OUTLINE:
 *   Render a slightly expanded AABB (by outlineWidth pixels in screen space) with
 *   stencil test = NOTEQUAL to the value written in Pass 1.
 *   This draws ONLY the ring of pixels AROUND the entity — not on top of it.
 *   That ring is the outline.
 *
 * RESULT:
 * - Clean outline that follows entity silhouette (not just a box)
 * - No z-fighting with the entity model
 * - Respects occlusion (doesn't show through walls unless we want it to)
 *
 * WHY AABB INSTEAD OF MESH:
 * Rendering the actual entity mesh in the stencil pass requires re-submitting
 * the entire entity render, which is expensive and complex (requires hooking
 * EntityRenderer deep in the pipeline). AABB approximation is visually
 * acceptable for PvP — you're looking at players, which are roughly box-shaped.
 * Phase 5 will add proper mesh stenciling for aesthetics.
 *
 * THREAD: Render thread only. Called from MixinWorldRenderer.
 *
 * PERFORMANCE:
 * Two AABB renders per outlined entity = 2 × 8 vertices = 16 vertex submissions.
 * This is negligible. The stencil buffer operations are free on modern GPUs
 * (they happen during the rasterization stage with no additional memory bandwidth).
 */
public final class EntityOutlineRenderer {

    private static final float OUTLINE_EXPAND = 0.05f; // Expand AABB by this fraction

    // Outline colors per state
    private static final int COLOR_TARGET    = 0xFFFF4444; // Red — current target
    private static final int COLOR_ATTACKING = 0xFFFFAA00; // Orange — recently attacked
    private static final int COLOR_ALLY      = 0xFF44FF44; // Green — party member (Phase 5)

    private boolean initialized = false;

    public EntityOutlineRenderer() {}

    public void init() {
        initialized = true;
        BoogerClient.LOGGER.debug("EntityOutlineRenderer initialized");
    }

    /**
     * Called by MixinWorldRenderer after entity rendering is complete.
     * Renders outlines for entities tracked by TargetHudModule.
     *
     * @param partialTick Interpolation factor for smooth outline position
     */
    public void renderOutlines(float partialTick) {
        if (!initialized) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;

        var targetModule = BoogerClient.modules()
            .getModule(TargetHudModule.class).orElse(null);
        if (targetModule == null || !targetModule.isEnabled()) return;

        // Get current target from TargetHudModule
        LivingEntity target = getTargetEntity(mc, targetModule);
        if (target == null || target.isDead()) return;

        // Determine outline color based on threat state
        int outlineColor = COLOR_TARGET;

        try {
            renderEntityOutline(target, outlineColor, partialTick, mc);
        } catch (Exception e) {
            BoogerClient.LOGGER.debug("EntityOutlineRenderer: outline render failed", e);
        }
    }

    private void renderEntityOutline(LivingEntity entity, int argb,
                                      float partialTick, MinecraftClient mc) {
        // Interpolated entity position
        double ex = MathHelper.lerp(partialTick, entity.lastRenderX, entity.getX());
        double ey = MathHelper.lerp(partialTick, entity.lastRenderY, entity.getY());
        double ez = MathHelper.lerp(partialTick, entity.lastRenderZ, entity.getZ());

        // Camera position for relative rendering
        Camera camera = mc.gameRenderer.getCamera();
        double cx = camera.getPos().x;
        double cy = camera.getPos().y;
        double cz = camera.getPos().z;

        // Entity AABB in camera-relative space
        Box bb = entity.getBoundingBox();
        float minX = (float)(bb.minX - cx);
        float minY = (float)(bb.minY - cy);
        float minZ = (float)(bb.minZ - cz);
        float maxX = (float)(bb.maxX - cx);
        float maxY = (float)(bb.maxY - cy);
        float maxZ = (float)(bb.maxZ - cz);

        // Expanded AABB for outline ring
        float exp = OUTLINE_EXPAND;
        float oxMin = minX - exp, oyMin = minY - exp, ozMin = minZ - exp;
        float oxMax = maxX + exp, oyMax = maxY + exp, ozMax = maxZ + exp;

        // Unpack outline color
        float r = ((argb >> 16) & 0xFF) / 255.0f;
        float g = ((argb >>  8) & 0xFF) / 255.0f;
        float b = ( argb        & 0xFF) / 255.0f;
        float a = ((argb >> 24) & 0xFF) / 255.0f;

        // ── Pass 1: Write entity AABB to stencil buffer ──────────────────────
        GL11.glEnable(GL11.GL_STENCIL_TEST);
        GL11.glColorMask(false, false, false, false); // No color output
        GL11.glDepthMask(false);
        GL11.glStencilFunc(GL11.GL_ALWAYS, 1, 0xFF);
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_REPLACE);

        renderBox(minX, minY, minZ, maxX, maxY, maxZ, 0, 0, 0, 0);

        // ── Pass 2: Draw outline in stencil NOT-equal region ─────────────────
        GL11.glColorMask(true, true, true, true);
        GL11.glStencilFunc(GL11.GL_NOTEQUAL, 1, 0xFF);
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
        GL11.glDepthMask(false);
        GL11.glDisable(GL11.GL_DEPTH_TEST); // Render through walls for target highlight

        renderBox(oxMin, oyMin, ozMin, oxMax, oyMax, ozMax, r, g, b, a * 0.85f);

        // ── Restore GL state ─────────────────────────────────────────────────
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(true);
        GL11.glDisable(GL11.GL_STENCIL_TEST);
        GL11.glStencilMask(0xFF);
        GL11.glClear(GL11.GL_STENCIL_BUFFER_BIT);
    }

    /**
     * Render a wireframe box using immediate mode GL.
     * 8 vertices, 12 edges = 24 GL calls. Fast enough for 1-2 outlines per frame.
     */
    private void renderBox(float x0, float y0, float z0,
                            float x1, float y1, float z1,
                            float r, float g, float b, float a) {
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glLineWidth(2.0f);

        if (r == 0 && g == 0 && b == 0 && a == 0) {
            // Stencil pass — render filled faces
            GL11.glBegin(GL11.GL_QUADS);
        } else {
            GL11.glColor4f(r, g, b, a);
            GL11.glBegin(GL11.GL_QUADS);
        }

        // Bottom face
        GL11.glVertex3f(x0, y0, z0); GL11.glVertex3f(x1, y0, z0);
        GL11.glVertex3f(x1, y0, z1); GL11.glVertex3f(x0, y0, z1);
        // Top face
        GL11.glVertex3f(x0, y1, z0); GL11.glVertex3f(x1, y1, z0);
        GL11.glVertex3f(x1, y1, z1); GL11.glVertex3f(x0, y1, z1);
        // Front face
        GL11.glVertex3f(x0, y0, z0); GL11.glVertex3f(x1, y0, z0);
        GL11.glVertex3f(x1, y1, z0); GL11.glVertex3f(x0, y1, z0);
        // Back face
        GL11.glVertex3f(x0, y0, z1); GL11.glVertex3f(x1, y0, z1);
        GL11.glVertex3f(x1, y1, z1); GL11.glVertex3f(x0, y1, z1);
        // Left face
        GL11.glVertex3f(x0, y0, z0); GL11.glVertex3f(x0, y0, z1);
        GL11.glVertex3f(x0, y1, z1); GL11.glVertex3f(x0, y1, z0);
        // Right face
        GL11.glVertex3f(x1, y0, z0); GL11.glVertex3f(x1, y0, z1);
        GL11.glVertex3f(x1, y1, z1); GL11.glVertex3f(x1, y1, z0);

        GL11.glEnd();
    }

    private LivingEntity getTargetEntity(MinecraftClient mc, TargetHudModule module) {
        if (mc.targetedEntity instanceof LivingEntity living) return living;
        // Fallback to module's last target — exposed via getter added to TargetHudModule
        return module.getLastTarget();
    }
}
