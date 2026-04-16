package dev.booger.client.module.impl.pvp;

import dev.booger.client.module.BoogerModule;
import dev.booger.client.module.ModuleCategory;
import dev.booger.client.module.Setting;
import net.minecraft.client.gui.DrawContext;

/** Kill effect — visual flash when landing a killing blow. */
public final class KillEffectModule extends BoogerModule {

    private final Setting<Integer> effectColor = addSetting(
        Setting.color("effectColor", "Kill Flash Color", 0x44FF0000)
    );
    private final Setting<Float> duration = addSetting(
        Setting.floatSetting("duration", "Duration (ms)", 300f, 100f, 1000f)
    );

    private volatile long killTimestamp = 0;

    public KillEffectModule() {
        super("KillEffect", "Visual effect on kill", ModuleCategory.PVP);
        setEnabled(true);
    }

    public void onKill() {
        killTimestamp = System.currentTimeMillis();
    }

    public int getKillOverlayColor() {
        long elapsed = System.currentTimeMillis() - killTimestamp;
        float dur = duration.getValue();
        if (elapsed >= dur) return 0;
        float progress = elapsed / dur;
        int base = effectColor.getValue();
        int a = (base >>> 24) & 0xFF;
        return (base & 0x00FFFFFF) | ((int)(a * (1f - progress)) << 24);
    }

    @Override
    public void onRender(DrawContext context, float partialTick, int screenW, int screenH) {
        // No HUD element — effect is applied by MixinGameRenderer
    }
}
