package dev.penguinsuit.bridge;

import java.util.List;

/** The only sender for an accepted QQ message. One recipient never gets both complete paths. */
public final class DeliveryRouter {
    public interface Recipient {
        boolean supportsMedia();
        void text(String message);
        void image(ChatBackend.Prepared image, String sender, String caption, String group, int index, int count);
    }
    public record Failure(int sentImages, Throwable cause) {}
    /** Successful calls enqueue packets; these counts are not client download/render acknowledgements. */
    public record Summary(int recipients, int mediaRecipients, int textRecipients, int partialRecipients,
                          int failedRecipients, int imagePackets) {}
    public static Summary deliver(List<? extends Recipient> recipients, List<ChatBackend.Prepared> ready, String fallback,
                               String sender, String caption, String groupId, int failures,
                               java.util.function.Consumer<Failure> onFailure) {
        String displayCaption = caption + (failures > 0 ? (caption.isEmpty() ? "" : " ")
                + dev.penguinsuit.config.RuntimeSettings.text(dev.penguinsuit.config.RuntimeSettings.get().messages.omittedImages,"count",Integer.toString(failures)) : "");
        int mediaRecipients = 0, textRecipients = 0, partialRecipients = 0, failedRecipients = 0, imagePackets = 0;
        for (Recipient recipient : recipients) {
            int sent = 0;
            boolean textAttempted = false;
            try {
                if (ready.isEmpty() || !recipient.supportsMedia()) {
                    textAttempted = true;
                    recipient.text(fallback + (ready.isEmpty() ? " "+dev.penguinsuit.config.RuntimeSettings.get().messages.imageLoadFailure : ""));
                    textRecipients++;
                    continue;
                }
                for (int i = 0; i < ready.size(); i++) {
                    recipient.image(ready.get(i), sender, i == 0 ? displayCaption : "", groupId, i, ready.size());
                    sent++;
                    imagePackets++;
                }
                mediaRecipients++;
            } catch (RuntimeException | LinkageError e) {
                onFailure.accept(new Failure(sent, e));
                // Never replay a partially sent group, and never retry an already attempted text.
                if (sent == 0 && !textAttempted) {
                    try {
                        recipient.text(fallback + " "+dev.penguinsuit.config.RuntimeSettings.get().messages.imageSendFailure);
                        textRecipients++;
                    } catch (RuntimeException | LinkageError ignored) { failedRecipients++; }
                } else if (sent > 0) {
                    partialRecipients++;
                } else {
                    failedRecipients++;
                }
            }
        }
        return new Summary(recipients.size(), mediaRecipients, textRecipients, partialRecipients, failedRecipients, imagePackets);
    }
    private DeliveryRouter() {}
}
