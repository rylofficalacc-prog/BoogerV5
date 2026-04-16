package dev.booger.client.render.theme;

import dev.booger.client.render.BoogerFontRenderer;

/**
 * Theme definition — all HUD colors and style properties in one place.
 *
 * Modules NEVER hardcode colors. They call ThemeEngine.current().getAccentColor() etc.
 * This makes themes switchable at runtime without touching any module code.
 *
 * Colors are pre-packed ARGB ints for zero-cost access on the render path.
 */
public record Theme(
    String name,

    // Background colors
    int bgPrimary,       // Main element background
    int bgSecondary,     // Nested element background (e.g. armor bar bg)
    int bgBorder,        // Border around elements

    // Text colors
    int textPrimary,     // Main text
    int textSecondary,   // Labels, secondary info
    int textValue,       // Dynamic values (FPS number, ping number)

    // Accent colors
    int accentPrimary,   // Brand accent — used for highlights, active indicators
    int accentSecondary, // Secondary accent

    // Status colors
    int colorGood,       // Green — high health, good ping
    int colorWarning,    // Yellow — mid health, medium ping
    int colorDanger,     // Red — low health, high ping

    // Style properties
    float cornerRadius,  // For rounded rects (Phase 3 custom renderer)
    boolean shadowText,  // Draw shadow under text
    int shadowColor,     // Shadow color
    float elementPadding // Padding inside HUD elements (pixels)
) {

    // ─── Built-in themes ─────────────────────────────────────────────────────

    public static final Theme BOOGER_DARK = new Theme(
        "Booger Dark",
        BoogerFontRenderer.argb(180, 15, 15, 15),   // bgPrimary: near-black 70% alpha
        BoogerFontRenderer.argb(140, 10, 10, 10),   // bgSecondary
        BoogerFontRenderer.argb(200, 78, 175, 84),  // bgBorder: green accent border
        0xFFE0E0E0,                                  // textPrimary: soft white
        0xFF9E9E9E,                                  // textSecondary: gray
        0xFFFFFFFF,                                  // textValue: pure white
        0xFF4CAF50,                                  // accentPrimary: material green
        0xFF69F0AE,                                  // accentSecondary: light green
        0xFF66BB6A,                                  // colorGood
        0xFFFFCA28,                                  // colorWarning
        0xFFEF5350,                                  // colorDanger
        3.0f,                                        // cornerRadius
        true,                                        // shadowText
        BoogerFontRenderer.argb(120, 0, 0, 0),      // shadowColor
        3.0f                                         // elementPadding
    );

    public static final Theme LUNAR_CLONE = new Theme(
        "Lunar",
        BoogerFontRenderer.argb(160, 0, 0, 0),
        BoogerFontRenderer.argb(120, 0, 0, 0),
        BoogerFontRenderer.argb(200, 80, 80, 200),
        0xFFFFFFFF,
        0xFFAAAAAA,
        0xFFFFFFFF,
        0xFF7289DA,
        0xFF99AAB5,
        0xFF55FF55,
        0xFFFFFF55,
        0xFFFF5555,
        2.0f, true,
        BoogerFontRenderer.argb(100, 0, 0, 0),
        3.0f
    );

    public static final Theme MINIMAL = new Theme(
        "Minimal",
        BoogerFontRenderer.argb(0, 0, 0, 0),        // No background
        BoogerFontRenderer.argb(0, 0, 0, 0),
        BoogerFontRenderer.argb(0, 0, 0, 0),
        0xFFFFFFFF,
        0xFFCCCCCC,
        0xFFFFFFFF,
        0xFFFFFFFF,
        0xFFAAAAAA,
        0xFF55FF55,
        0xFFFFFF55,
        0xFFFF5555,
        0.0f, true,
        BoogerFontRenderer.argb(180, 0, 0, 0),
        0.0f
    );

    public static final Theme[] ALL_THEMES = { BOOGER_DARK, LUNAR_CLONE, MINIMAL };
}
