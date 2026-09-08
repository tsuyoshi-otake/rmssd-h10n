package dev.otake.rmssdh10n;

/** Pure four-way classification used before any H10 recording mutation. */
final class RecordingOwnership {
    enum Kind { INACTIVE, OURS, FOREIGN, UNKNOWN }
    private RecordingOwnership() {}

    static Kind classify(Boolean active, String identifier, String ownedPrefix) {
        if (active == null) return Kind.UNKNOWN;
        if (!active) return Kind.INACTIVE;
        if (identifier == null || identifier.trim().isEmpty()) return Kind.UNKNOWN;
        return identifier.startsWith(ownedPrefix) ? Kind.OURS : Kind.FOREIGN;
    }
}
