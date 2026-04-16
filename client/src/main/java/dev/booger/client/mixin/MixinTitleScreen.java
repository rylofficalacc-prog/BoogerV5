package dev.booger.client.mixin;

import dev.booger.client.BoogerClient;
import dev.booger.client.gui.BoogerTitleScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces the vanilla TitleScreen with our custom BoogerTitleScreen.
 * Fires when Minecraft would normally show the main menu.
 */
@Mixin(TitleScreen.class)
public class MixinTitleScreen {

    @Inject(method = "init", at = @At("HEAD"), cancellable = true)
    private void onInit(CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.setScreen(new BoogerTitleScreen());
        ci.cancel();
    }
}
