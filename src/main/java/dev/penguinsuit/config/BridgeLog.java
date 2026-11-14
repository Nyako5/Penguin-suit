package dev.penguinsuit.config;

public final class BridgeLog {
    public static boolean detailed(String module){var l=RuntimeSettings.get().logs;return l.debugEnabled&&"DETAILED".equals(switch(module){case "media"->l.media;case "emoji"->l.emoji;case "mentions"->l.mentions;case "authentication"->l.authentication;case "events"->l.events;default->throw new IllegalArgumentException(module);});}
    public static boolean replies(BridgeConfig config){return config.logs.debugEnabled&&config.replyDebugLog;}
    private BridgeLog(){}
}
