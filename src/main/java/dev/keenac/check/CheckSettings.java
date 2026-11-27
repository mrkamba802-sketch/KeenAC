package dev.keenac.check;

import dev.keenac.KeenAC;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Typed access to per-check settings under the top-level checks section.
 * Numeric values are normalized so an accidental zero/negative count or
 * interval cannot create an unbounded loop or a scheduler failure.
 */
public final class CheckSettings {
    private CheckSettings() {
    }

    private static String path(String check, String key) {
        return "checks." + check + "." + key;
    }

    public static boolean enabled(KeenAC plugin, String check, boolean fallback) {
        return plugin.getConfig().getBoolean(path(check, "enabled"), fallback);
    }

    public static int integer(KeenAC plugin, String check, String key, int fallback) {
        int value = plugin.getConfig().getInt(path(check, key), fallback);
        // Packet identifiers and inventory slots legitimately use -1 sentinels.
        if (key.startsWith("min-")) return value;
        return Math.max(1, value);
    }

    public static long longValue(KeenAC plugin, String check, String key, long fallback) {
        return Math.max(0L, plugin.getConfig().getLong(path(check, key), fallback));
    }

    public static double decimal(KeenAC plugin, String check, String key, double fallback) {
        double value = plugin.getConfig().getDouble(path(check, key), fallback);
        return Double.isFinite(value) ? value : fallback;
    }

    public static long millisNanos(KeenAC plugin, String check, String key, long fallback) {
        return TimeUnit.MILLISECONDS.toNanos(longValue(plugin, check, key, fallback));
    }

    public static List<String> strings(KeenAC plugin, String check, String key,
                                        List<String> fallback) {
        List<String> values = plugin.getConfig().getStringList(path(check, key));
        return values.isEmpty() ? List.copyOf(fallback) : List.copyOf(values);
    }
}
