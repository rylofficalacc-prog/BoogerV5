package dev.booger.client.module;

public enum ModuleCategory {
    HUD("HUD", "Heads-up display elements"),
    PVP("PvP", "Combat enhancements"),
    UTILITY("Utility", "Quality of life features"),
    RENDER("Render", "Visual enhancements"),
    PERFORMANCE("Performance", "FPS and optimization tools");

    public final String displayName;
    public final String description;

    ModuleCategory(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }
}
