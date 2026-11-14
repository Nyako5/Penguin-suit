package dev.penguinsuit.mention;

import java.math.BigDecimal;
import java.util.*;

/** Only structured User fields are identity evidence; names/content are never compared. */
public final class QqIdentity {
    public static String id(Object raw) {
        String value="";
        if(raw instanceof String s)value=s;
        else if(raw instanceof Number n)try{
            // JSON maps may contain doubles. Never build identity from a rounded large number.
            if((n instanceof Double||n instanceof Float)&&(!Double.isFinite(n.doubleValue())||Math.abs(n.doubleValue())>9007199254740991D))return "";
            var integer=new BigDecimal(n.toString()).toBigIntegerExact();if(integer.signum()>=0)value=integer.toString();
        }catch(RuntimeException ignored){}
        return MemberDirectory.validId(value)?value:"";
    }
    public static String memberId(Map<?,?> user) {
        String id=id(user.get("member_openid"));return id.isEmpty()?id(user.get("id")):id;
    }
    public static boolean bot(Map<?,?> user) {
        Object value=user.get("bot");
        return Boolean.TRUE.equals(value)||value instanceof String s&&s.equalsIgnoreCase("true")
                ||value instanceof Number n&&n.doubleValue()==1;
    }
    public static Set<String> aliases(Map<?,?> user) {
        Set<String> out=new LinkedHashSet<>();
        for(String field:List.of("id","member_openid","user_openid","union_openid")) {
            String id=id(user.get(field));
            if(!id.isEmpty())out.add((field.equals("union_openid")?"union/":"openid/")+id);
        }
        return Set.copyOf(out);
    }
    private QqIdentity() {}
}
