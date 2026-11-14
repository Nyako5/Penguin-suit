package dev.penguinsuit.config;

import java.io.IOException;
import java.util.*;
import dev.penguinsuit.events.EventFormat;

/** Runtime options; ConfigLayout owns the versioned user-facing names. */
public final class FeatureOptions {
    public static final class Forwarding {
        public boolean qqToGame = true, gameToQq = true;
        public boolean qqText = true, qqImages = true, gameText = true, gameImages = true;
        public boolean qqMentions = true, gameMentions = true;
        public boolean gameEvents = true, playerJoin = true, playerLeave = true;
        public boolean qqCommands = true, qqEvents = true;
    }
    public static final class Mentions {
        public boolean enabled = true, soundEnabled = true;
        public String color = "aqua", sound = "minecraft:entity.experience_orb.pickup";
        public float volume = 2, pitch = 2;
        public boolean chatCompletions = true, commandCompletions = true;
        public String sort = "NAME", boundAliases = "BOTH";
        public int refreshMillis = 1000, auditTimeoutSeconds = 30;
    }
    public static final class Logs {
        public boolean debugEnabled = false;
        public String media = "DETAILED", emoji = "DETAILED", mentions = "DETAILED", authentication = "DETAILED", events = "DETAILED";
    }
    public static final class Filters {
        public List<String> blockedPlayerUuids = List.of(), blockedPlayerNames = List.of();
    }
    public static final class Media {
        public String emojiMode = "ANIMATED", incomingGifMode = "ANIMATED", outgoingGifMode = "ANIMATED";
        public int cacheCleanupSeconds = 60;
    }
    public static final class Events {
        public String advancementTaskFormat = "{player}取得了进度[{title}]";
        public String advancementGoalFormat = "{player}达成了目标[{title}]";
        public String advancementChallengeFormat = "{player}完成了挑战[{title}]";
        public String advancementFormat(String type) {
            return switch(type) { case "TASK" -> advancementTaskFormat; case "GOAL" -> advancementGoalFormat;
                case "CHALLENGE" -> advancementChallengeFormat; default -> throw new IllegalArgumentException("Unknown advancement type"); };
        }
        public List<String> advancementTypes = List.of("TASK","GOAL","CHALLENGE");
        public List<String> allowedAdvancements = List.of(), excludedAdvancements = List.of();
        public List<String> allowedNamespaces = List.of(), excludedNamespaces = List.of();
        /** Zero inherits existing media capacity/timeout for upgraded configurations. */
        public int queueCapacity = 0, timeoutSeconds = 0, minIntervalMillis = 0;
        public Map<String,String> translations = Map.of();
    }
    public static final class Authentication {
        public int codeTtlSeconds = 300, rateWindowSeconds = 60, maxAttempts = 10, maxPending = 256;
    }
    public static final class Network {
        public int qqConnectTimeoutSeconds = 10, qqRequestTimeoutSeconds = 30;
    }
    public static final class Directory {
        /** Zero disables automatic expiry. Bound, pending-bound and online identities are never pruned. */
        public int inactiveRetentionDays = 0, cleanupIntervalSeconds = 3600;
    }
    public static final class Messages {
        public String mentionFailure = "发送失败：{reason}";
        public String incomingFailure = "转发失败：{reason}";
        public String authentication = "[绑定] {message}";
        public String imageLoadFailure = "[图片下载失败]", imageSendFailure = "[图片发送失败]";
        public String omittedImages = "[{count} 张图片未能加载]";
        public String outgoingFailure = "转发失败：{message}";
        public String mediaSenderFallback = "[QQ] {name}";
        /** Empty inherits Penguin, so existing custom templates are never overwritten. */
        public String fromGroup = "", fromGame = "";
        public String playerJoin = "", playerLeave = "";
    }
    public static void validate(BridgeConfig c) throws IOException {
        if(c.forwarding==null||c.mentions==null||c.logs==null||c.filters==null||c.media==null||c.events==null||c.authentication==null||c.network==null||c.directory==null||c.messages==null)throw new IOException("Feature configuration objects must not be null");
        var m=c.mentions;
        choice(m.color,"black","dark_blue","dark_green","dark_aqua","dark_red","dark_purple","gold","gray","dark_gray","blue","green","aqua","red","light_purple","yellow","white","none");
        if(m.sound==null||!m.sound.matches("[a-z0-9_.-]+:[a-z0-9/._-]+"))throw new IOException("Invalid mention sound resource ID");
        if(!Float.isFinite(m.volume)||m.volume<0||m.volume>4||!Float.isFinite(m.pitch)||m.pitch<0.5F||m.pitch>2)throw new IOException("Mention volume must be 0-4 and pitch 0.5-2");
        choice(m.sort,"NAME","ONLINE_FIRST","BOUND_FIRST");choice(m.boundAliases,"BOTH","MC","QQ");
        range(m.refreshMillis,250,60000,"mentions.refreshMillis");range(m.auditTimeoutSeconds,5,120,"mentions.auditTimeoutSeconds");
        for(String s:List.of(nonnull(c.logs.media),nonnull(c.logs.emoji),nonnull(c.logs.mentions),nonnull(c.logs.authentication),nonnull(c.logs.events)))choice(s,"SUMMARY","DETAILED");
        uuidList(c.filters.blockedPlayerUuids);names(c.filters.blockedPlayerNames);
        choice(c.media.emojiMode,"ANIMATED","STATIC","TEXT");choice(c.media.incomingGifMode,"ANIMATED","STATIC","TEXT");choice(c.media.outgoingGifMode,"ANIMATED","STATIC","TEXT");range(c.media.cacheCleanupSeconds,5,3600,"media.cacheCleanupSeconds");
        for(String type:List.of("TASK","GOAL","CHALLENGE"))EventFormat.validate(c.events.advancementFormat(type),Set.of("player","type","title","id"));
        list(c.events.advancementTypes);for(String t:c.events.advancementTypes)choice(t,"TASK","GOAL","CHALLENGE");
        for(var l:List.of(nonnullList(c.events.allowedAdvancements),nonnullList(c.events.excludedAdvancements)))for(String s:l)if(!s.matches("[a-z0-9_.-]+:[a-z0-9/._-]+"))throw new IOException("Invalid advancement ID");
        for(var l:List.of(nonnullList(c.events.allowedNamespaces),nonnullList(c.events.excludedNamespaces)))for(String s:l)if(!s.matches("[a-z0-9_.-]+"))throw new IOException("Invalid advancement namespace");
        range(c.events.queueCapacity,0,256,"events.queueCapacity");range(c.events.timeoutSeconds,0,600,"events.timeoutSeconds");range(c.events.minIntervalMillis,0,60000,"events.minIntervalMillis");
        if(c.events.translations==null||c.events.translations.size()>4096)throw new IOException("Invalid event translations");
        for(var e:c.events.translations.entrySet())if(e.getKey()==null||!e.getKey().matches("[a-zA-Z0-9_.:/-]{1,256}")||e.getValue()==null||e.getValue().length()>1024||e.getValue().codePoints().anyMatch(Character::isISOControl))throw new IOException("Invalid event translation entry");
        range(c.authentication.codeTtlSeconds,60,1800,"authentication.codeTtlSeconds");range(c.authentication.rateWindowSeconds,10,600,"authentication.rateWindowSeconds");range(c.authentication.maxAttempts,1,30,"authentication.maxAttempts");range(c.authentication.maxPending,1,1024,"authentication.maxPending");
        range(c.network.qqConnectTimeoutSeconds,1,30,"network.qqConnectTimeoutSeconds");range(c.network.qqRequestTimeoutSeconds,5,120,"network.qqRequestTimeoutSeconds");
        range(c.directory.inactiveRetentionDays,0,3650,"directory.inactiveRetentionDays");range(c.directory.cleanupIntervalSeconds,60,86400,"directory.cleanupIntervalSeconds");
        var t=c.messages;
        EventFormat.validate(t.mentionFailure,Set.of("reason"));EventFormat.validate(t.incomingFailure,Set.of("reason"));EventFormat.validate(t.authentication,Set.of("message"));EventFormat.validate(t.outgoingFailure,Set.of("message"));
        EventFormat.validate(t.imageLoadFailure,Set.of());EventFormat.validate(t.imageSendFailure,Set.of());EventFormat.validate(t.omittedImages,Set.of("count"));EventFormat.validate(t.mediaSenderFallback,Set.of("name"));
        for(String s:List.of(nonnull(t.fromGroup),nonnull(t.fromGame)))if(!s.isEmpty()){EventFormat.validate(s,Set.of("name","message"));if(!s.contains("{name}")||!s.contains("{message}"))throw new IOException("Chat format requires {name} and {message}");}
        for(String s:List.of(nonnull(t.playerJoin),nonnull(t.playerLeave)))if(!s.isEmpty())EventFormat.validate(s,Set.of("name","server"));
    }
    private static String nonnull(String s)throws IOException{if(s==null)throw new IOException("Null setting");return s;}
    private static List<String> nonnullList(List<String> l)throws IOException{list(l);return l;}
    public static void list(List<String> l)throws IOException{if(l==null||l.size()>4096||l.stream().anyMatch(Objects::isNull))throw new IOException("Invalid setting list");}
    public static void names(List<String> l)throws IOException{list(l);for(String n:l)if(!n.matches("[A-Za-z0-9_]{1,16}"))throw new IOException("Invalid player name");}
    public static void uuidList(List<String> l)throws IOException{list(l);for(String s:l)try{if(!UUID.fromString(s).toString().equalsIgnoreCase(s))throw new IllegalArgumentException();}catch(RuntimeException e){throw new IOException("Invalid player UUID");}}
    private static void choice(String v,String... values)throws IOException{if(v==null||!Arrays.asList(values).contains(v))throw new IOException("Invalid choice: "+v);}
    private static void range(int v,int min,int max,String field)throws IOException{if(v<min||v>max)throw new IOException(field+" must be "+min+"-"+max);}
    private FeatureOptions(){}
}
