package com.broadcastmail.worker.common.exceptions;

import java.util.UUID;

public class RecipientNotFoundException extends RuntimeException{
    public RecipientNotFoundException(UUID recipientId) {
        super("Recipient not found: " + recipientId);
    }
}
