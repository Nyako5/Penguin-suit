package dev.penguinsuit.mention;

import java.util.*;

/** Vanilla greedy-string argument with server-side quoting; no client argument registration. */
public final class AtSyntax {
    /** CR uploads attached-image input as a caption rather than executing a vanilla command. */
    public static boolean isImageCommand(String caption) {
        String input=caption.stripLeading();
        return input.startsWith("/at")&&(input.length()==3||Character.isWhitespace(input.charAt(3)));
    }
    public static String imageCaption(String caption,MemberDirectory directory,MemberDirectory.Scope scope) {
        if(!isImageCommand(caption))return caption;
        return expand(caption.stripLeading().substring(3).stripLeading(),directory,scope);
    }
    public static String expand(String input,MemberDirectory directory,MemberDirectory.Scope scope) {
        List<String> targets=new ArrayList<>(); int i=0; String body="";
        // Keep the old named-target list before --. Tab-selected candidates need no separator.
        boolean namedList=hasSeparator(input)&&!explicit(input.stripLeading().split("\\s+",2)[0]);
        while(i<input.length()) {
            while(i<input.length()&&Character.isWhitespace(input.charAt(i)))i++;
            if(i==input.length())break;
            int start=i, end=i;
            while(end<input.length()&&!Character.isWhitespace(input.charAt(end)))end++;
            String next=input.substring(start,end);
            if(!targets.isEmpty()&&!namedList&&!next.equals("--")&&!explicit(next)) {
                body=input.substring(start); break;
            }
            boolean quoted=input.charAt(i)=='\"'; StringBuilder word=new StringBuilder();
            if(quoted) {
                i++; boolean closed=false;
                while(i<input.length()) {
                    char c=input.charAt(i++); if(c=='\"'){closed=true;break;}
                    if(c=='\\') {
                        if(i==input.length())throw new MemberDirectory.Invalid("不完整的引号转义");
                        c=input.charAt(i++); if(c!='\\'&&c!='\"')throw new MemberDirectory.Invalid("只支持双引号和反斜杠转义");
                    }
                    word.append(c);
                }
                if(!closed)throw new MemberDirectory.Invalid("目标名称引号未闭合");
                if(i<input.length()&&!Character.isWhitespace(input.charAt(i)))throw new MemberDirectory.Invalid("目标之间需要空格");
            } else while(i<input.length()&&!Character.isWhitespace(input.charAt(i)))word.append(input.charAt(i++));
            if(!quoted&&word.toString().equals("--")) { body=input.substring(i).stripLeading(); break; }
            if(word.isEmpty())throw new MemberDirectory.Invalid("提及目标为空");
            targets.add(directory.select(scope,word.toString(),quoted));
            if(targets.size()>128)throw new MemberDirectory.Invalid("提及目标过多");
        }
        if(targets.isEmpty())throw new MemberDirectory.Invalid("用法：/at <目标...> [正文]；使用 Tab 选择 #MC 或 #QQ 目标，重名时带编号");
        return String.join(" ",targets)+(body.isEmpty()?"":" "+body);
    }
    private static boolean explicit(String word) {
        return word.startsWith("mc:")||word.startsWith("qq:")
                ||word.startsWith("@")&&(word.contains("#MC")||word.contains("#QQ"));
    }
    private static boolean hasSeparator(String input) {
        boolean quoted=false, escaped=false;
        for(int i=0;i<input.length();i++) {
            char c=input.charAt(i);
            if(escaped){escaped=false;continue;}
            if(quoted&&c=='\\'){escaped=true;continue;}
            if(c=='"'){quoted=!quoted;continue;}
            if(!quoted&&c=='-'&&i+1<input.length()&&input.charAt(i+1)=='-'
                    &&(i==0||Character.isWhitespace(input.charAt(i-1)))
                    &&(i+2==input.length()||Character.isWhitespace(input.charAt(i+2))))return true;
        }
        return false;
    }
    private AtSyntax() {}
}
