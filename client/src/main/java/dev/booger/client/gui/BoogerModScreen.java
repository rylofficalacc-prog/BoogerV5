package dev.booger.client.gui;

import dev.booger.client.BoogerClient;
import dev.booger.client.module.ModuleManager;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.*;

/**
 * Booger Client — Full Mod Menu Screen.
 *
 * Opened by:  Right Shift (from anywhere via MixinKeyboard)
 *             "Settings & Mods" button on the title screen
 *
 * LAYOUT (matches Lunar Client's mod menu aesthetic):
 *
 *   ┌────────────────────────────────────────────────────────┐
 *   │ BOOGER CLIENT — MOD MENU          [← Back]            │  top bar
 *   ├──────────────┬──────────────────┬──────────────────────┤
 *   │ CATEGORY     │ MODULE LIST      │ MODULE SETTINGS      │
 *   │              │                  │                      │
 *   │ HUD          │ ● FPS Counter    │ FPS Counter          │
 *   │ PvP          │ ● Ping Display   │ ─────────────────── │
 *   │ Movement     │ ○ CPS Counter    │ Enabled        [ON] │
 *   │ Render       │ ...              │ Color code FPS [ON] │
 *   │ Utility      │                  │ Background     [ON] │
 *   │ Lunar Mods   │                  │ Scale        ──● 1x │
 *   │ ──────────── │                  │ Good FPS    ────●120 │
 *   │ THEME        │                  │ Text color    ████  │
 *   │ Booger Dark  │                  │                      │
 *   │ Lunar Clone  │                  │                      │
 *   │ Minimal      │                  │                      │
 *   │ ──────────── │                  │                      │
 *   │ SYSTEM       │                  │                      │
 *   │ Profiles     │                  │                      │
 *   │ Safe Mode    │                  │                      │
 *   │ Discord      │                  │                      │
 *   └──────────────┴──────────────────┴──────────────────────┘
 *
 * ALL CATEGORIES + MODS (matching Lunar Client 1.1):
 *
 * HUD: FPS Counter, Ping Display, CPS Counter, Armour Status, Potion Effects,
 *      Coordinates, Direction HUD, Speed Display, Scoreboard, Boss Bar,
 *      Action Bar, Saturation, Reach Display, Keystrokes, Pack Display,
 *      Server Brand, Day Counter, Time Changer, Combo Display, Clock
 *
 * PvP: Hit Colour, Combo Counter, Target HUD, Kill Effect, Attack Indicator,
 *      Crosshair, Sword Cooldown, Shield Indicator, Item Physics, Auto GG, Ping Tags
 *
 * Movement: Sprint Indicator, Toggle Sneak, Toggle Sprint, Velocity Display,
 *           Step Height, No Fall, Fast Place
 *
 * Render: Full Bright, Entity ESP, Zoom, Freelook, Shaders Toggle, Motion Blur,
 *         FOV Changer, Outline ESP, Chunk Borders, Hitbox, Trajectory
 *
 * Utility: FPS Limiter, Anti GC, Packet Logger, Chat Timestamps, Tab Overlay,
 *          Screenshot Helper
 *
 * Lunar Mods: Emotes, Level Head, Team Tags, Nick Hider, Hypixel+, Discord RPC,
 *             Voice Chat, Friends List, Cosmetics, Waypoints, World Edit CUI,
 *             Replay Mod, Bug Fixes, Performance Boost
 *
 * Profiles: Default, PvP, Survival, Streaming, Auto-Switch by Server
 *
 * Safe Mode: Safe Mode Toggle, Packet Audit, JAR Verify, Class Scanner
 *
 * Discord: Rich Presence, Show Server, Show Elapsed Time, Party Size Display
 */
public final class BoogerModScreen extends Screen {

    private final Screen parent;

    // Layout zones
    private int sidebarW, modListW, panelX;
    private int topBarH;
    private int bodyY, bodyH;

    // Mod data
    private static final Map<String, List<String>> CATS = new LinkedHashMap<>();
    private static final Map<String, Boolean> ENABLED = new HashMap<>();

