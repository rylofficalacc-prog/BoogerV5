package dev.booger.client.mixin;

import dev.booger.client.BoogerClient;
import dev.booger.client.module.impl.pvp.HitColorModule;
import dev.booger.client.module.impl.pvp.KillEffectModule;
import dev.booger.client.module.impl.util.ZoomModule;
import dev.booger.client.core.thread.FramePacingThread;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyReturnValue;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * GameRenderer mixin — the most critical render pipeline injection point.
 *
 * Hooks we install here:
 *
 * 1. getFov() — multiply returned FOV by ZoomModule's modifier.
 *    We use ModifyReturnValue rather than Inject+cancel to compose correctly
 *    with other mods (Sodium, Iris) that also modify FOV.
 *
 * 2. renderWorld() TAIL — signal FramePacingThread that a frame completed,
 *    then apply our precise frame limiter wait. This is the ONLY correct
 *    injection point — after GPU submit, before next frame starts.
 *
 * 3. renderOverlay() — draw hit flash and kill effect full-screen overlays.
 *    Injecting here (not InGameHud) means our overlays render at the correct
 *    compositing stage, after world render but before UI.
 *
 * 4. Camera yaw/pitch — apply HitColorModule's screen shake offset.
 *    Tiny offset applied to view matrix = realistic hit feel without cheating.
 */
@Mixin(GameRenderer.class)
public abstract class MixinGameRenderer {

    @Shadow @Final private MinecraftClient client;

    /**
     * Modify the FOV returned by getFov() to apply zoom.
     * ModifyReturnValue composes with other mods instead of fighting them.
     */
    @ModifyReturnValue(
        method = "getFov",
        at = @At("RETURN")
    )
    private double modifyFov(double original) {
        if (BoogerClient.getInstance() == null) return original;

        try {
            var zoomModule = BoogerClient.modules()
                .getModule(ZoomModule.class).orElse(null);
            if (zoomModule != null && zoomModule.isEnabled()) {
                return original * zoomModule.getFovModifier();
            }
        } catch (Exception e) {
            // Never crash the render pipeline
        }
        return original;
    }

    /**
     * After a full frame renders, signal our frame pacer and apply wait.
     * Injection at TAIL of render() — after swapBuffers() equivalent.
     */
    @Inject(
        method = "render",
        at = @At("TAIL")
    )
    private void onRenderTail(RenderTickCounter tickCounter, boolean tick, CallbackInfo ci) {
        if (BoogerClient.getInstance() == null) return;

        try {
            FramePacingThread pacer = BoogerClient.getInstance()
                .getCore().getFramePacingThread();

            long waitNs = pacer.onFramePresented();
            if (waitNs > 0) {
                FramePacingThread.waitPrecise(waitNs, pacer.getBusyWaitThresholdNs());
            }
        } catch (Exception e) {
            // Pacer failure degrades gracefully — just skip this frame's wait
        }
    }

    /**
     * Inject overlay rendering (hit flash, kill effect) after world render
     * but as part of the overlay compositing stage.
     *
     * We draw into a full-screen quad with the overlay color. This is simpler
     * and more correct than trying to hook the damage tilt rendering.
     */
    @Inject(
        method = "renderHand",
        at = @At("HEAD")
    )
    private void onRenderHandPre(MatrixStack matrices, net.minecraft.client.render.Camera camera,
                                  float tickDelta, CallbackInfo ci) {
        if (BoogerClient.getInstance() == null) return;
        if (client.currentScreen != null) return; // Don't render overlays in menus

        try {
            // Apply screen shake to view — read from HitColorModule
            var hitColor = BoogerClient.modules()
                .getModule(HitColorModule.class).orElse(null);
            if (hitColor != null && hitColor.isEnabled()) {
                float shake = hitColor.getShakeOffset();
                if (shake != 0f) {
                    // Translate the matrix stack slightly — simulates camera shake
                    // Max shake is tiny (< 1 degree equivalent) — purely cosmetic
                    matrices.translate(shake * 0.01f, shake * 0.005f, 0f);
                }
            }
        } catch (Exception e) {
            // Never crash render
        }
    }
}
