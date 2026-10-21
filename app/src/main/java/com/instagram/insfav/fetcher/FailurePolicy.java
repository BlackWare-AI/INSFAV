package com.instagram.insfav.fetcher;
/** Profile-local absence and timeouts never become a shared access barrier. */
final class FailurePolicy {
 static boolean unavailable(ProfileProvider.Failure e){return e.code.equals("PROFILE_NOT_FOUND_OR_UNAVAILABLE")||e.code.equals("PROFILE_PAGE_UNAVAILABLE");}
 static boolean noImage(ProfileProvider.Failure e){return e.code.equals("PROFILE_IMAGE_URL_MISSING")||e.code.equals("PROFILE_DEFAULT_AVATAR")||e.code.equals("PROFILE_IDENTITY_UNVERIFIED")||e.code.equals("PROFILE_DATA_AMBIGUOUS");}
 static boolean pause(ProfileProvider.Failure e){
  if(unavailable(e)||noImage(e))return false;
  if(e.code.equals("WEBVIEW_NAVIGATION_BLOCKED")||e.code.equals("WEBVIEW_NETWORK_ERROR")||e.code.equals("WEBVIEW_TIMEOUT")||e.code.equals("PROFILE_OPERATION_TIMEOUT")||e.code.equals("NETWORK_ERROR")||e.code.equals("REQUEST_TIMEOUT")||e.code.equals("WEBVIEW_RENDERER_GONE")||e.code.equals("REQUEST_CANCELLED")||e.code.equals("IMAGE_MEMORY_LIMIT")||e.code.equals("HTTP_ERROR")&&e.http>=500)return false;
  return e.http==429||e.http==401||e.http==403||e.code.startsWith("COOKIE_")||e.code.startsWith("LOGIN_")||e.code.contains("CHALLENGE")||e.code.contains("RESTRICTED")||e.pause;
 }
 private FailurePolicy(){}
}