    static {
        CATS.put("HUD", Arrays.asList(
            "FPS Counter","Ping Display","CPS Counter","Armour Status","Potion Effects",
            "Coordinates","Direction HUD","Speed Display","Scoreboard","Boss Bar",
            "Action Bar","Saturation","Reach Display","Keystrokes","Pack Display",
            "Server Brand","Day Counter","Time Changer","Combo Display","Clock"
        ));
        CATS.put("PvP", Arrays.asList(
            "Hit Colour","Combo Counter","Target HUD","Kill Effect","Attack Indicator",
            "Crosshair","Sword Cooldown","Shield Indicator","Item Physics","Auto GG","Ping Tags"
        ));
        CATS.put("Movement", Arrays.asList(
            "Sprint Indicator","Toggle Sneak","Toggle Sprint","Velocity Display",
            "Step Height","No Fall","Fast Place"
        ));
        CATS.put("Render", Arrays.asList(
            "Full Bright","Entity ESP","Zoom","Freelook","Shaders Toggle","Motion Blur",
            "FOV Changer","Outline ESP","Chunk Borders","Hitbox","Trajectory"
        ));
        CATS.put("Utility", Arrays.asList(
            "FPS Limiter","Anti GC","Packet Logger","Chat Timestamps","Tab Overlay","Screenshot Helper"
        ));
        CATS.put("Lunar Mods", Arrays.asList(
            "Emotes","Level Head","Team Tags","Nick Hider","Hypixel+","Discord RPC",
            "Voice Chat","Friends List","Cosmetics","Waypoints","World Edit CUI",
            "Replay Mod","Bug Fixes","Performance Boost"
        ));
        CATS.put("Profiles",   Arrays.asList("Default","PvP","Survival","Streaming","Auto-Switch by Server"));
        CATS.put("Safe Mode",  Arrays.asList("Safe Mode Toggle","Packet Audit","JAR Verify","Class Scanner"));
        CATS.put("Discord",    Arrays.asList("Rich Presence","Show Server","Show Elapsed Time","Party Size Display"));

        // Default enabled state
        CATS.values().stream().flatMap(Collection::stream).forEach(m -> ENABLED.put(m, Math.random() > 0.3));
        List.of("FPS Counter","Ping Display","CPS Counter","Armour Status","Hit Colour",
                "Target HUD","Zoom","Full Bright","Combo Counter","Scoreboard","Keystrokes",
                "Coordinates","Direction HUD","Speed Display","Anti GC","Discord RPC",
                "Rich Presence","Default").forEach(m -> ENABLED.put(m, true));
    }

    private String selectedCat = "HUD";
    private String selectedMod = "FPS Counter";

    // Scroll
    private int modListScroll = 0;

    // Toggle animation states (index = row in settings panel)
    private final Map<String, Boolean> settingToggles = new HashMap<>();

    // Themes
    private static final String[] THEMES = { "Booger Dark", "Lunar Clone", "Minimal" };
    private int selectedTheme = 0;

    // Category sections for sidebar
    private static final String[][] SIDEBAR_SECTIONS = {
        { "MODULES", "HUD", "PvP", "Movement", "Render", "Utility", "Lunar Mods" },
        { "SYSTEM",  "Profiles", "Safe Mode", "Discord" },
        { "THEME",   "Booger Dark", "Lunar Clone", "Minimal" },
    };

    public BoogerModScreen(Screen parent) {
        super(Text.literal("Booger Client — Mods"));
        this.parent = parent;
    }

    @Override
    public boolean shouldPause() { return false; }

    @Override
    protected void init() {
        sidebarW  = 120;
        modListW  = 152;
        topBarH   = 26;
        bodyY     = topBarH;
        bodyH     = this.height - topBarH;
        panelX    = sidebarW + modListW;

        // Back button
        addDrawableChild(ButtonWidget.builder(Text.literal("\u2190 Back"), b -> close())
            .dimensions(this.width - 80, 4, 72, 18).build());
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) { close(); return true; } // ESC
        if (keyCode == 344) { close(); return true; } // RShift toggle
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void close() {
        client.setScreen(parent);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int mxi = (int)mx, myi = (int)my;

        // Sidebar click
        if (mxi < sidebarW && myi >= bodyY) {
            handleSidebarClick(mxi, myi);
            return true;
        }

        // Module list click
        if (mxi >= sidebarW && mxi < sidebarW + modListW && myi >= bodyY) {
            handleModListClick(mxi, myi - bodyY);
            return true;
        }

        // Settings panel toggle click
        if (mxi >= panelX && myi >= bodyY) {
            handleSettingsClick(mxi, myi - bodyY);
            return true;
        }

        return super.mouseClicked(mx, my, button);
    }

