package com.broadcastmail.worker.common.exceptions;

public class PlanLimitExceededException extends RuntimeException {
    public PlanLimitExceededException() {
        super("Recipient limit exceeded for your plan");
    }
}
