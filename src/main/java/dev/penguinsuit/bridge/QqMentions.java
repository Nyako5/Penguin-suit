package dev.penguinsuit.bridge;

import com.huhobot.penguin.qq.GroupMessage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Event-local QQ mention names. No account/name cache, network lookup or message mutation. */
public final class QqMentions {
    private static final Pattern TOKEN = Pattern.compile("<@!?([^<>\\s]{1,256})>");
    private static final String UNKNOWN = "@未知成员";
    private static final int MAX_MENTIONS = 128;
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private record Scope(GroupMessage message, Map<String, String> names) {}

    /** QQClient invokes its existing listener synchronously; restore any enclosing event on exit. */
    public static <T> T during(GroupMessage message, Object mentions, Supplier<T> listener) {
        Scope previous = CURRENT.get();
        CURRENT.set(new Scope(message, names(mentions)));
        try { return listener.get(); }
        finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    /** Called only on the non-command forwarding branch, BEFORE the existing text audit. */
    public static String forAudit(GroupMessage message, String content) {
        Scope scope = CURRENT.get();
        return render(content, scope != null && scope.message() == message ? scope.names() : Map.of());
    }

    private static Map<String, String> names(Object raw) {
        if (!(raw instanceof List<?> list)) return Map.of();
        Map<String, String> result = new HashMap<>();
        for (int i = 0; i < Math.min(list.size(), MAX_MENTIONS); i++) {
            if (!(list.get(i) instanceof Map<?, ?> user)) continue;
            String name = user.get("username") instanceof String text ? safeName(text) : "";
            if (name.isBlank()) continue;
            // The official User object supplies member_openid for groups and id as its alias.
            for (String key : List.of("member_openid", "id")) {
                if (!(user.get(key) instanceof String id) || id.isEmpty() || id.length() > 256) continue;
                result.merge(id, "@" + name, (a, b) -> a.equals(b) ? a : UNKNOWN);
            }
        }
        return Map.copyOf(result);
    }

    private static String render(String content, Map<String, String> names) {
        Matcher matcher = TOKEN.matcher(content);
        if (!matcher.find()) return content;
        StringBuilder result = new StringBuilder(content.length());
        do {
            String id = matcher.group(1);
            String replacement = id.equals("all") ? "@全体成员" : names.getOrDefault(id, UNKNOWN);
            // A QQ nickname containing $ or backslashes must remain literal.
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        } while (matcher.find());
        matcher.appendTail(result);
        return result.toString();
    }

    private static String safeName(String name) {
        StringBuilder result = new StringBuilder();
        name.codePoints().limit(128).forEach(cp -> {
            if (cp == '§' || Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT) return;
            // Prevent the upstream XML cleaner from consuming a nickname or adjacent text.
            result.appendCodePoint(cp == '<' ? '＜' : cp == '>' ? '＞' : cp);
        });
        return result.toString().strip();
    }

    private QqMentions() {}
}
