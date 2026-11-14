package dev.penguinsuit.media;

import dev.penguinsuit.config.BridgeConfig;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.*;
import java.util.Locale;

public final class MediaFetcher {
    public byte[] fetch(URI start, BridgeConfig cfg, long limit) throws IOException {
        return fetch(start,cfg,limit,false);
    }
    public byte[] fetchCataloguedEmoji(URI start,BridgeConfig cfg,long limit)throws IOException {
        if(dev.penguinsuit.emoji.QqEmojiCatalog.bySource(start)==null)throw new MediaException("unknown_emoji_source");
        return fetch(start,cfg,limit,true);
    }
    private byte[] fetch(URI start,BridgeConfig cfg,long limit,boolean emoji)throws IOException {
        URI current = start;
        long deadline = System.nanoTime() + cfg.downloadTimeoutMillis * 1_000_000L;
        for (int hop = 0; hop <= cfg.maxRedirects; hop++) {
            if(emoji)validateEmojiUri(start,current);else validateUri(current, cfg);
            for (InetAddress address : InetAddress.getAllByName(current.getHost()))
                if (!isPublic(address)) throw new MediaException("blocked_address");
            HttpURLConnection connection = (HttpURLConnection) current.toURL().openConnection(Proxy.NO_PROXY);
            try {
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(Math.min(cfg.connectTimeoutMillis, remaining(deadline)));
                connection.setReadTimeout(remaining(deadline));
                connection.setRequestProperty("User-Agent", "PenguinSuit/0.1 (Minecraft 26.2)");
                connection.setRequestProperty("Accept", "image/png,image/jpeg,image/gif");
                connection.setRequestProperty("Accept-Encoding", "identity");
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || hop == cfg.maxRedirects) throw new MediaException("redirect_limit");
                    try { current = current.resolve(location); }
                    catch (IllegalArgumentException e) { throw new MediaException("invalid_redirect"); }
                    continue;
                }
                if (status != 200) throw new MediaException("http_" + status);
                String encoding = connection.getContentEncoding();
                if (encoding != null && !encoding.equalsIgnoreCase("identity")) throw new MediaException("encoded_body");
                if (connection.getContentLengthLong() > limit) throw new MediaException("file_too_large");
                try (var input = connection.getInputStream(); var output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted()) throw new MediaException("cancelled");
                        connection.setReadTimeout(remaining(deadline));
                        if ((long) output.size() + count > limit) throw new MediaException("file_too_large");
                        output.write(buffer, 0, count);
                    }
                    return output.toByteArray();
                }
            } finally { connection.disconnect(); }
        }
        throw new MediaException("redirect_limit");
    }
    /** This separate, exact catalog policy never expands attachment host permissions. */
    public static void validateEmojiUri(URI original,URI current)throws MediaException {
        if(dev.penguinsuit.emoji.QqEmojiCatalog.bySource(original)==null||!original.equals(current))
            throw new MediaException("blocked_emoji_url");
    }
    private static int remaining(long deadline) throws MediaException {
        long remaining = (deadline - System.nanoTime()) / 1_000_000L;
        if (remaining < 1) throw new MediaException("download_timeout");
        return (int) Math.min(remaining, Integer.MAX_VALUE);
    }
    public static void validateUri(URI uri, BridgeConfig cfg) throws MediaException {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getFragment() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)) throw new MediaException("blocked_url");
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        boolean allowed = cfg.allowedHostSuffixes.stream().map(h -> h.toLowerCase(Locale.ROOT))
                .anyMatch(h -> host.equals(h) || host.endsWith("." + h));
        if (!allowed) throw new MediaException("blocked_host");
    }
    public static boolean isPublic(InetAddress ip) {
        if (ip.isAnyLocalAddress() || ip.isLoopbackAddress() || ip.isLinkLocalAddress()
                || ip.isSiteLocalAddress() || ip.isMulticastAddress()) return false;
        byte[] b = ip.getAddress();
        if (b.length == 4) {
            int a = b[0] & 255, c = b[1] & 255;
            return a != 0 && a != 127 && a < 224 && !(a == 100 && c >= 64 && c <= 127)
                    && !(a == 198 && (c == 18 || c == 19)) && !(a == 192 && c == 0)
                    && !(a == 198 && c == 51) && !(a == 203 && c == 0);
        }
        // Restrict IPv6 to global unicast; exclude documentation and transition ranges.
        return b.length == 16 && (b[0] & 0xe0) == 0x20
                && !((b[0] & 255) == 0x20 && (b[1] & 255) == 0x02)
                && !((b[0] & 255) == 0x20 && (b[1] & 255) == 0x01
                && (((b[2] & 255) == 0x0d && (b[3] & 255) == 0xb8) || (b[2] == 0 && b[3] == 0)));
    }
}
