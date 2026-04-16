package dev.booger.client.mixin;

import dev.booger.client.BoogerClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks into InGameHud.render() to fire our HUD render event.
 *
 * We inject at TAIL (after all vanilla HUD elements render) so our
 * elements always render on top. If we wanted to suppress vanilla elements
 * (e.g., custom FPS display replacing vanilla F3), we'd inject at HEAD
 * and cancel for specific elements.
 *
 * For now, we render alongside vanilla HUD elements. Phase 3 will add
 * per-element suppression for cleaner HUD customization.
 */
@Mixin(InGameHud.class)
public class MixinInGameHud {

    @Inject(
        method = "render",
        at = @At("TAIL")
    )
    private void onRenderTail(DrawContext context, RenderTickCounter tickCounter,
                               CallbackInfo ci) {
        if (BoogerClient.getInstance() == null) return;
        if (!BoogerClient.getInstance().getCore().isHealthy()) return;

        try {
            BoogerClient.hud().fireRenderEvent(context, tickCounter.getTickDelta(false));
        } catch (Exception e) {
            BoogerClient.LOGGER.error("HUD render event threw exception", e);
        }
    }
}