    private void handleSidebarClick(int mx, int my) {
        int y = bodyY + 8;
        for (String[] section : SIDEBAR_SECTIONS) {
            y += 16; // section header
            for (int i = 1; i < section.length; i++) {
                String item = section[i];
                if (my >= y && my < y + 18) {
                    if (section[0].equals("THEME")) {
                        selectedTheme = i - 1;
                    } else {
                        selectedCat = item;
                        selectedMod = CATS.getOrDefault(item, List.of()).stream().findFirst().orElse("");
                        modListScroll = 0;
                    }
                    return;
                }
                y += 18;
            }
            y += 6; // spacer
        }
    }

    private void handleModListClick(int mx, int my) {
        List<String> mods = CATS.getOrDefault(selectedCat, List.of());
        int y = 6;
        for (int i = 0; i < mods.size(); i++) {
            if (my - bodyY >= y && my - bodyY < y + 18) {
                selectedMod = mods.get(i);
                return;
            }
            // Right click toggles enable
            if (my - bodyY >= y && my - bodyY < y + 18 && mx >= sidebarW + 6 && mx < sidebarW + 14) {
                String m = mods.get(i);
                ENABLED.put(m, !ENABLED.getOrDefault(m, false));
            }
            y += 18;
        }
    }

    private void handleSettingsClick(int mx, int my) {
        // Toggle rows (enabled row = row 0)
        int row = (my - bodyY - 30) / 20;
        String key = selectedMod + "_" + row;
        settingToggles.put(key, !settingToggles.getOrDefault(key, row == 0 ? ENABLED.getOrDefault(selectedMod, false) : Math.random() > 0.5));
        if (row == 0) ENABLED.put(selectedMod, !ENABLED.getOrDefault(selectedMod, false));
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        int w = this.width, h = this.height;

        // Background
        ctx.fill(0, 0, w, h, 0xFF060c07);

        renderTopBar(ctx, w);
        renderSidebar(ctx, h);
        renderModList(ctx, mx, my, h);
        renderSettingsPanel(ctx, mx, my, h);
        renderDividers(ctx, h);

        super.render(ctx, mx, my, delta); // back button
    }

    private void renderTopBar(DrawContext ctx, int w) {
        ctx.fill(0, 0, w, topBarH, 0xFF040a05);
        ctx.fill(0, topBarH-1, w, topBarH, 0xFF152018);
        ctx.drawText(textRenderer, "BOOGER CLIENT \u2014 MOD MENU",
            12, topBarH/2 - textRenderer.fontHeight/2, 0xFF39ff6e, false);
        // Version badge
        String ver = "v" + BoogerClient.VERSION;
        ctx.drawText(textRenderer, ver, 200, topBarH/2 - textRenderer.fontHeight/2, 0xFF1a3d22, false);
        // Hint
        String hint = "RShift to close  |  ESC to close";
        ctx.drawText(textRenderer, hint,
            w - textRenderer.getWidth(hint) - 90,
            topBarH/2 - textRenderer.fontHeight/2, 0xFF1a3022, false);
    }

    private void renderSidebar(DrawContext ctx, int h) {
        ctx.fill(0, bodyY, sidebarW, h, 0xFF050b06);
        int y = bodyY + 8;
        for (String[] section : SIDEBAR_SECTIONS) {
            // Section header
            ctx.fill(0, y, sidebarW, y + 1, 0xFF0d1e0f);
            ctx.drawText(textRenderer, section[0], 10, y + 3, 0xFF1a3d22, false);
            y += 16;
            for (int i = 1; i < section.length; i++) {
                String item = section[i];
                boolean active;
                if (section[0].equals("THEME")) {
                    active = THEMES[selectedTheme].equals(item);
                } else {
                    active = item.equals(selectedCat);
                }
                if (active) {
                    ctx.fill(0, y, sidebarW, y+18, 0xFF07130a);
                    ctx.fill(0, y, 2, y+18, 0xFF39ff6e);
                }
                // Hover highlight
                ctx.drawText(textRenderer, item, 12, y + 5, active ? 0xFF39ff6e : 0xFF2a5a30, false);
                y += 18;
            }
            y += 6;
        }
    }

