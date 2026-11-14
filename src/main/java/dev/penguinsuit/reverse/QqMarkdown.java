package dev.penguinsuit.reverse;

/** QQ's Markdown subset does not consistently consume CommonMark backslash escapes. */
public final class QqMarkdown {
    public static String imageMessage(String text,String reference,int width,int height) {
        if(width<1||height<1||width>8192||height>8192) throw new IllegalArgumentException("invalid_image_dimensions");
        double scale=Math.min(1.0,640.0/Math.max(width,height));
        int displayWidth=Math.max(1,(int)Math.round(width*scale));
        int displayHeight=Math.max(1,(int)Math.round(height*scale));
        return literalText(text)+"\n\n![图片 #"+displayWidth+"px #"+displayHeight+"px]("+reference+")";
    }
    /** Preserve ordinary tags and names exactly; neutralize actual Markdown syntax visibly. */
    public static String literalText(String text) {
        String input=text.replace("\r\n","\n").replace('\r','\n');
        StringBuilder out=new StringBuilder();
        boolean lineStart=true;
        for(int i=0;i<input.length();i++) {
            char c=input.charAt(i);
            char before=i>0?input.charAt(i-1):0, after=i+1<input.length()?input.charAt(i+1):0;
            if(c=='\n') {out.append(c);lineStart=true;continue;}
            if(Character.isISOControl(c)&&c!='\t') continue;
            // No backslashes are inserted. Bare [server] and Player_ID are ordinary text.
            char safe=switch(c) {
                case '\\' -> '＼';
                case '`' -> '｀';
                case '*' -> '＊';
                case '<' -> '＜';
                case '>' -> '＞';
                case '|' -> '｜';
                case '_' -> Character.isLetterOrDigit(before)&&Character.isLetterOrDigit(after)?c:'＿';
                case '~' -> before=='~'||after=='~'?'～':c;
                case '!' -> after=='['?'！':c;
                case ']' -> after=='('||after=='['||after==':'?'］':c;
                case '#' -> lineStart?'＃':c;
                case '-' -> lineStart?'－':c;
                case '+' -> lineStart?'＋':c;
                case '.' -> Character.isDigit(before)&&Character.isWhitespace(after)?'．':c;
                default -> c;
            };
            out.append(safe);
            if(!Character.isWhitespace(c))lineStart=false;
        }
        return out.toString();
    }
    private QqMarkdown() {}
}
