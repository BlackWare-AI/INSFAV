package com.instagram.insfav.fetcher;
final class FailurePolicy {
 static boolean unavailable(ProfileProvider.Failure e){return e.http==404&&e.code.equals("PROFILE_NOT_FOUND_OR_UNAVAILABLE")&&!e.pause;}
 static boolean pause(ProfileProvider.Failure e){
  if(unavailable(e))return false;
  if(e.code.equals("PROFILE_IMAGE_URL_MISSING")||e.code.equals("PROFILE_PAGE_UNAVAILABLE")||e.code.equals("WEBVIEW_NETWORK_ERROR")||e.code.equals("WEBVIEW_TIMEOUT")||e.code.equals("NETWORK_ERROR")||e.code.equals("REQUEST_TIMEOUT")||e.code.equals("HTTP_ERROR")&&e.http>=500)return false;
  return e.http==429||e.http==401||e.http==403||e.code.startsWith("COOKIE_")||e.code.contains("CHALLENGE")||e.code.contains("RESTRICTED")||e.pause;
 }
 private FailurePolicy(){}
}
