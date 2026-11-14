package dev.penguinsuit.config;

import java.util.*;
import dev.penguinsuit.events.EventFormat;

/** Snapshot shared by hooks whose owning objects belong to the upstream mods. */
public final class RuntimeSettings {
    private static volatile BridgeConfig current = new BridgeConfig();
    public static BridgeConfig get(){return current;}
    public static void apply(BridgeConfig config){current=config;}
    public static boolean incoming(String group,String member,boolean image){
        var c=current;return !c.enabled||(c.forwarding.qqToGame&&!c.blockedGroups.contains(group)&&!c.blockedUsers.contains(member)
                &&(image?(c.forwarding.qqImages||c.forwarding.qqText):c.forwarding.qqText));
    }
    public static boolean outgoing(UUID uuid,String name,boolean image){
        var c=current;return !c.enabled||(c.gameToQqEnabled&&c.forwarding.gameToQq&&(image?c.forwarding.gameImages:c.forwarding.gameText)&&playerAllowed(uuid,name));
    }
    public static boolean incomingEvent(String group,String member){
        var c=current;if(!c.enabled)return true;
        if(!c.forwarding.qqToGame||!c.forwarding.qqEvents||c.blockedGroups.contains(group)||c.blockedUsers.contains(member))return false;
        return true;
    }
    public static boolean playerAllowed(UUID uuid,String name){
        var c=current;if(!c.enabled)return true;
        return (uuid==null||c.filters.blockedPlayerUuids.stream().noneMatch(s->s.equalsIgnoreCase(uuid.toString())))
                &&c.filters.blockedPlayerNames.stream().noneMatch(s->s.equalsIgnoreCase(name));
    }
    public static boolean mentions(){return current.enabled&&current.mentions.enabled;}
    public static boolean nativeQqMentions(){return mentions()&&current.forwarding.gameMentions;}
    public static String text(String template,String key,String value){return EventFormat.render(template,Map.of(key,value));}
    public static net.minecraft.ChatFormatting color(){return current.mentions.color.equals("none")?null:net.minecraft.ChatFormatting.valueOf(current.mentions.color.toUpperCase(Locale.ROOT));}
    public static String colorCode(){var c=color();return c==null?"":c.toString();}
    private RuntimeSettings(){}
}
