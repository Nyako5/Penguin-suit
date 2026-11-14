package dev.penguinsuit.bridge;

import com.huhobot.penguin.qq.GroupMessage;
import dev.penguinsuit.media.AttachmentResolver;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public record MessageSnapshot(String id, String group, String user, String name, String role, String timestamp,
                              List<Map<?, ?>> attachments, List<AttachmentResolver.Attachment> media,
                              dev.penguinsuit.mention.MentionService.Incoming mention,
                              dev.penguinsuit.reply.QqReplyData reply,
                              dev.penguinsuit.emoji.QqEmojiMessage.Plan emojis) {
    public MessageSnapshot(String id,String group,String user,String name,String role,String timestamp,
            List<Map<?,?>> attachments,List<AttachmentResolver.Attachment> media,
            dev.penguinsuit.mention.MentionService.Incoming mention,dev.penguinsuit.reply.QqReplyData reply){
        this(id,group,user,name,role,timestamp,attachments,media,mention,reply,dev.penguinsuit.emoji.QqEmojiMessage.Plan.EMPTY);
    }
    public MessageSnapshot(String id,String group,String user,String name,String role,String timestamp,
                           List<Map<?,?>> attachments,List<AttachmentResolver.Attachment> media) {
        this(id,group,user,name,role,timestamp,attachments,media,null,dev.penguinsuit.reply.QqReplyData.NONE);
    }
    public MessageSnapshot(String id,String group,String user,String name,String role,String timestamp,
            List<Map<?,?>> attachments,List<AttachmentResolver.Attachment> media,dev.penguinsuit.mention.MentionService.Incoming mention){
        this(id,group,user,name,role,timestamp,attachments,media,mention,dev.penguinsuit.reply.QqReplyData.NONE);
    }
    public MessageSnapshot withMention(dev.penguinsuit.mention.MentionService.Incoming value) {
        return new MessageSnapshot(id,group,user,name,role,timestamp,attachments,media,value,reply,emojis);
    }
    public MessageSnapshot withEmojis(dev.penguinsuit.emoji.QqEmojiMessage.Plan value) {
        return new MessageSnapshot(id,group,user,name,role,timestamp,attachments,media,mention,reply,value);
    }
    public String mediaFallbackSuffix() {
        return emojis.emojiOnlyMedia(media)?"":" [图片]";
    }
    public static MessageSnapshot capture(GroupMessage msg) {
        List<Map<?, ?>> copy = new ArrayList<>();
        if (msg.getAttachments() != null) {
            for (var map : msg.getAttachments().stream().limit(64).toList()) {
                if (map == null) continue;
                Map<String, String> fields = new HashMap<>();
                for (String field : List.of("url", "content_type", "asr_refer_text"))
                    if (map.get(field) instanceof String value) fields.put(field, value);
                copy.add(Collections.unmodifiableMap(fields));
            }
        }
        return new MessageSnapshot(msg.getId(), msg.getGroupId(), msg.getUserId(),
                msg.getUsername() == null ? msg.getUserId() : msg.getUsername(), msg.getMemberRole(),
                msg.getTimestamp(), List.copyOf(copy), AttachmentResolver.snapshot(copy),null,dev.penguinsuit.reply.QqReplyData.capture(msg));
    }
    public GroupMessage filteredMessage(String filtered) {
        return new GroupMessage(id, group, filtered, user, name, role, timestamp, attachments);
    }
    public String key() { return group + "\n" + id; }
}
