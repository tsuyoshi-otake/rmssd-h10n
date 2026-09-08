package dev.otake.rmssdh10n;

import java.util.Locale;

/** Single source of truth for explicit H10 selection across UI, service and boot paths. */
final class DeviceSelection {
    private DeviceSelection() {}

    static String normalize(String value) {
        if (value == null) return null;
        String id = value.trim().toUpperCase(Locale.ROOT);
        return id.matches("[A-Z0-9:-]{4,64}") ? id : null;
    }

    static String resolve(String requested, String saved) {
        String explicit = normalize(requested);
        return explicit != null ? explicit : normalize(saved);
    }

    static String masked(String value) {
        String id = normalize(value);
        if (id == null) return "未選択";
        return id.length() <= 5 ? id : "…" + id.substring(id.length() - 5);
    }
}
