package dev.booger.client.gui;

import dev.booger.client.BoogerClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

import java.util.Random;

/**
 * Booger Client — Facility Interior title screen.
 *
 * Matches the reference image:
 *   - Dark charcoal back wall with subtle panel grid
 *   - Two tall arched windows (left + right) with blue night glass and pane dividers
 *   - Large semi-circular top window (center)
 *   - Snaking grey pipes on both side walls
 *   - Second-floor metal walkway with RUST/ORANGE railings + vertical posts
 *   - Green glowing crates on walkway
 *   - Staircase on right side (rust-edged steps)
 *   - Dark concrete floor with perspective lines
 *   - Three GREEN GLOWING floor vents (the signature element from the reference)
 *   - Small pipe+vent cluster bottom right
 *   - Green particles floating up from floor
 *   - Atmospheric depth fog / vignette
 *
 * Right Shift opens the full mod menu (BoogerModScreen).
 */
public final class BoogerTitleScreen extends Screen {

    private static final int BTN_W = 172, BTN_H = 26, BTN_GAP = 5;

    private final Random rng = new Random();
    private float T = 0;

    // Particles
    private static final int PART = 20;
    private final float[] px = new float[PART], py = new float[PART];
    private final float[] pvx = new float[PART], pvy = new float[PART];
    private final float[] pa = new float[PART], pwo = new float[PART];

    // Scene geometry (computed in init)
    private int sw; // scene pixel width (left portion)

    public BoogerTitleScreen() { super(Text.literal("Booger Client")); }
    @Override public boolean shouldPause() { return false; }

    @Override
    protected void init() {
        sw = (int)(this.width * 0.775f);
        for (int i = 0; i < PART; i++) spawnParticle(i);
        buildButtons();
    }

    private void spawnParticle(int i) {
        px[i]  = rng.nextFloat() * sw * 0.9f;
        py[i]  = (float)(this.height * 0.4f + rng.nextFloat() * this.height * 0.55f);
        pvx[i] = (rng.nextFloat() - 0.5f) * 0.03f;
        pvy[i] = -(0.015f + rng.nextFloat() * 0.06f);
        pa[i]  = 0.03f + rng.nextFloat() * 0.12f;
        pwo[i] = rng.nextFloat() * 6.28f;
    }