    private void renderModList(DrawContext ctx, int mx, int my, int h) {
        int listX = sidebarW;
        ctx.fill(listX, bodyY, listX + modListW, h, 0xFF070e08);

        // Section title
        ctx.drawText(textRenderer, selectedCat.toUpperCase(),
            listX + 8, bodyY + 6, 0xFF1a3d22, false);
        ctx.fill(listX, bodyY + 17, listX + modListW, bodyY + 18, 0xFF0d1e0f);

        List<String> mods = CATS.getOrDefault(selectedCat, List.of());
        int y = bodyY + 22;
        for (String mod : mods) {
            if (y + 18 > h) break;
            boolean sel  = mod.equals(selectedMod);
            boolean on   = ENABLED.getOrDefault(mod, false);
            boolean hov  = mx >= listX && mx < listX + modListW && my >= y && my < y + 18;

            if (sel) ctx.fill(listX, y, listX + modListW, y + 18, 0xFF08140a);
            else if (hov) ctx.fill(listX, y, listX + modListW, y + 18, 0xFF060f07);

            // Enable dot
            int dotCol = on ? 0xFF39ff6e : 0xFFcc2200;
            ctx.fill(listX + 8, y + 7, listX + 13, y + 12, dotCol);

            // Mod name
            ctx.drawText(textRenderer, mod, listX + 18, y + 5,
                sel ? 0xFFa5d6a7 : (on ? 0xFF2e6e3e : 0xFF2a4a2c), false);

            // Click to toggle dot area
            if (hov && mx < listX + 16) {
                ctx.fill(listX + 6, y + 5, listX + 15, y + 14, 0x2239ff6e);
            }
            y += 18;
        }

        // "Click dot to toggle" hint
        ctx.drawText(textRenderer, "Click \u25a0 to toggle",
            listX + 8, h - 14, 0xFF132018, false);
    }

    private void renderSettingsPanel(DrawContext ctx, int mx, int my, int h) {
        ctx.fill(panelX, bodyY, this.width, h, 0xFF080f09);

        if (selectedMod.isEmpty()) return;

        boolean modOn = ENABLED.getOrDefault(selectedMod, false);

        // Module title
        ctx.fill(panelX, bodyY, this.width, bodyY + 22, 0xFF060c07);
        ctx.drawText(textRenderer, selectedMod, panelX + 10, bodyY + 7, 0xFF39ff6e, false);
        ctx.fill(panelX, bodyY + 22, this.width, bodyY + 23, 0xFF0d1e0f);

        // Enabled toggle (always first)
        int y = bodyY + 30;
        renderSettingRow(ctx, panelX + 8, y, "Enabled",
            modOn ? 0xFF39ff6e : 0xFFcc2200, modOn, mx, my, 0);
        y += 22;

        // Module-specific settings
        for (String[] row : getModSettings(selectedMod)) {
            if (y + 20 > h) break;
            String label = row[0], type = row[1];
            switch (type) {
                case "tog" -> {
                    int ri = (y - bodyY - 30) / 22;
                    String key = selectedMod + "_" + ri;
                    boolean state = settingToggles.getOrDefault(key, Math.random() > 0.4);
                    renderSettingRow(ctx, panelX + 8, y, label, state ? 0xFF39ff6e : 0xFF1a3d22, state, mx, my, ri);
                }
                case "sld" -> {
                    renderSliderRow(ctx, panelX + 8, y, label, row[2], Integer.parseInt(row[3]));
                }
                case "col" -> {
                    renderColorRow(ctx, panelX + 8, y, label, row[2]);
                }
                case "lbl" -> {
                    ctx.drawText(textRenderer, label, panelX + 8, y + 4, 0xFF1a3d22, false);
                }
            }
            y += 22;
        }
    }

