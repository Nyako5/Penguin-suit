package dev.penguinsuit.events;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** One-pass interpolation: player/item text can never introduce new template placeholders. */
public final class EventFormat {
    private static final Pattern TOKEN = Pattern.compile("\\{([^{}]+)}");
    public static void validate(String format, Set<String> allowed) throws IOException {
        if (format == null || format.isBlank() || format.length() > 512 || format.codePoints().anyMatch(Character::isISOControl))
            throw new IOException("Invalid game event format (1-512 characters, no controls)");
        var matcher = TOKEN.matcher(format);
        while (matcher.find()) if (!allowed.contains(matcher.group(1))) throw new IOException("Unknown game event placeholder: " + matcher.group());
    }
    public static String render(String format, Map<String, String> values) {
        return clean(TOKEN.matcher(format).replaceAll(m -> java.util.regex.Matcher.quoteReplacement(values.getOrDefault(m.group(1), m.group()))), 1500);
    }
    public static String clean(String text, int limit) {
        StringBuilder out = new StringBuilder();
        boolean formatting = false;
        for (int cp : text.codePoints().toArray()) {
            if (formatting) { formatting = false; continue; }
            if (cp == 0xA7) { formatting = true; continue; }
            if (Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT) cp = ' ';
            if (out.length() + Character.charCount(cp) > limit) { out.append('…'); break; }
            out.appendCodePoint(cp);
        }
        return out.toString().strip();
    }
    private EventFormat() {}
}
