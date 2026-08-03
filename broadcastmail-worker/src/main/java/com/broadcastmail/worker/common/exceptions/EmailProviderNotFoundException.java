package com.broadcastmail.worker.common.exceptions;

import java.util.UUID;

public class EmailProviderNotFoundException extends RuntimeException{
    public EmailProviderNotFoundException(UUID accountId) {
        super("Email provider not found for account: " + accountId);
    }
}