    private void renderSettingRow(DrawContext ctx, int x, int y, String label,
                                   int dotCol, boolean state, int mx, int my, int rowIdx) {
        boolean hov = mx >= x && mx < this.width - 8 && my >= y && my < y + 18;
        if (hov) ctx.fill(x - 6, y, this.width, y + 18, 0xFF060e07);

        ctx.drawText(textRenderer, label, x, y + 5, 0xFF2a5a30, false);

        // Toggle widget
        int tx = this.width - 36, ty = y + 4;
        ctx.fill(tx, ty, tx + 26, ty + 13, state ? 0xFF082a10 : 0xFF060c07);
        ctx.fill(tx, ty, tx + 26, ty + 1, state ? 0xFF39ff6e : 0xFF1a3022);
        ctx.fill(tx, ty + 12, tx + 26, ty + 13, state ? 0xFF39ff6e : 0xFF1a3022);
        ctx.fill(tx, ty, tx + 1, ty + 13, state ? 0xFF39ff6e : 0xFF1a3022);
        ctx.fill(tx + 25, ty, tx + 26, ty + 13, state ? 0xFF39ff6e : 0xFF1a3022);
        // Thumb
        int thumbX = state ? tx + 15 : tx + 2;
        ctx.fill(thumbX, ty + 2, thumbX + 9, ty + 11, state ? 0xFF39ff6e : 0xFF1a3022);
    }

    private void renderSliderRow(DrawContext ctx, int x, int y, String label, String val, int pct) {
        ctx.drawText(textRenderer, label, x, y + 5, 0xFF2a5a30, false);
        int slX = this.width - 80, slY = y + 7;
        ctx.fill(slX, slY, slX + 60, slY + 3, 0xFF152018);
        int filled = (int)(60 * pct / 100.0f);
        ctx.fill(slX, slY, slX + filled, slY + 3, 0xFF39ff6e);
        ctx.fill(slX + filled - 3, slY - 2, slX + filled + 4, slY + 7, 0xFF39ff6e);
        ctx.drawText(textRenderer, val, this.width - 18 - textRenderer.getWidth(val), y + 5,
            0xFF39ff6e, false);
    }

    private void renderColorRow(DrawContext ctx, int x, int y, String label, String hexColor) {
        ctx.drawText(textRenderer, label, x, y + 5, 0xFF2a5a30, false);
        // Parse hex color (simplified)
        int col;
        try {
            col = (int)Long.parseLong(hexColor.replace("#",""), 16);
            if (hexColor.length() <= 7) col |= 0xFF000000;
        } catch (Exception e) { col = 0xFF39ff6e; }
        ctx.fill(this.width - 34, y + 3, this.width - 12, y + 15, col);
        ctx.fill(this.width - 34, y + 3, this.width - 12, y + 4, 0xFF1a3022);
        ctx.fill(this.width - 34, y + 14, this.width - 12, y + 15, 0xFF1a3022);
        ctx.fill(this.width - 34, y + 3, this.width - 33, y + 15, 0xFF1a3022);
        ctx.fill(this.width - 13, y + 3, this.width - 12, y + 15, 0xFF1a3022);
    }

    private void renderDividers(DrawContext ctx, int h) {
        ctx.fill(sidebarW, bodyY, sidebarW + 1, h, 0xFF0d1e0f);
        ctx.fill(sidebarW + modListW, bodyY, sidebarW + modListW + 1, h, 0xFF0d1e0f);
    }

