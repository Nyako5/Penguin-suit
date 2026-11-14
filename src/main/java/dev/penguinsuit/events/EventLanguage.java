package dev.penguinsuit.events;

import com.google.gson.JsonParser;
import dev.penguinsuit.PenguinSuit;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import java.nio.file.*;
import java.net.URI;
import java.net.http.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

/** Private renderer only: never replaces Minecraft's global Language or client components. */
public final class EventLanguage implements AutoCloseable {
    // Exact official 26.2 zh_cn asset, pinned through its verified Mojang asset index 32.
    public static final String SHA1 = "dc7a2a97cd71031e709e3d3c90ee1b511fad1269";
    public static final String FILE = "game-events-26.2-zh_cn.json";
    private static final int SIZE = 569479;
    private static final Pattern ARG = Pattern.compile("%(?:(\\d+)\\$)?s|%%");
    private final Path directory;
    private volatile Map<String, String> translations = Map.of();
    private volatile boolean closed;
    private volatile Map<String,String> overrides=Map.of();
    public void overrides(Map<String,String> values){overrides=Map.copyOf(values);}
    private Thread loader;
    public EventLanguage(Path directory) { this.directory = directory; }
    public synchronized void start() {
        if (closed || !translations.isEmpty() || loader != null) return;
        loader = Thread.ofVirtual().name("penguin-game-event-language").start(() -> {
            try {
                Path file = directory.resolve(FILE); byte[] bytes = null;
                if (Files.isRegularFile(file) && Files.size(file) == SIZE) {
                    byte[] cached = Files.readAllBytes(file);
                    if (verified(cached)) bytes = cached;
                }
                if (bytes == null) {
                    try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build()) {
                        var request = HttpRequest.newBuilder(URI.create("https://resources.download.minecraft.net/dc/" + SHA1))
                                .timeout(Duration.ofSeconds(15)).GET().build();
                        var response = http.send(request, HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), SIZE));
                        if (response.statusCode() != 200 || !verified(response.body())) throw new java.io.IOException("unverified language asset");
                        bytes = response.body();
                    }
                    if (closed) return;
                    Files.createDirectories(directory);
                    Path temporary = directory.resolve(FILE + ".tmp"); Files.write(temporary, bytes);
                    try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                    catch (AtomicMoveNotSupportedException e) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
                }
                Map<String, String> map = new HashMap<>();
                JsonParser.parseString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject()
                        .entrySet().forEach(e -> map.put(e.getKey(), e.getValue().getAsString()));
                if (!closed) { translations = Map.copyOf(map); PenguinSuit.LOG.info("[game-events] Chinese language ready (verified Minecraft 26.2 asset)"); }
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                if (!closed) PenguinSuit.LOG.warn("[game-events] Chinese language unavailable: {}; using server text. Reload to retry.", e.getClass().getSimpleName());
            } finally { synchronized (this) { loader = null; } }
        });
    }
    private static boolean verified(byte[] bytes) throws Exception {
        return bytes.length == SIZE && HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes)).equals(SHA1);
    }
    public boolean ready() { return !translations.isEmpty(); }
    public String render(Component component, boolean chinese) {
        Map<String,String> language=new HashMap<>(chinese?translations:Map.of());language.putAll(overrides);
        return EventFormat.clean(render(component, language, 0), 1400);
    }
    private String render(Component component, Map<String, String> language, int depth) {
        if (depth > 32) return "…";
        StringBuilder out = new StringBuilder();
        if (component.getContents() instanceof TranslatableContents t) {
            String fallback = Language.getInstance().getOrDefault(t.getKey(), t.getFallback() == null ? t.getKey() : t.getFallback());
            Object[] args = t.getArgs(); List<String> values = new ArrayList<>();
            for (Object arg : args) values.add(arg instanceof Component c ? render(c, language, depth + 1) : String.valueOf(arg));
            out.append(interpolate(language.getOrDefault(t.getKey(), fallback), values));
        } else component.getContents().visit(text -> { out.append(text); return Optional.empty(); });
        for (Component sibling : component.getSiblings()) {
            if (out.length() > 1600) break;
            out.append(render(sibling, language, depth + 1));
        }
        return out.length() > 1600 ? out.substring(0, 1600) + "…" : out.toString();
    }
    public static String interpolate(String pattern, List<String> args) {
        int[] next = {0};
        return ARG.matcher(pattern).replaceAll(m -> {
            if (m.group().equals("%%")) return "%";
            int index;
            try { index = m.group(1) == null ? next[0]++ : Integer.parseInt(m.group(1)) - 1; }
            catch (NumberFormatException e) { return java.util.regex.Matcher.quoteReplacement(m.group()); }
            return java.util.regex.Matcher.quoteReplacement(index >= 0 && index < args.size() ? args.get(index) : m.group());
        });
    }
    @Override public synchronized void close() { closed = true; if (loader != null) loader.interrupt(); }
}
