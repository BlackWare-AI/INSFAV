package com.instagram.insfav;

import java.net.URI;
import java.util.*;
import java.util.regex.*;

/** Dependency-free parser for public-page metadata; deliberately rejects ambiguous identity. */
final class ProfileParser {
    static final Pattern META=Pattern.compile("<meta\\b[^>]*>",Pattern.CASE_INSENSITIVE);
    static final Pattern ATTR=Pattern.compile("([\\w:-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')",Pattern.CASE_INSENSITIVE);
    static Map<String,String> metadata(String html){Map<String,String> out=new HashMap<>();Matcher m=META.matcher(html);while(m.find()){Map<String,String> a=new HashMap<>();Matcher p=ATTR.matcher(m.group());while(p.find())a.put(p.group(1).toLowerCase(Locale.ROOT),unescape(p.group(2)!=null?p.group(2):p.group(3)));String k=a.containsKey("property")?a.get("property"):a.get("name");if(k!=null&&a.containsKey("content"))out.put(k.toLowerCase(Locale.ROOT),a.get("content"));}return out;}
    static String unescape(String s){return s.replace("&amp;","&").replace("&quot;","\"").replace("&#39;","'").replace("&lt;","<").replace("&gt;",">");}
    static boolean profilePath(String url,String user){try{URI u=URI.create(url);String host=u.getHost();return "https".equals(u.getScheme())&&("www.instagram.com".equals(host)||"instagram.com".equals(host))&&u.getPath().replaceAll("^/|/$","").equalsIgnoreCase(user);}catch(Exception e){return false;}}
    static boolean imageHost(String url){try{URI u=URI.create(url);String h=u.getHost();if(!"https".equals(u.getScheme())||h==null||u.getPort()!=-1&&u.getPort()!=443)return false;boolean host=h.endsWith(".cdninstagram.com")||h.endsWith(".fbcdn.net")||h.endsWith(".instagram.com");String p=u.getPath().toLowerCase(Locale.ROOT);return host&&!h.startsWith("static.")&&!p.contains("logo")&&!p.contains("rsrc.php");}catch(Exception e){return false;}}
    static String image(String html,String username){Map<String,String> m=metadata(html);String title=m.getOrDefault("og:title","").toLowerCase(Locale.ROOT),image=m.get("og:image");if(!title.contains("@"+username.toLowerCase(Locale.ROOT))||!profilePath(m.getOrDefault("og:url",""),username)||image==null||!imageHost(image))return null;return image;}
}