    /** Per-module settings rows — [label, type, value?, pct?] */
    private static String[][] getModSettings(String mod) {
        return switch (mod) {
            case "FPS Counter"   -> new String[][]{{"Color code FPS","tog"},{"Background","tog"},{"Shadow","tog"},{"Scale","sld","1.0x","33"},{"Good FPS","sld","120","72"},{"OK FPS","sld","60","33"},{"Text colour","col","#39ff6e"}};
            case "Ping Display"  -> new String[][]{{"Show icon","tog"},{"Background","tog"},{"Scale","sld","1.0x","33"},{"Good ping","sld","60ms","45"},{"Text colour","col","#39ff6e"}};
            case "CPS Counter"   -> new String[][]{{"Show L/R separate","tog"},{"Background","tog"},{"Scale","sld","1.0x","33"},{"Text colour","col","#39ff6e"}};
            case "Armour Status" -> new String[][]{{"Show durability","tog"},{"Warn threshold","sld","20%","20"},{"Show offhand","tog"},{"Icon size","sld","16px","33"}};
            case "Potion Effects"-> new String[][]{{"Show duration","tog"},{"Show amplifier","tog"},{"Compact mode","tog"},{"Icon size","sld","16px","33"}};
            case "Coordinates"   -> new String[][]{{"Show nether coords","tog"},{"Show biome","tog"},{"Show facing","tog"},{"Scale","sld","1.0x","33"}};
            case "Direction HUD" -> new String[][]{{"Show degrees","tog"},{"Compact","tog"},{"Scale","sld","1.0x","33"}};
            case "Speed Display" -> new String[][]{{"Unit","lbl","blocks/s"},{"Decimal places","sld","1","33"},{"Scale","sld","1.0x","33"}};
            case "Scoreboard"    -> new String[][]{{"Show sidebar","tog"},{"Show numbers","tog"},{"Background","tog"},{"Max width","sld","120px","60"}};
            case "Boss Bar"      -> new String[][]{{"Show text","tog"},{"Show bar","tog"},{"Scale","sld","1.0x","33"}};
            case "Keystrokes"    -> new String[][]{{"Show WASD","tog"},{"Show mouse","tog"},{"Show space","tog"},{"Show CPS","tog"},{"Scale","sld","1.0x","33"}};
            case "Hit Colour"    -> new String[][]{{"Self colour","col","#ff000088"},{"Target colour","col","#ffffff66"},{"Duration","sld","150ms","30"},{"Flash count","sld","1","10"}};
            case "Combo Counter" -> new String[][]{{"Timeout","sld","2.0s","50"},{"Show max","tog"},{"Animation","tog"},{"Scale","sld","1.0x","33"}};
            case "Target HUD"    -> new String[][]{{"Show armour","tog"},{"Show effects","tog"},{"Show ping","tog"},{"Width","sld","140px","65"},{"Background","tog"}};
            case "Kill Effect"   -> new String[][]{{"Effect type","lbl","Particles"},{"Duration","sld","500ms","40"},{"Colour","col","#ff4400"}};
            case "Crosshair"     -> new String[][]{{"Style","lbl","Default"},{"Gap","sld","2px","20"},{"Size","sld","6px","30"},{"Colour","col","#ffffff"}};
            case "Zoom"          -> new String[][]{{"Zoom FOV","sld","15°","12"},{"Smooth zoom","tog"},{"Sensitivity scale","tog"},{"Scroll to adjust","tog"},{"Cinematic camera","tog"}};
            case "Full Bright"   -> new String[][]{{"Gamma","sld","100%","100"},{"Night vision effect","tog"}};
            case "Entity ESP"    -> new String[][]{{"Players","tog"},{"Mobs","tog"},{"Animals","tog"},{"Colour","col","#ff0000"},{"Through walls","tog"}};
            case "FOV Changer"   -> new String[][]{{"FOV","sld","90°","50"},{"Override sprinting","tog"},{"Override potion","tog"}};
            case "Motion Blur"   -> new String[][]{{"Strength","sld","50%","50"}};
            case "FPS Limiter"   -> new String[][]{{"Max FPS","sld","60","33"},{"Only background","tog"}};
            case "Anti GC"       -> new String[][]{{"Threshold","sld","80%","60"},{"Aggressive","tog"}};
            case "Emotes"        -> new String[][]{{"Keybind","lbl","B"},{"Show others","tog"},{"Emote wheel","tog"}};
            case "Level Head"    -> new String[][]{{"Show own level","tog"},{"Provider","lbl","Hypixel"},{"Colour","col","#ffffff"}};
            case "Nick Hider"    -> new String[][]{{"Custom name","tog"},{"Hide in tab","tog"},{"Hide in chat","tog"}};
            case "Cosmetics"     -> new String[][]{{"Show cloak","tog"},{"Show wings","tog"},{"Show hat","tog"},{"Show bandana","tog"}};
            case "Discord RPC"   -> new String[][]{{"Show server","tog"},{"Show elapsed time","tog"},{"Show profile","tog"},{"Party size","tog"}};
            case "Waypoints"     -> new String[][]{{"Show in world","tog"},{"Show distance","tog"},{"Min distance","sld","10m","10"},{"Beam height","sld","50%","50"}};
            case "Hypixel+"      -> new String[][]{{"Auto tip","tog"},{"Star colour","tog"},{"MVP++ chat","tog"},{"Guild tag","tog"}};
            case "Friends List"  -> new String[][]{{"Show in tab","tog"},{"Notify join","tog"},{"Notify leave","tog"},{"Highlight colour","col","#39ff6e"}};
            default              -> new String[][]{{"Background","tog"},{"Shadow","tog"},{"Scale","sld","1.0x","33"},{"Text colour","col","#39ff6e"}};
        };
    }
}
