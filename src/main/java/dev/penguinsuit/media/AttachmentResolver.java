package dev.penguinsuit.media;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class AttachmentResolver {
    public record Attachment(String url, String contentType) {}
    public record Resolution(List<URI> images, int omitted) {}

    public static List<Attachment> snapshot(List<? extends Map<?, ?>> attachments) {
        if (attachments == null) return List.of();
        List<Attachment> result = new ArrayList<>();
        // Attachment URLs only. Known system emojis are resolved separately through a pinned catalog.
        for (Map<?, ?> a : attachments.stream().limit(64).toList()) {
            if (a == null) continue;
            if (a.get("url") instanceof String url && a.get("content_type") instanceof String type)
                result.add(new Attachment(url, type));
        }
        return List.copyOf(result);
    }

    public static Resolution resolve(List<Attachment> attachments, int limit) {
        List<URI> images = new ArrayList<>();
        int omitted = 0;
        for (Attachment a : attachments) {
            if (!a.contentType().toLowerCase(java.util.Locale.ROOT).startsWith("image/")) continue;
            if (images.size() >= limit) { omitted++; continue; }
            try {
                String value = a.url().trim();
                if (value.startsWith("//")) value = "https:" + value;
                else if (!value.contains("://")) value = "https://" + value;
                URI uri = URI.create(value);
                if (uri.getHost() == null || !"https".equalsIgnoreCase(uri.getScheme())) { omitted++; continue; }
                images.add(uri);
            } catch (IllegalArgumentException e) { omitted++; }
        }
        return new Resolution(List.copyOf(images), omitted);
    }

    public static Resolution resolve(List<Attachment> attachments,List<URI> emojis,int limit) {
        var original=resolve(attachments,limit);List<URI> images=new ArrayList<>(original.images());int omitted=original.omitted();
        // QQ attachment events do not carry reliable interleaving offsets. Preserve attachment order
        // and then emoji occurrence order; keep the readable names in the caption at their positions.
        for(URI emoji:emojis){if(images.size()<limit)images.add(emoji);else omitted++;}
        return new Resolution(List.copyOf(images),omitted);
    }

    public static String cleanCaption(String filtered) {
        return filtered.replaceAll("<faceType=6,faceId=\"0\",ext=\"[^\"]+\">", "")
                .replaceAll("<faceType=[^>]+>", "[表情]")
                .replaceAll("<[^>]+>", "").trim();
    }
    private AttachmentResolver() {}
}
