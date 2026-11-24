package com.broadcastmail.worker.common.exceptions;

/** Resend's per-second request limit (429 rate_limit_exceeded); the daily quota is not used up. */
public class ResendThrottledException extends RuntimeException {
    public ResendThrottledException(String s) {
        super(s);
    }
}
