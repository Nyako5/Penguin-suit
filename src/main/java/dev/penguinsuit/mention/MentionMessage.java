package dev.penguinsuit.mention;

import java.util.*;
import java.util.regex.*;
import dev.penguinsuit.reverse.QqMarkdown;

/** Immutable spans: names in ordinary prose are never a replacement target. */
public final class MentionMessage {
    private static final Pattern EXPLICIT = Pattern.compile("(?<!@)@[^\\s@#<>]+#((?:MC|QQ)[A-Za-z0-9_]*)(?![A-Za-z0-9_])");
    private static final Pattern NATIVE = Pattern.compile("<@!?([A-Za-z0-9_-]{1,256})>|<qqbot-at-user\\s+id=\"([A-Za-z0-9_-]{1,256})\"\\s*/>|<qqbot-at-everyone\\s*/>");
    public record Span(int start, int end, String code) {}
    public record Part(String text, MemberDirectory.Target target, int sourceStart) {
        public Part(String text,MemberDirectory.Target target){this(text,target,-1);}
    }
    public record Rendered(List<Part> parts, Set<UUID> notifications, Set<String> filtered, Map<String,String> associations) {
        public String plain() { StringBuilder b=new StringBuilder(); parts.forEach(p -> b.append(p.text())); return b.toString(); }
        public String legacy() { StringBuilder b=new StringBuilder(); parts.forEach(p -> {
            if(p.target()!=null&&p.target().online()) b.append(dev.penguinsuit.config.RuntimeSettings.colorCode()).append(p.text()).append("§r"); else b.append(p.text());
        }); return b.toString(); }
        public String markdown() {
            StringBuilder b=new StringBuilder();
            for(Part p:parts) {
                var t=p.target();
                if(t!=null&&t.openid()!=null&&dev.penguinsuit.config.RuntimeSettings.nativeQqMentions()) {
                    // Each selected span remains a native QQ mention. Only game sounds deduplicate.
                    b.append("<qqbot-at-user id=\"").append(t.openid()).append("\" />");
                } else b.append(QqMarkdown.literalText(t!=null?"@"+t.qqLabel():p.text()));
            }
            return b.toString();
        }
        /** Filtering must not accidentally turn an audited literal into another mention. */
        public Rendered auditText(String value) {
            if(!plain().equals(value)) throw new MemberDirectory.Invalid("审核修改了提及消息，请修改正文后重发");
            return this;
        }
    }
    private final String text;
    private final List<Span> spans;
    private final MemberDirectory.Scope scope;
    private Map<String,String> frozenAssociations;
    private List<String> frozenCodes;
    public MentionMessage(String text,List<Span> spans,MemberDirectory.Scope scope) {
        if(text.length()>8192||spans.size()>128) throw new MemberDirectory.Invalid("提及消息过长或目标过多");
        this.text=text; this.spans=List.copyOf(spans); this.scope=scope;
    }
    public static boolean hasExplicit(String text) { return EXPLICIT.matcher(text).find(); }
    public static MentionMessage parse(String text,MemberDirectory.Scope scope) {
        List<Span> spans=new ArrayList<>(); Matcher matcher=EXPLICIT.matcher(text);
        while(matcher.find()) spans.add(new Span(matcher.start(),matcher.end(),matcher.group(1)));
        return new MentionMessage(text,spans,scope);
    }
    public static MentionMessage fromQq(String text,MemberDirectory.Scope scope,MemberDirectory directory) {
        List<Span> spans=new ArrayList<>(parse(text,scope).spans); Matcher matcher=NATIVE.matcher(text);
        while(matcher.find()) {
            String id=matcher.group(1)!=null?matcher.group(1):matcher.group(2)!=null?matcher.group(2):"all";
            if(directory.qqExcluded(scope,id)) {
                spans.add(new Span(matcher.start(),matcher.end(),"!"+id));continue;
            }
            String code=directory.qqCode(scope,id);
            if(code==null) throw new MemberDirectory.Invalid("提及的群成员未知或已离群");
            spans.add(new Span(matcher.start(),matcher.end(),code));
        }
        spans.sort(Comparator.comparingInt(Span::start)); return new MentionMessage(text,spans,scope);
    }
    public boolean hasMentions() { return !spans.isEmpty(); }
    public String original() { return text; }
    public MemberDirectory.Scope scope() { return scope; }
    public synchronized Rendered render(MemberDirectory directory) {
        List<MemberDirectory.Target> targets=new ArrayList<>(); Set<String> fake=new LinkedHashSet<>();
        Map<String,String> associations=new LinkedHashMap<>(); int valid=0, selected=0;
        List<String> codes=new ArrayList<>();
        for(int i=0;i<spans.size();i++) {
            Span span=spans.get(i);
            String code=frozenCodes==null?span.code():frozenCodes.get(i);
            if(code.equals("MC")||code.equals("QQ"))code=directory.selectionCode(scope,text.substring(span.start(),span.end()));
            codes.add(code);
            if(code.startsWith("!")) {
                targets.add(null);fake.add("qq-excluded/"+code.substring(1));continue;
            }
            selected++;
            var target=directory.resolve(scope,code); // every error checked, including after a fake
            associations.put(code,target.association()); targets.add(target);
            if(target.fake()) fake.add(target.identity()); else valid++;
        }
        if(frozenAssociations!=null&&!frozenAssociations.equals(associations))
            throw new MemberDirectory.Invalid("绑定关系已变化，请重新选择目标");
        if(selected>0&&valid==0) throw new MemberDirectory.Invalid("提及目标均为假人，未发送");
        if(frozenAssociations==null) { frozenAssociations=Map.copyOf(associations);frozenCodes=List.copyOf(codes); }
        List<Part> parts=new ArrayList<>(); Set<UUID> notify=new LinkedHashSet<>(); int cursor=0;
        for(int i=0;i<spans.size();i++) {
            Span span=spans.get(i); var target=targets.get(i);
            if(span.start()<cursor) throw new MemberDirectory.Invalid("提及片段重叠");
            String before=text.substring(cursor,span.start());
            if(!before.isEmpty()) parts.add(new Part(before,null,cursor));
            boolean removed=target==null||target.fake();
            if(!removed) {
                parts.add(new Part("@"+target.gameLabel(),target));
                if(target.online()&&target.player()!=null) notify.add(target.player());
            }
            cursor=span.end();
            // Trim only one redundant separator adjoining a removed span.
            if(removed&&cursor<text.length()&&text.charAt(cursor)==' '
                    && (parts.isEmpty() || (!parts.getLast().text().isEmpty()&&parts.getLast().text().endsWith(" ")))) cursor++;
        }
        if(cursor<text.length()) parts.add(new Part(text.substring(cursor),null,cursor));
        return new Rendered(List.copyOf(parts),Set.copyOf(notify),Set.copyOf(fake),Map.copyOf(associations));
    }
}
