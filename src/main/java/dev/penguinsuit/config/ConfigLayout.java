package dev.penguinsuit.config;

import com.google.gson.*;
import java.io.IOException;
import java.util.*;

/** Versioned file layout, independent of runtime field names. Legacy keys are read-only aliases. */
public final class ConfigLayout {
    public static final int VERSION=2;
    private static final String KEYS="""
            enabled enabled
            forwarding.qqToGame QQtogame.enabled
            forwarding.qqText QQtogame.text
            forwarding.qqImages QQtogame.images
            forwarding.qqMentions QQtogame.mentions
            forwarding.qqCommands QQtogame.commands
            forwarding.qqEvents QQtogame.groupEvents
            messages.fromGroup QQtogame.format
            qqEmojiImagesEnabled QQtogame.emoji.enabled
            media.emojiMode QQtogame.emoji.mode
            media.incomingGifMode QQtogame.gifMode
            maxDownloadBytes QQtogame.download.maxDownloadBytes
            connectTimeoutMillis QQtogame.download.connectTimeoutMillis
            downloadTimeoutMillis QQtogame.download.downloadTimeoutMillis
            maxRedirects QQtogame.download.maxRedirects
            allowedHostSuffixes QQtogame.download.allowedHostSuffixes
            workerThreads QQtogame.queue.workerThreads
            queueCapacity QQtogame.queue.capacity
            dedupSeconds QQtogame.queue.dedupSeconds
            minSenderIntervalMillis QQtogame.queue.minSenderIntervalMillis
            minGroupIntervalMillis QQtogame.queue.minGroupIntervalMillis
            forwarding.gameToQq gametoQQ.enabled
            forwarding.gameText gametoQQ.text
            forwarding.gameImages gametoQQ.images
            forwarding.gameMentions gametoQQ.mentions
            messages.fromGame gametoQQ.format
            media.outgoingGifMode gametoQQ.gifMode
            gameToQqQueueCapacity gametoQQ.upload.queueCapacity
            gameToQqMaxBytes gametoQQ.upload.maxBytes
            gameToQqMaxPendingBytes gametoQQ.upload.maxPendingBytes
            gameToQqTimeoutSeconds gametoQQ.upload.timeoutSeconds
            gameToQqMaterialMaxImages gametoQQ.materialCache.maxImages
            gameToQqMaterialTtlMinutes gametoQQ.materialCache.ttlMinutes
            gameToQqMaterialGraceSeconds gametoQQ.materialCache.graceSeconds
            forwarding.gameEvents gametoQQ.events.enabled
            forwarding.playerJoin gametoQQ.events.playerJoin.enabled
            messages.playerJoin gametoQQ.events.playerJoin.format
            forwarding.playerLeave gametoQQ.events.playerLeave.enabled
            messages.playerLeave gametoQQ.events.playerLeave.format
            advancementEventsEnabled gametoQQ.events.advancement.enabled
            events.advancementTaskFormat gametoQQ.events.advancement.formats.TASK
            events.advancementGoalFormat gametoQQ.events.advancement.formats.GOAL
            events.advancementChallengeFormat gametoQQ.events.advancement.formats.CHALLENGE
            events.advancementTypes gametoQQ.events.advancement.types
            events.allowedAdvancements gametoQQ.events.advancement.allowedIds
            events.excludedAdvancements gametoQQ.events.advancement.excludedIds
            events.allowedNamespaces gametoQQ.events.advancement.allowedNamespaces
            events.excludedNamespaces gametoQQ.events.advancement.excludedNamespaces
            deathEventsEnabled gametoQQ.events.death.enabled
            deathEventFormat gametoQQ.events.death.format
            gameEventsRespectGameRules gametoQQ.events.respectGameRules
            gameEventsChinese gametoQQ.events.chinese
            events.queueCapacity gametoQQ.events.queueCapacity
            events.timeoutSeconds gametoQQ.events.timeoutSeconds
            events.minIntervalMillis gametoQQ.events.minIntervalMillis
            events.translations gametoQQ.events.translations
            maxImagesPerMessage media.maxImagesPerMessage
            maxDimension media.maxDimension
            maxPixels media.maxPixels
            maxGifFrames media.maxGifFrames
            maxGifDecodedPixels media.maxGifDecodedPixels
            maxOwnedCacheBytes cache.maxBytes
            maxOwnedImages cache.maxImages
            cacheTtlMinutes cache.ttlMinutes
            cacheDeliveryGraceSeconds cache.deliveryGraceSeconds
            media.cacheCleanupSeconds cache.cleanupSeconds
            mentions.enabled mentions.enabled
            mentions.soundEnabled mentions.soundEnabled
            mentions.color mentions.color
            mentions.sound mentions.sound
            mentions.volume mentions.volume
            mentions.pitch mentions.pitch
            mentions.chatCompletions mentions.chatCompletions
            mentions.commandCompletions mentions.commandCompletions
            mentions.sort mentions.sort
            mentions.boundAliases mentions.boundAliases
            mentions.refreshMillis mentions.refreshMillis
            mentions.auditTimeoutSeconds mentions.auditTimeoutSeconds
            repliesEnabled replies.enabled
            replyRetentionMinutes replies.retentionMinutes
            replyMaxEntries replies.maxEntries
            replyMaxBytes replies.maxBytes
            authenticationEnabled authentication.enabled
            authentication.codeTtlSeconds authentication.codeTtlSeconds
            authentication.rateWindowSeconds authentication.rateWindowSeconds
            authentication.maxAttempts authentication.maxAttempts
            authentication.maxPending authentication.maxPending
            blockedGroups filters.blockedGroups
            blockedUsers filters.blockedMembers
            qqBotExcludedMembers filters.excludedBots
            filters.blockedPlayerUuids filters.blockedPlayerUUIDs
            filters.blockedPlayerNames filters.blockedPlayerNames
            fakePlayerFilter.enabled filters.fakePlayers.enabled
            fakePlayerFilter.namePrefixes filters.fakePlayers.namePrefixes
            fakePlayerFilter.nameSuffixes filters.fakePlayers.nameSuffixes
            fakePlayerFilter.exactNames filters.fakePlayers.exactNames
            fakePlayerFilter.excludedPlayerUuids filters.fakePlayers.excludedPlayerUUIDs
            fakePlayerFilter.caseSensitive filters.fakePlayers.caseSensitive
            fakePlayerFilter.allowedPlayerUuids filters.fakePlayers.allowedPlayerUUIDs
            directory.inactiveRetentionDays directory.inactiveRetentionDays
            directory.cleanupIntervalSeconds directory.cleanupIntervalSeconds
            network.qqConnectTimeoutSeconds network.QQ.connectTimeoutSeconds
            network.qqRequestTimeoutSeconds network.QQ.requestTimeoutSeconds
            messages.mentionFailure messages.mentionFailure
            messages.incomingFailure messages.incomingFailure
            messages.authentication messages.authentication
            messages.imageLoadFailure messages.imageLoadFailure
            messages.imageSendFailure messages.imageSendFailure
            messages.omittedImages messages.omittedImages
            messages.outgoingFailure messages.outgoingFailure
            messages.mediaSenderFallback messages.mediaSenderFallback
            logs.debugEnabled logs.debugEnabled
            logs.media logs.media
            logs.emoji logs.emoji
            logs.mentions logs.mentions
            logs.authentication logs.authentication
            logs.events logs.events
            replyDebugLog logs.replies
            """;
    private static final Map<String,String> PATHS;
    static {
        var paths=new LinkedHashMap<String,String>();
        for(String line:KEYS.strip().split("\\R")){var pair=line.strip().split(" +");paths.put(pair[0],pair[1]);}
        PATHS=Collections.unmodifiableMap(paths);
    }
    public static String canonicalKey(String key){return key.equals("gameToQqEnabled")?"gametoQQ.enabled":PATHS.getOrDefault(key,key);}
    static JsonElement get(JsonObject root,String path)throws IOException{
        String[] parts=path.split("\\.");JsonElement current=root;
        for(String part:parts){
            if(!current.isJsonObject())throw new IOException("Configuration category must be an object: "+path);
            current=current.getAsJsonObject().get(part);if(current==null)return null;
        }
        return current;
    }
    static void put(JsonObject root,String path,JsonElement value){
        String[] parts=path.split("\\.");
        for(int i=0;i<parts.length-1;i++){
            if(!root.has(parts[i]))root.add(parts[i],new JsonObject());
            root=root.getAsJsonObject(parts[i]);
        }
        root.add(parts[parts.length-1],value.deepCopy());
    }
    private static void remove(JsonObject root,String path){
        int dot=path.indexOf('.');if(dot<0){root.remove(path);return;}
        String part=path.substring(0,dot);if(!root.has(part)||!root.get(part).isJsonObject())return;
        var child=root.getAsJsonObject(part);remove(child,path.substring(dot+1));if(child.isEmpty())root.remove(part);
    }
    /** Validate both spellings before choosing values. Contradictory aliases never silently win. */
    static JsonObject normalize(JsonElement input)throws IOException{
        if(input==null||!input.isJsonObject())throw new IOException("Bridge configuration must be an object");
        var raw=input.getAsJsonObject().deepCopy();ConfigTypes.validate(raw);
        // Retired flags are discarded rather than preserved as unknown editable options.
        for(String key:List.of("filters.blockExcludedQqSenders","filters.blockExcludedQQSenders","filters.blockFakeChatSenders"))remove(raw,key);
        JsonElement legacyFormat=get(raw,"advancementEventFormat"), groupedFormat=get(raw,"gametoQQ.events.advancement.format");
        if(legacyFormat!=null&&groupedFormat!=null&&!legacyFormat.equals(groupedFormat))throw new IOException("Conflicting advancement format aliases");
        JsonElement oldFormat=groupedFormat!=null?groupedFormat:legacyFormat;
        if(oldFormat!=null){
            if(!oldFormat.isJsonPrimitive()||!oldFormat.getAsJsonPrimitive().isString())throw new IOException("Invalid legacy advancement format");
            String text=oldFormat.getAsString();
            dev.penguinsuit.events.EventFormat.validate(text,Set.of("player","type","title","id"));
            if(!Set.of("[进度] {player} 获得了{type}【{title}】","{player}完成了{type}[{title}]").contains(text))
                for(String type:List.of("TASK","GOAL","CHALLENGE")){
                    String key="gametoQQ.events.advancement.formats."+type;
                    if(get(raw,key)==null)put(raw,key,oldFormat);
                }
        }
        remove(raw,"advancementEventFormat");remove(raw,"gametoQQ.events.advancement.format");
        if(raw.has("schemaVersion")){
            var v=raw.get("schemaVersion");
            try{if(!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isNumber()||v.getAsBigDecimal().intValueExact()!=VERSION)throw new IOException("Unsupported config schemaVersion; expected "+VERSION);}
            catch(ArithmeticException|NumberFormatException e){throw new IOException("Invalid config schemaVersion",e);}
        }
        JsonObject result=new JsonObject(),unknown=raw.deepCopy();unknown.remove("schemaVersion");
        for(var pair:PATHS.entrySet()){
            String old=pair.getKey(),key=pair.getValue();JsonElement legacy=get(raw,old),modern=get(raw,key);
            if(old.equals("forwarding.gameToQq")&&raw.has("gameToQqEnabled")){
                boolean allowed=raw.get("gameToQqEnabled").getAsBoolean()&&(legacy==null||legacy.getAsBoolean());
                legacy=new JsonPrimitive(allowed);
            }
            if(legacy!=null&&modern!=null&&!legacy.equals(modern))throw new IOException("Conflicting configuration keys: "+old+" and "+key+"; keep only "+key);
            JsonElement value=modern!=null?modern:legacy;if(value!=null)put(result,key,value);
            remove(unknown,old);remove(unknown,key);
        }
        unknown.remove("gameToQqEnabled");BridgeConfig.mergeMissing(result,unknown);
        // Validate types at their runtime destinations, including canonical fields absent from old classes.
        ConfigTypes.validate(internal(result));
        return result;
    }
    static JsonObject internal(JsonObject normalized)throws IOException{
        var result=new JsonObject();
        for(var pair:PATHS.entrySet()){var value=get(normalized,pair.getValue());if(value!=null)put(result,pair.getKey(),value);}
        var outbound=get(normalized,"gametoQQ.enabled");if(outbound!=null)result.add("gameToQqEnabled",outbound.deepCopy());
        return result;
    }
    static JsonObject external(JsonObject runtime)throws IOException{
        var result=new JsonObject();
        for(var pair:PATHS.entrySet()){
            var value=get(runtime,pair.getKey());
            if(value==null)throw new IOException("Missing internal configuration field: "+pair.getKey());
            if(pair.getKey().equals("forwarding.gameToQq"))value=new JsonPrimitive(value.getAsBoolean()&&runtime.get("gameToQqEnabled").getAsBoolean());
            put(result,pair.getValue(),value);
        }
        return result;
    }
    private ConfigLayout(){}
}
