package dev.booger.client.module;

import java.util.function.Consumer;

/**
 * Typed module setting with validation and change callbacks.
 *
 * We use generics + explicit type tags instead of reflection.
 * ConfigManager serializes these by calling getValue() and deserializes
 * by calling setValue() with the appropriate type from JSON.
 *
 * Supported setting types (T):
 * - Boolean (toggles)
 * - Integer (int sliders)
 * - Float (float sliders)
 * - String (text inputs)
 * - Enum subclasses (dropdown selectors)
 * - Color (32-bit ARGB int) — stored as Integer, rendered as color picker
 *
 * Usage in a module:
 * <pre>
 *   private final Setting<Boolean> showBg = addSetting(
 *       Setting.bool("showBackground", "Show background box", true)
 *   );
 *   // In render: if (showBg.getValue()) { ... }
 * </pre>
 */
public final class Setting<T> {

    public enum Type {
        BOOLEAN, INTEGER, FLOAT, STRING, ENUM, COLOR
    }

    private final String key;           // Config serialization key
    private final String displayName;   // Shown in GUI
    private final String description;   // Tooltip text
    private final Type type;

    private T value;
    private final T defaultValue;

    // Constraints for numeric types
    private final Number minValue;
    private final Number maxValue;

    // Optional change callback
    private Consumer<T> onChange;

    private Setting(String key, String displayName, String description,
                    Type type, T defaultValue, Number min, Number max) {
        this.key = key;
        this.displayName = displayName;
        this.description = description;
        this.type = type;
        this.defaultValue = defaultValue;
        this.value = defaultValue;
        this.minValue = min;
        this.maxValue = max;
    }

    // ─── Factory methods ─────────────────────────────────────────────────────

    public static Setting<Boolean> bool(String key, String name, boolean def) {
        return new Setting<>(key, name, "", Type.BOOLEAN, def, null, null);
    }

    public static Setting<Boolean> bool(String key, String name, String desc, boolean def) {
        return new Setting<>(key, name, desc, Type.BOOLEAN, def, null, null);
    }

    public static Setting<Integer> integer(String key, String name, int def, int min, int max) {
        return new Setting<>(key, name, "", Type.INTEGER, def, min, max);
    }

    public static Setting<Float> floatSetting(String key, String name, float def, float min, float max) {
        return new Setting<>(key, name, "", Type.FLOAT, def, min, max);
    }

    public static Setting<String> string(String key, String name, String def) {
        return new Setting<>(key, name, "", Type.STRING, def, null, null);
    }

    public static <E extends Enum<E>> Setting<E> enumSetting(String key, String name, E def) {
        return new Setting<>(key, name, "", Type.ENUM, def, null, null);
    }

    /** Color setting — value is 32-bit ARGB packed int */
    public static Setting<Integer> color(String key, String name, int defaultArgb) {
        return new Setting<>(key, name, "", Type.COLOR, defaultArgb, null, null);
    }

    // ─── Value access ────────────────────────────────────────────────────────

    public T getValue() {
        return value;
    }

    @SuppressWarnings("unchecked")
    public void setValue(Object newValue) {
        T casted;
        try {
            casted = (T) newValue;
        } catch (ClassCastException e) {
            return; // Silently ignore type mismatch from config deserialization
        }

        // Clamp numeric values
        if (casted instanceof Integer i && minValue != null) {
            casted = (T) Integer.valueOf(
                Math.max(minValue.intValue(), Math.min(maxValue.intValue(), i))
            );
        } else if (casted instanceof Float f && minValue != null) {
            casted = (T) Float.valueOf(
                Math.max(minValue.floatValue(), Math.min(maxValue.floatValue(), f))
            );
        }

        this.value = casted;

        if (onChange != null) {
            onChange.accept(this.value);
        }
    }

    public void reset() {
        setValue(defaultValue);
    }

    public Setting<T> onChange(Consumer<T> callback) {
        this.onChange = callback;
        return this;
    }

    // ─── Getters ─────────────────────────────────────────────────────────────

    public String getKey() { return key; }
    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
    public Type getType() { return type; }
    public T getDefaultValue() { return defaultValue; }
    public Number getMinValue() { return minValue; }
    public Number getMaxValue() { return maxValue; }
}
