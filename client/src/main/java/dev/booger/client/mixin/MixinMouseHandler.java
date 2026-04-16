package dev.booger.client.mixin;

import dev.booger.client.BoogerClient;
import dev.booger.client.module.impl.util.ZoomModule;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mouse handler mixin.
 *
 * Two responsibilities:
 *
 * 1. SHADOW STATE UPDATE:
 *    GLFW mouse callbacks fire on the main thread. Our InputPollingThread
 *    runs on a separate thread and cannot call GLFW directly. We intercept
 *    the callback here and update the shadow state atomically.
 *
 *    This is zero-overhead on the main thread — just an atomic store.
 *    The polling thread reads the shadow state at 1000Hz.
 *
 * 2. SENSITIVITY SCALING:
 *    When ZoomModule is active, we scale down the mouse delta before
 *    Minecraft processes it. This maintains consistent aim feel at any
 *    zoom level. We modify the local variable directly using @ModifyVariable
 *    rather than recalculating the entire mouse delta computation.
 *
 * WHY NOT USE FABRIC EVENTS FOR MOUSE:
 * Fabric's MouseMoveCallback fires AFTER Minecraft processes the delta,
 * making it useless for pre-processing. We need a PRE injection point.
 * Mixins give us that.
 */
@Mixin(Mouse.class)
public class MixinMouseHandler {

    /**
     * Intercept GLFW cursor position callback — update shadow state.
     * Method: onCursorPos (GLFW cursor position callback handler)
     */
    @Inject(
        method = "onCursorPos",
        at = @At("HEAD")
    )
    private void onCursorPosHead(long window, double x, double y, CallbackInfo ci) {
        if (BoogerClient.getInstance() == null) return;
        BoogerClient.getInstance().getCore()
            .getInputPollingThread().updateMousePosition(x, y);
    }

    /**
     * Intercept GLFW mouse button callback — update shadow state for CPS tracking.
     */
    @Inject(
        method = "onMouseButton",
        at = @At("HEAD")
    )
    private void onMouseButtonHead(long window, int button, int action, int mods,
                                    CallbackInfo ci) {
        if (BoogerClient.getInstance() == null) return;
        BoogerClient.getInstance().getCore()
            .getInputPollingThread().updateMouseButtonState(button, action);
    }

    /**
     * Scale mouse delta X when zoomed.
     *
     * @ModifyVariable targets the local variable 'e' (deltaX) in updateMouse()
     * at the point where it's first assigned from GLFW delta.
     *
     * NOTE: The exact variable name and index depends on the yarn mappings.
     * In 1.21.4 yarn, the delta variables in Mouse.updateMouse() are mapped
     * as local variables at ordinal 0 and 1 (double precision).
     * If this breaks on a mappings update, check yarn mappings for Mouse.updateMouse.
     */
    @ModifyVariable(
        method = "updateMouse",
        at = @At("HEAD"),
        argsOnly = false,
        index = 3   // deltaX is the 3rd local double in the method (after 'this', tickDelta)
    )
    private double modifyMouseDeltaX(double deltaX) {
        return scaleDelta(deltaX);
    }

    @ModifyVariable(
        method = "updateMouse",
        at = @At("HEAD"),
        argsOnly = false,
        index = 5   // deltaY
    )
    private double modifyMouseDeltaY(double deltaY) {
        return scaleDelta(deltaY);
    }

    private double scaleDelta(double delta) {
        if (BoogerClient.getInstance() == null) return delta;
        try {
            var zoom = BoogerClient.modules()
                .getModule(ZoomModule.class).orElse(null);
            if (zoom != null && zoom.isEnabled() && zoom.isZooming()) {
                return delta * zoom.getSensitivityMultiplier();
            }
        } catch (Exception e) {
            // Fail safe — return unmodified delta
        }
        return delta;
    }
}
