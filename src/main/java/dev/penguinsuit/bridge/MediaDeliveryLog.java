package dev.penguinsuit.bridge;

import java.util.regex.Pattern;
import org.slf4j.Logger;

/** One server-side log entry per authorized QQ media message, never a second chat broadcast. */
public final class MediaDeliveryLog {
    private static final Pattern ANSI = Pattern.compile("\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\))");
    private static final Pattern LEGACY_COLOR = Pattern.compile("(?i)§[0-9a-fk-orx]");
    private static final Pattern URL = Pattern.compile("(?i)(?:https?://|www\\.)[^\\s<>]+");
    private static final Pattern LONG_NUMBER = Pattern.compile("[0-9]{5,}");

    public static void write(Logger log, String sender, int prepared, int failedOrOmitted, DeliveryRouter.Summary result) {
        if(dev.penguinsuit.config.BridgeLog.detailed("media"))log.info("{}", format(sender, prepared, failedOrOmitted, result));
        else log.info("[media] QQ->MC <{}> [图片×{}] recipients={} mediaRecipients={} textRecipients={} failedImages={} failedRecipients={}",
                safeSender(sender),prepared+failedOrOmitted,result.recipients(),result.mediaRecipients(),result.textRecipients(),failedOrOmitted,result.failedRecipients());
    }

    public static String format(String sender, int prepared, int failedOrOmitted, DeliveryRouter.Summary result) {
        return "[Penguin suit] <" + safeSender(sender) + "> [图片×" + (prepared + failedOrOmitted)
                + "] prepared=" + prepared + " failedOrOmitted=" + failedOrOmitted
                + " recipients=" + result.recipients() + " mediaRecipients=" + result.mediaRecipients()
                + " textRecipients=" + result.textRecipients() + " partialRecipients=" + result.partialRecipients()
                + " failedRecipients=" + result.failedRecipients() + " imagePackets=" + result.imagePackets();
    }

    public static String safeSender(String sender) {
        if (sender == null || sender.isBlank()) return "QQ";
        String value = ANSI.matcher(sender).replaceAll("");
        value = LEGACY_COLOR.matcher(value).replaceAll("");
        value = value.replaceAll("[\\p{Cntrl}\\p{Cf}\\p{Zl}\\p{Zp}<>§]", " ");
        value = URL.matcher(value).replaceAll("[链接已隐藏]");
        value = LONG_NUMBER.matcher(value).replaceAll("[数字已隐藏]");
        value = value.replaceAll("\\s+", " ").strip();
        if (value.isEmpty()) return "QQ";
        if (value.codePointCount(0, value.length()) > 256)
            value = value.substring(0, value.offsetByCodePoints(0, 256)) + "…";
        return value;
    }
    private MediaDeliveryLog() {}
}
