package dev.penguinsuit.bridge;

import java.util.regex.Pattern;

/** Chat Remastered supplies the angle brackets; send only the configured inner name. */
public final class MediaSenderFormat {
    private static final Pattern NATIVE = Pattern.compile("^<([^<>\\r\\n]*\\{name\\}[^<>\\r\\n]*)>[ \\t]*\\{message\\}$");
    private static final Pattern LEGACY = Pattern.compile("^([^<>\\r\\n]*\\{name\\})[ \\t]*[:：][ \\t]*\\{message\\}$");

    public static String format(String penguinTemplate, String name) {
        String inner = dev.penguinsuit.config.RuntimeSettings.get().messages.mediaSenderFallback;
        if (penguinTemplate != null && penguinTemplate.length() <= 2048) {
            String template = penguinTemplate.strip();
            var nativeMatch = NATIVE.matcher(template);
            var legacyMatch = LEGACY.matcher(template);
            String candidate = nativeMatch.matches() ? nativeMatch.group(1)
                    : legacyMatch.matches() ? legacyMatch.group(1) : null;
            // Unknown placeholders / body layouts must not leak into the sender field.
            if (candidate != null && !candidate.replace("{name}", "").matches(".*[{}].*")) inner = candidate;
        }
        String safeName = limit(clean(name == null ? "" : name), 128);
        return limit(clean(inner.replace("{name}", safeName)), 256);
    }

    private static String clean(String value) { return value.replaceAll("[\\p{Cntrl}§]", ""); }
    private static String limit(String value, int points) {
        return value.codePointCount(0, value.length()) <= points ? value
                : value.substring(0, value.offsetByCodePoints(0, points));
    }
    private MediaSenderFormat() {}
}
