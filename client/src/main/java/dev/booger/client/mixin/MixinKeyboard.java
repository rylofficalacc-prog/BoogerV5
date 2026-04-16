package dev.booger.client.mixin;

import dev.booger.client.BoogerClient;
import dev.booger.client.gui.BoogerModScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keyboard mixin — key events + Right Shift global mod menu.
 * Right Shift (GLFW key 344) opens BoogerModScreen from anywhere in-game.
 */
@Mixin(net.minecraft.client.Keyboard.class)
public class MixinKeyboard {

    @Inject(method = "onKey", at = @At("HEAD"))
    private void onKeyHead(long window, int key, int scancode, int action, int modifiers,
                            CallbackInfo ci) {
        if (BoogerClient.getInstance() == null) return;

        BoogerClient.getInstance().getCore()
            .getInputPollingThread().updateKeyState(key, action);

        // Right Shift PRESS only
        if (key == 344 && action == 1) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc == null) return;
            if (mc.currentScreen instanceof ChatScreen) return;
            mc.execute(() -> {
                if (mc.currentScreen instanceof BoogerModScreen) {
                    // Already open — close it
                    mc.setScreen(null);
                } else {
                    mc.setScreen(new BoogerModScreen(mc.currentScreen));
                }
            });
        }
    }
}
