package com.broadcastmail.worker.resend.dto;

import java.util.List;

public record ResendSendRequest(
        String from,
        List<String> to,
        String subject,
        String html
) {}
