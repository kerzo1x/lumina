package dev.lumina.util;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Counts every GPU object the mod creates and frees, so a leak shows up in lumina.log
 * as a live count that keeps growing between health reports.
 */
public final class ResourceTracker {
    private static final Map<String, long[]> counters = new LinkedHashMap<>();
    private static final Map<String, String> live = new LinkedHashMap<>();

    private ResourceTracker() {
    }

    public static synchronized void created(String kind, String name, String details) {
        counters.computeIfAbsent(kind, k -> new long[2])[0]++;
        live.put(kind + ":" + name, details);
        LuminaLog.debug("[res] + %s '%s' %s", kind, name, details);
    }

    public static synchronized void freed(String kind, String name) {
        counters.computeIfAbsent(kind, k -> new long[2])[1]++;

        if (live.remove(kind + ":" + name) == null)
            LuminaLog.warn("[res] freed %s '%s' that was not tracked as live (double free?)", kind, name);
        else
            LuminaLog.debug("[res] - %s '%s'", kind, name);
    }

    public static synchronized String summary() {
        StringBuilder sb = new StringBuilder();

        for (var entry : counters.entrySet()) {
            long[] c = entry.getValue();
            if (!sb.isEmpty())
                sb.append(", ");
            sb.append(entry.getKey()).append(" live=").append(c[0] - c[1]).append(" (created ").append(c[0]).append(')');
        }

        return sb.isEmpty() ? "none" : sb.toString();
    }

    public static synchronized String liveList() {
        StringBuilder sb = new StringBuilder();
        live.forEach((key, details) -> sb.append("\n    ").append(key).append(' ').append(details));
        return sb.isEmpty() ? " none" : sb.toString();
    }
}
