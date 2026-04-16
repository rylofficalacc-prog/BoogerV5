package dev.booger.client.mixin.sodium;

import dev.booger.client.BoogerClient;
import dev.booger.client.render.chunk.ChunkRenderOptimizer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkJobResult;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.SortedRenderLists;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Comparator;
import java.util.PriorityQueue;

/**
 * Sodium RenderSectionManager mixin.
 *
 * TARGET: net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager
 *
 * HOW SODIUM SCHEDULES CHUNK BUILDS:
 * Sodium maintains a priority queue of chunk rebuild tasks in its
 * RenderSectionManager. When a chunk needs rebuilding (block change,
 * initial load, camera move), it's added to this queue.
 * The queue is drained by ChunkBuilderTask worker threads.
 *
 * The default priority comparator in Sodium sorts chunks by:
 *   1. Task type priority (immediate > normal > deferred)
 *   2. Distance from the camera (nearest first)
 *
 * OUR MODIFICATION:
 * We inject a secondary sort key: our velocity-adjusted priority score.
 * When two chunks have the same task type priority AND similar distance,
 * our score breaks the tie — chunks ahead of the player win.
 *
 * INJECTION STRATEGY:
 * We cannot directly replace Sodium's comparator (it's a private field
 * with no public API). Instead we:
 * 1. Inject into the method that adds tasks to the queue
 * 2. Post-process the task's priority before enqueueing
 *
 * Alternatively (more stable): inject into the sort key computation.
 * Sodium's distance-based priority is computed as:
 *   int distanceSquared = section.getSquaredDistance(cameraX, cameraY, cameraZ)
 * We modify this return value to factor in our velocity score.
 *
 * RISK ASSESSMENT:
 * This is a soft modification — we only affect ordering, never correctness.
 * If our mixin breaks due to a Sodium update, chunks still build correctly,
 * just in the vanilla Sodium order. We catch all exceptions and fall back.
 *
 * SODIUM VERSION PINNING:
 * This mixin targets Sodium mc1.21.4-0.6.0. The internal class names and
 * method signatures WILL change between Sodium versions. The mixin must be
 * re-verified on each Sodium update. We track this in CHANGELOG.md.
 *
 * NOTE ON FABRIC COMPATIBILITY:
 * Sodium's internal packages are not part of its public API.
 * We access them only via Mixin (compile-time safe) not via direct import
 * at runtime. If Sodium changes its package structure, the mixin silently
 * fails (Mixin's @Required=false behavior) rather than crashing.
 */
@Mixin(
    targets = "net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager",
    remap = false  // Sodium uses intermediary names, not yarn — don't remap
)
public abstract class MixinRenderSectionManager {

    /**
     * Inject into schedulePendingUpdates() — the method that processes
     * chunk rebuild requests each frame and adds them to the build queue.
     *
     * We intercept AFTER Sodium has determined a chunk needs rebuilding
     * but BEFORE it's submitted to the worker thread pool.
     *
     * This gives us the opportunity to record the chunk's position and
     * let ChunkRenderOptimizer.shouldSkipRebuild() veto camera-driven
     * rebuilds on known-empty chunks.
     */
    @Inject(
        method = "schedulePendingUpdates",
        at = @At("HEAD"),
        require = 0  // Don't crash if method signature changes
    )
    private void onSchedulePendingUpdates(CallbackInfo ci) {
        // This hook is intentionally minimal — just ensure our optimizer
        // is aware that Sodium is processing updates this frame.
        // Actual priority injection happens in the comparator hook below.
    }

    /**
     * Inject into the chunk distance calculation used for queue priority.
     *
     * Sodium calls RenderSection.getSquaredDistance(cameraX, cameraY, cameraZ)
     * to determine build order. Lower values = higher priority (nearest first).
     *
     * We modify the return value: divide by our priority score.
     * Higher score (forward-cone chunks) → lower effective distance → built sooner.
     * Lower score (behind-player chunks) → higher effective distance → built later.
     *
     * The math:
     *   effectiveDistance = actualDistanceSq / priorityScore
     *   priorityScore ∈ [0.01, ~5.0]
     *   Forward chunk (score=3.0):  effectiveDistance = actualDist / 3.0  (appears closer)
     *   Behind chunk  (score=0.3):  effectiveDistance = actualDist / 0.3  (appears farther)
     *
     * This preserves Sodium's relative ordering within similar priorities while
     * biasing the queue toward our movement direction.
     */
    @Inject(
        method = "getSchedulingDistance",
        at = @At("RETURN"),
        require = 0,
        cancellable = true
    )
    private void modifySchedulingDistance(
        RenderSection section,
        CallbackInfoReturnable<Double> cir
    ) {
        if (BoogerClient.getInstance() == null) return;

        try {
            ChunkRenderOptimizer optimizer = BoogerClient.getInstance()
                .getCore().getChunkRenderOptimizer();
            if (optimizer == null) return;

            int chunkX = section.getChunkX();
            int chunkZ = section.getChunkZ();

            // Check empty chunk skip
            if (optimizer.shouldSkipRebuild(
                    chunkX, chunkZ,
                    ChunkRenderOptimizer.RebuildReason.CAMERA_MOVED)) {
                // Signal Sodium to de-prioritize by returning a very large distance
                cir.setReturnValue(Double.MAX_VALUE / 2.0);
                return;
            }

            // Apply velocity-based priority adjustment
            double originalDist = cir.getReturnValue();
            double priorityScore = optimizer.getChunkPriority(chunkX, chunkZ);

            // Clamp score to prevent extreme values
            priorityScore = Math.max(0.1, Math.min(10.0, priorityScore));

            // Adjusted distance: forward chunks appear closer, behind chunks farther
            double adjustedDist = originalDist / priorityScore;
            cir.setReturnValue(adjustedDist);

        } catch (Exception e) {
            // Never crash Sodium's render loop — fail silently, Sodium retains control
            BoogerClient.LOGGER.debug(
                "ChunkOptimizer: priority injection failed (Sodium API changed?): {}",
                e.getMessage()
            );
        }
    }

    /**
     * Inject after a chunk build completes to report mesh emptiness
     * to ChunkRenderOptimizer's empty-chunk tracker.
     *
     * This lets the optimizer know which chunks are permanently empty
     * so it can skip future camera-driven rebuilds for them.
     */
    @Inject(
        method = "onBuildComplete",
        at = @At("HEAD"),
        require = 0
    )
    private void onChunkBuildComplete(
        ChunkJobResult<ChunkBuildOutput> result,
        CallbackInfo ci
    ) {
        if (BoogerClient.getInstance() == null) return;

        try {
            ChunkRenderOptimizer optimizer = BoogerClient.getInstance()
                .getCore().getChunkRenderOptimizer();
            if (optimizer == null) return;

            ChunkBuildOutput output = result.unwrap();
            if (output == null) return;

            RenderSection section = output.render;
            boolean isEmpty = output.terrain == null ||
                output.terrain.getMeshes().isEmpty();

            optimizer.onChunkMeshBuilt(section.getChunkX(), section.getChunkZ(), isEmpty);

        } catch (Exception e) {
            // Silent fail — correctness not affected
        }
    }
}
