package com.broadcastmail.worker.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class CampaignStatusNotifier {

    private final JdbcTemplate jdbcTemplate;

    public void notify(UUID campaignId, UUID accountId) {
        String payload = "{\"campaignId\":\"" + campaignId + "\",\"accountId\":\"" + accountId + "\"}";
        jdbcTemplate.queryForObject("SELECT pg_notify('campaign_status', ?)", Void.class, payload);
    }
}