    private void buildButtons() {
        int cx = sw + (this.width - sw) / 2 - BTN_W / 2;
        int sy = (int)(this.height * 0.46f);
        String[] labels  = { "Singleplayer", "Multiplayer", "Settings & Mods", "Resource Packs", "Quit Game" };
        boolean[] danger = { false, false, false, false, true };
        Runnable[] acts  = {
            () -> client.setScreen(new SelectWorldScreen(this)),
            () -> client.setScreen(new MultiplayerScreen(this)),
            () -> client.setScreen(new BoogerModScreen(this)),
            () -> {},
            () -> client.scheduleStop()
        };
        for (int i = 0; i < labels.length; i++) {
            final int fi = i;
            addDrawableChild(new FacBtn(cx, sy + i * (BTN_H + BTN_GAP),
                BTN_W, BTN_H, Text.literal(labels[i]), b -> acts[fi].run(), danger[i]));
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Right Shift (keyCode 344) opens mod menu
        if (keyCode == 344) {
            client.setScreen(new BoogerModScreen(this));
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void tick() {
        T += 0.016f;
        for (int i = 0; i < PART; i++) {
            px[i] += pvx[i] + (float)Math.sin(T * 1.6f + pwo[i]) * 0.008f;
            py[i] += pvy[i];
            if (py[i] < this.height * 0.4f) { py[i] = this.height * 0.95f; px[i] = rng.nextFloat() * sw * 0.9f; }
            if (px[i] < 0) px[i] = sw * 0.9f;
            if (px[i] > sw * 0.9f) px[i] = 0;
        }
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        int w = this.width, h = this.height;

        // Background
        ctx.fill(0, 0, w, h, 0xFF0e1318);

        renderScene(ctx, h);
        renderMenuPanel(ctx, w, h);
        renderTitle(ctx, w, h);
        renderParticles(ctx);

        super.render(ctx, mx, my, delta); // buttons

        renderFooter(ctx, w, h);
    }

    // ── Scene rendering ──────────────────────────────────────────────────────

    private void renderScene(DrawContext ctx, int h) {
        // Back wall base
        ctx.fill(0, 0, sw, h, 0xFF161e26);

        // Centre back panel (lighter)
        ctx.fill((int)(sw*.17f), 0, (int)(sw*.83f), (int)(h*.82f), 0xFF1c2530);

        // Panel grid
        for (int x = (int)(sw*.17f); x < (int)(sw*.83f); x += 44)
            ctx.fill(x, 0, x+1, (int)(h*.82f), 0x06FFFFFF);
        for (int y = 0; y < (int)(h*.82f); y += 44)
            ctx.fill((int)(sw*.17f), y, (int)(sw*.83f), y+1, 0x06FFFFFF);

        renderPipes(ctx, h);
        renderArchedWindow(ctx, (int)(sw*.025f), (int)(h*.07f), (int)(sw*.155f), (int)(h*.75f));
        renderArchedWindow(ctx, (int)(sw*.820f), (int)(h*.07f), (int)(sw*.155f), (int)(h*.75f));
        renderTopArch(ctx, h);
        renderCeiling(ctx, h);
        renderWalkway(ctx, h);
        renderStairs(ctx, h);
        renderFloor(ctx, h);
        renderVents(ctx, h);
        renderSideVent(ctx, (int)(sw*.76f), (int)(h*.85f), h);
        renderParticles(ctx);

        // Depth fog
        ctx.fill(0, 0, sw, h, 0x0A0A120E);

        // Right edge fade to menu
        for (int i = 0; i < 55; i++) {
            int a = (int)(i / 55.0f * 220);
            ctx.fill(sw - 55 + i, 0, sw - 54 + i, h, (a << 24));
        }
    }

    private void renderArchedWindow(DrawContext ctx, int x, int y, int w, int h) {
        int archR = w / 2, archY = y + archR;
        // Frame
        ctx.fill(x, archY, x + w, y + h, 0xFF1e2a38);
        drawEllipse(ctx, x + w/2, archY, w/2, archR, 0xFF1e2a38);
        // Glass
        ctx.fill(x+5, archY, x+w-5, y+h-5, 0xDA162840);
        drawEllipse(ctx, x + w/2, archY, w/2-5, archR-5, 0xDA122032);
        // Pane dividers
        ctx.fill(x+w/2, y+4, x+w/2+1, y+h, 0xFF263448);
        for (int i = 1; i < 3; i++) {
            int lineY = archY + i * (h - archR) / 3;
            ctx.fill(x+5, lineY, x+w-5, lineY+1, 0xFF263448);
        }
        // Moonlight glow
        float pulse = 0.04f + (float)Math.sin(T * 0.3f) * 0.01f;
        int ga = (int)(pulse * 255 * 0.6f);
        drawEllipse(ctx, x + w/2, y + h/2, (int)(w * 0.9f), (int)(h * 0.55f), (ga << 24) | 0x28468C);
    }

    private void renderTopArch(DrawContext ctx, int h) {
        int ax = (int)(sw * .24f), aw = (int)(sw * .52f), ah = (int)(h * .30f);
        // Frame
        ctx.fill(ax, 0, ax+aw, ah, 0xFF1e2a38);
        drawEllipse(ctx, ax+aw/2, ah, aw/2, (int)(ah*.45f), 0xFF1e2a38);
        // Glass
        ctx.fill(ax+7, 0, ax+aw-7, ah-2, 0xDA14264A);
        drawEllipse(ctx, ax+aw/2, ah, aw/2-7, (int)(ah*.45f)-5, 0xC8122032);
        // Grid panes
        for (int i = 1; i < 5; i++) ctx.fill(ax + aw*i/5, 0, ax + aw*i/5+1, ah, 0xFF253448);
        for (int i = 1; i < 3; i++) ctx.fill(ax, ah*i/3, ax+aw, ah*i/3+1, 0xFF253448);
    }

    private void renderCeiling(DrawContext ctx, int h) {
        ctx.fill(0, 0, sw, (int)(h*.05f), 0xFF1a2230);
        for (int bx = (int)(sw*.05f); bx < (int)(sw*.95f); bx += (int)(sw*.14f)) {
            ctx.fill(bx, 0, bx+(int)(sw*.06f), (int)(h*.18f), 0xFF1e2838);
            ctx.fill(bx+1, 0, bx+3, (int)(h*.18f), 0x08FFFFFF);
        }
    }

    private void renderPipes(DrawContext ctx, int h) {
        // Left side snaking pipe
        int[][] lp = { {(int)(sw*.17f),(int)(h*.10f)}, {(int)(sw*.17f),(int)(h*.35f)},
                       {(int)(sw*.22f),(int)(h*.35f)}, {(int)(sw*.22f),(int)(h*.55f)},
                       {(int)(sw*.17f),(int)(h*.55f)}, {(int)(sw*.17f),(int)(h*.72f)} };
        drawPixelPath(ctx, lp, 5, 0xFF2e3a3a);
        drawPixelPath(ctx, lp, 2, 0xFF283232);

        // Right side snaking pipe
        int[][] rp = { {(int)(sw*.83f),(int)(h*.08f)}, {(int)(sw*.83f),(int)(h*.28f)},
                       {(int)(sw*.78f),(int)(h*.28f)}, {(int)(sw*.78f),(int)(h*.50f)},
                       {(int)(sw*.83f),(int)(h*.50f)}, {(int)(sw*.83f),(int)(h*.72f)} };
        drawPixelPath(ctx, rp, 5, 0xFF2e3a3a);

        // Horizontal connector
        ctx.fill((int)(sw*.17f), (int)(h*.18f), (int)(sw*.83f), (int)(h*.18f)+4, 0xFF263434);
    }

    private void drawPixelPath(DrawContext ctx, int[][] pts, int thick, int col) {
        for (int i = 0; i < pts.length - 1; i++) {
            int x1 = pts[i][0], y1 = pts[i][1], x2 = pts[i+1][0], y2 = pts[i+1][1];
            if (x1 == x2) ctx.fill(x1 - thick/2, Math.min(y1,y2), x1 + thick/2 + 1, Math.max(y1,y2), col);
            else           ctx.fill(Math.min(x1,x2), y1 - thick/2, Math.max(x1,x2), y1 + thick/2 + 1, col);
        }
    }

    private void renderWalkway(DrawContext ctx, int h) {
        int wy = (int)(h * .37f), wh = 14;
        int wl = (int)(sw * .155f), wr = (int)(sw * .845f);

        // Grating floor
        ctx.fill(wl, wy, wr, wy+wh, 0xFF2e3a4a);
        for (int x = wl; x < wr; x += 7) ctx.fill(x, wy, x+1, wy+wh, 0x66000000);
        ctx.fill(wl, wy+wh, wr, wy+wh+5, 0x55000000); // shadow

        // Orange/rust railings — EXACT match to reference
        int railTop = wy - 28;
        // Bottom rail beam
        ctx.fill(wl, wy, wr, wy+5, 0xFF7a4420);
        ctx.fill(wl, wy+5, wr, wy+8, 0xFF5a3018);
        // Top rail
        ctx.fill(wl, railTop, wr, railTop+5, 0xFF8a4e28);
        // Mid rail
        ctx.fill(wl, railTop+14, wr, railTop+17, 0xFF7a4420);
        // Vertical posts
        for (int px = wl + 4; px < wr - 4; px += (int)(sw * .055f)) {
            ctx.fill(px, railTop, px+4, wy+wh, 0xFF8a4e28);
        }

        // Crates on walkway (matching reference)
        int crateY = wy;
        int[][] crates = { {(int)(sw*.23f),26,24}, {(int)(sw*.42f),30,28},
                           {(int)(sw*.57f),22,20}, {(int)(sw*.68f),28,24} };
        for (int[] c : crates) {
            int bx = c[0], bw = c[1], bh = c[2];
            ctx.fill(bx, crateY-bh, bx+bw, crateY, 0xFF2e3c2e);
            ctx.fill(bx, crateY-bh, bx+bw, crateY-bh+2, 0xFF243224); // top
            ctx.fill(bx, crateY-bh, bx+2, crateY, 0xFF1a2818);       // left
            ctx.fill(bx+1, crateY-bh+1, bx+bw-1, crateY-1, 0x0F39ff6e); // glow
        }
    }

    private void renderStairs(DrawContext ctx, int h) {
        int stX = (int)(sw*.68f), stTopY = (int)(h*.37f), stBotY = (int)(h*.80f);
        int steps = 10;
        int sw2 = (int)(sw * .13f) / steps, sh = (stBotY - stTopY) / steps;
        for (int i = 0; i < steps; i++) {
            int sx = stX + i*sw2, sy = stTopY + i*sh;
            ctx.fill(sx, sy, sx+sw2+1, stBotY, 0xFF283445);  // riser
            ctx.fill(sx, sy, sx+sw2+1, sy+3, 0xFF323f50);     // tread
            ctx.fill(sx, sy, sx+sw2+1, sy+2, 0xFF7a4420);     // rust edge
        }
        // Handrail
        for (int i = 0; i < steps * 8; i++) {
            float t2 = i / (float)(steps * 8);
            int hx = stX + (int)(t2 * steps * sw2);
            int hy = stTopY + (int)(t2 * (stBotY - stTopY));
            ctx.fill(hx, hy, hx+3, hy+3, 0xFF8a4e28);
        }
    }

    private void renderFloor(DrawContext ctx, int h) {
        ctx.fill(0, (int)(h*.80f), sw, h, 0xFF111820);
        ctx.fill(0, (int)(h*.80f), sw, (int)(h*.80f)+1, 0x0AFFFFFF);
        // Perspective lines
        for (int x = 0; x < sw; x += sw/7)
            for (int y = (int)(h*.80f); y < h; y++)
                ctx.fill(x, y, x+1, y+1, 0x04FFFFFF);
    }

    private void renderVents(DrawContext ctx, int h) {
        int[][] vents = { {(int)(sw*.20f)}, {(int)(sw*.42f)}, {(int)(sw*.63f)} };
        int vy = (int)(h * .82f), vw = (int)(sw * .085f), vh = (int)(h * .055f);
        for (int idx = 0; idx < 3; idx++) {
            int vx = vents[idx][0];
            float pulse = 0.35f + (float)Math.sin(T * 1.8f + idx * 2.1f) * 0.12f;

            // Floor recess
            ctx.fill(vx - vw/2, vy, vx + vw/2, vy + vh + 3, 0xAA000000);

            // Glow spread on floor
            int spread = (int)(vw * 1.4f);
            for (int r = spread; r > 0; r--) {
                float frac = 1.0f - (float)r / spread;
                int ga = (int)(pulse * 160 * frac * frac);
                drawEllipse(ctx, vx, vy + vh/2, r, (int)(r * 0.35f), (ga << 24) | 0x32DC5A);
            }

            // Vent grate body
            ctx.fill(vx - vw/2, vy, vx + vw/2, vy + vh, 0xFF080e08);

            // Glowing bars
            for (int b = 0; b < 5; b++) {
                float barA = pulse * (0.8f + (float)Math.sin(T * 3 + b + idx) * 0.15f);
                int ba = (int)(barA * 255);
                int by = vy + b * (vh / 5) + 2;
                ctx.fill(vx - vw/2 + 3, by, vx + vw/2 - 3, by + vh/5 - 2, (ba << 24) | 0x39FF6E);
            }

            // Ooze at sides
            int sa = (int)(pulse * 0.3f * 255);
            ctx.fill(vx - vw/2 - 5, vy + vh/4, vx - vw/2, vy + vh*3/4, (sa << 24) | 0x1EB43C);
            ctx.fill(vx + vw/2, vy + vh/4, vx + vw/2 + 5, vy + vh*3/4, (sa << 24) | 0x1EB43C);
        }
    }

    private void renderSideVent(DrawContext ctx, int vx, int vy, int h) {
        float pulse = 0.4f + (float)Math.sin(T * 2.2f) * 0.15f;
        // Pipe vertical
        ctx.fill(vx, vy - (int)(h*.12f), vx+7, vy, 0xFF2e3a2e);
        ctx.fill(vx+2, vy - (int)(h*.12f), vx+4, vy, 0xFF263226);
        // Vent head
        ctx.fill(vx-10, vy, vx+28, vy+(int)(h*.06f), 0xFF0a140a);
        for (int b = 0; b < 4; b++) {
            int ba = (int)(pulse * 0.7f * 255);
            ctx.fill(vx-7+b*6, vy+3, vx-7+b*6+4, vy+(int)(h*.06f)-4, (ba<<24)|0x39FF6E);
        }
        // Glow
        int ga = (int)(pulse * 0.45f * 255);
        drawEllipse(ctx, vx+4, vy+(int)(h*.03f), 28, 18, (ga<<24)|0x32DC5A);
    }

    private void renderParticles(DrawContext ctx) {
        for (int i = 0; i < PART; i++) {
            int a = (int)(pa[i] * 255);
            ctx.fill((int)px[i], (int)py[i], (int)px[i]+1, (int)py[i]+1, (a<<24)|0x39FF6E);
        }
    }

    private void renderMenuPanel(DrawContext ctx, int w, int h) {
        ctx.fill(sw, 0, w, h, 0xFF090e0a);
        // Subtle separator
        ctx.fill(sw, 0, sw+1, h, 0x1A39FF6E);
        // Corner brackets
        int cs = 10, co = 5, cc = 0x3539FF6E;
        ctx.fill(co,co,co+cs,co+1,cc); ctx.fill(co,co,co+1,co+cs,cc);
        ctx.fill(w-co-cs,co,w-co,co+1,cc); ctx.fill(w-co-1,co,w-co,co+cs,cc);
        ctx.fill(co,h-co-1,co+cs,h-co,cc); ctx.fill(co,h-co-cs,co+1,h-co,cc);
        ctx.fill(w-co-cs,h-co-1,w-co,h-co,cc); ctx.fill(w-co-1,h-co-cs,w-co,h-co,cc);
        // Info
        ctx.drawText(textRenderer, "\u25a0 BOOGER LABS", 6, 8,  0xFF1a3d22, false);
        ctx.drawText(textRenderer, "\u25a0 REACTOR: ONLINE", 6, 18, 0xFF1a3d22, false);
        ctx.drawText(textRenderer, "\u25b2 RAD: 3.2 mSv/h",  6, 28, 0xFF3a5a22, false);
    }

    private void renderTitle(DrawContext ctx, int w, int h) {
        int panelCX = sw + (w - sw) / 2;
        int ty = (int)(h * 0.22f);
        String l1 = "BOOGER";
        int s1 = 3, tw1 = textRenderer.getWidth(l1) * s1;
        ctx.getMatrices().push();
        ctx.getMatrices().translate(panelCX - tw1/2f, ty, 0);
        ctx.getMatrices().scale(s1, s1, 1f);
        ctx.drawText(textRenderer, l1, 1, 1, 0xCC011a06, false);
        ctx.drawText(textRenderer, l1, 0, 0, 0xFF39ff6e, false);
        ctx.getMatrices().pop();

        String l2 = "CLIENT";
        int s2 = 2, tw2 = textRenderer.getWidth(l2) * s2;
        ctx.getMatrices().push();
        ctx.getMatrices().translate(panelCX - tw2/2f, ty + textRenderer.fontHeight*s1+2, 0);
        ctx.getMatrices().scale(s2, s2, 1f);
        ctx.drawText(textRenderer, l2, 0, 0, 0xFF18e050, false);
        ctx.getMatrices().pop();

        int ly = ty + textRenderer.fontHeight*s1 + textRenderer.fontHeight*s2 + 6;
        ctx.fill(panelCX-85, ly, panelCX+85, ly+1, 0x2239FF6E);
        String sub = "v"+BoogerClient.VERSION+" · 1.21.4 · FABRIC";
        ctx.drawText(textRenderer, sub, panelCX - textRenderer.getWidth(sub)/2, ly+4, 0xFF1a4d22, false);
        // Right shift hint
        String hint = "RShift \u2192 Mods";
        ctx.drawText(textRenderer, hint, panelCX - textRenderer.getWidth(hint)/2, ly+14, 0xFF133322, false);
    }

    private void renderFooter(DrawContext ctx, int w, int h) {
        ctx.drawText(textRenderer, "Booger Client v"+BoogerClient.VERSION+" | Fabric 0.16.9", 6, h-9, 0xFF1b3d22, false);
        String prof = "Profile: pvp";
        ctx.drawText(textRenderer, prof, w-textRenderer.getWidth(prof)-6, h-9, 0xFF1b3d22, false);
        String ready = "\u25a0 REACTOR STABLE";
        ctx.drawText(textRenderer, ready, (w-textRenderer.getWidth(ready))/2, h-9, 0xFF2e7d32, false);
    }

    /** Fill-row ellipse approximation */
    private void drawEllipse(DrawContext ctx, int cx2, int cy2, int rx, int ry, int col) {
        if (rx <= 0 || ry <= 0) return;
        for (int row = -ry; row <= ry; row++) {
            float f = 1.0f - (float)(row*row)/(ry*ry+1f);
            int hw = (int)(rx * Math.sqrt(Math.max(0, f)));
            if (hw > 0) ctx.fill(cx2-hw, cy2+row, cx2+hw, cy2+row+1, col);
        }
    }

    // ── Button ────────────────────────────────────────────────────────────────

    private static final class FacBtn extends ButtonWidget {
        private final boolean danger;
        FacBtn(int x, int y, int w, int h, Text msg, PressAction act, boolean danger) {
            super(x, y, w, h, msg, act, DEFAULT_NARRATION_SUPPLIER);
            this.danger = danger;
        }
        @Override
        protected void renderWidget(DrawContext ctx, int mx, int my, float delta) {
            boolean hov = isHovered();
            int x=getX(),y=getY(),w=getWidth(),h=getHeight();
            int bg,border,text;
            if (danger&&hov)   { bg=0xCC1a0505;border=0xFFcc2200;text=0xFFff5533; }
            else if (hov)      { bg=0xCC00190a;border=0xFF39ff6e;text=0xFF39ff6e; }
            else               { bg=0xBB040905;border=0xFF1a3d22;text=0xFF2e6e3e; }
            ctx.fill(x,y,x+w,y+h,bg);
            ctx.fill(x,y,x+w,y+1,border); ctx.fill(x,y+h-1,x+w,y+h,border);
            ctx.fill(x,y,x+1,y+h,border); ctx.fill(x+w-1,y,x+w,y+h,border);
            if (hov) ctx.fill(x,y,x+2,y+h,border);
            var tr = net.minecraft.client.MinecraftClient.getInstance().textRenderer;
            int tw = tr.getWidth(getMessage());
            ctx.drawText(tr,getMessage(),x+(w-tw)/2,y+(h-8)/2,text,false);
        }
    }
}
