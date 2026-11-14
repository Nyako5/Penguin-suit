package dev.penguinsuit.reverse;

import java.util.List;

public final class OutboundPolicy {
    /** A configured prefix applies to the album's first caption, like Penguin normal chat. */
    public static String caption(String firstCaption, String requiredPrefix) {
        if (!firstCaption.startsWith(requiredPrefix)) return null;
        return firstCaption.substring(requiredPrefix.length());
    }
    /** The real attachment supplies the image; the text only identifies its sender/caption. */
    public static String message(String template, String sender, String filteredCaption, int index) {
        String body = index == 0 && !filteredCaption.isBlank() ? filteredCaption : "";
        return template.replace("{name}", sender).replace("{message}", body).stripTrailing();
    }
    public static List<String> targets(List<String> configured, List<String> blocked) {
        return configured.stream().filter(g -> g != null && !g.isBlank() && !blocked.contains(g)).distinct().toList();
    }
    private OutboundPolicy() {}
}
