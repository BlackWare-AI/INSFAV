package com.instagram.insfav.fetcher;

interface ProfileProvider extends AutoCloseable {
    default void authenticate() throws Failure { throw new Failure("COOKIE_AUTH_UNVERIFIED", 200, 0, true); }
    @Override default void close() { }
    byte[] fetch(String username) throws Failure;
    void cancel();
    default byte[] fetch(String username,boolean full) throws Failure {return fetch(username);}
    default void cancel(String username) {}
    default void trim(int limit) {}
    default Failure blockingFailure(){return null;}
    final class Failure extends Exception {
        final String code;
        final int http;
        final long retryAt;
        final boolean pause;
        Failure(String code, int http, long retryAt, boolean pause) {
            super(code); this.code = code; this.http = http; this.retryAt = retryAt; this.pause = pause;
        }
        Failure(String code) { this(code, 0, 0, false); }
    }
}
