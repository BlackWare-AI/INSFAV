package com.instagram.insfav;
public final class ProfileParserTest {
    static int passed;
    static void check(boolean b,String name){if(!b)throw new AssertionError(name);passed++;System.out.println("PASS "+name);}
    public static void main(String[] args){
        String valid="<meta property='og:title' content='Example (@example) • Instagram'><meta content='https://www.instagram.com/example/' property='og:url'><meta property='og:image' content='https://scontent.cdninstagram.com/photo.jpg?a=1&amp;b=2'>";
        check(ProfileParser.image(valid,"example").equals("https://scontent.cdninstagram.com/photo.jpg?a=1&b=2"),"valid identity and HTML entities");
        check(ProfileParser.image(valid,"other")==null,"reject different account identity");
        check(ProfileParser.image(valid.replace("scontent.cdninstagram.com/photo.jpg","static.cdninstagram.com/logo.png"),"example")==null,"reject generic Instagram logo");
        check(ProfileParser.image("<html><title>Instagram</title></html>","example")==null,"metadata-free shell remains missing data");
        check(ProfileParser.image(valid.replace("https://www.instagram.com/example/","https://www.instagram.com/accounts/login/"),"example")==null,"reject login identity");
        check(!ProfileParser.imageHost("https://cdninstagram.com.evil.example/image.jpg"),"reject lookalike CDN suffix");
        check(!ProfileParser.imageHost("http://scontent.cdninstagram.com/image.jpg"),"require HTTPS");
        check(!ProfileParser.imageHost("https://127.0.0.1/image.jpg"),"reject internal addresses");
        check(!ProfileParser.imageHost("https://scontent.cdninstagram.com:8080/image.jpg"),"reject nonstandard port");
        check(ProfileParser.profilePath("https://www.instagram.com/EXAMPLE/","example"),"profile identity is case insensitive");
        check(!ProfileParser.profilePath("https://instagram.com.evil.example/example/","example"),"reject profile host spoofing");
        check(ProfileParser.metadata("<META CONTENT=\"safe\" PROPERTY=\"og:title\">").get("og:title").equals("safe"),"case-insensitive metadata and reversed attributes");
        System.out.println(passed+" parser checks passed");
    }
}
