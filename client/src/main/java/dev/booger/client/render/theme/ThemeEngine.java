package dev.booger.client.render.theme;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Global theme manager. Thread-safe theme switching.
 *
 * Modules call ThemeEngine.current() on every render — this must be as cheap
 * as a field read. AtomicReference.get() is a single volatile read — essentially free.
 */
public final class ThemeEngine {

    private static final AtomicReference<Theme> activeTheme =
        new AtomicReference<>(Theme.BOOGER_DARK);

    private ThemeEngine() {}

    /** Get the current theme. Called every frame — must be O(1). */
    public static Theme current() {
        return activeTheme.get();
    }

    /** Switch theme — safe to call from any thread. */
    public static void setTheme(Theme theme) {
        activeTheme.set(theme);
    }

    public static void setThemeByName(String name) {
        for (Theme t : Theme.ALL_THEMES) {
            if (t.name().equalsIgnoreCase(name)) {
                setTheme(t);
                return;
            }
        }
    }
}
