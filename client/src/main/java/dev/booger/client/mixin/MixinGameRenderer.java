package dev.booger.client.mixin;

import dev.booger.client.BoogerClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class MixinGameRenderer {

    @Shadow @Final MinecraftClient client;

    @Inject(method = "render", at = @At("HEAD"))
    private void onRenderHead(RenderTickCounter tickCounter, boolean tick, CallbackInfo ci) {
        if (BoogerClient.getInstance() == null) return;
        try {
            BoogerClient.getInstance().getCore().initGlSystems();
        } catch (Exception e) {
            BoogerClient.LOGGER.debug("GL init error: {}", e.getMessage());
        }
    }
}
