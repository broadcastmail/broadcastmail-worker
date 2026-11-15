package com.broadcastmail.worker.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class CampaignStatusNotifier {

    private final JdbcTemplate jdbcTemplate;

    public void notify(UUID campaignId, UUID accountId) {
        String payload = "{\"campaignId\":\"" + campaignId + "\",\"accountId\":\"" + accountId + "\"}";
        jdbcTemplate.execute((Connection con) -> {
            try (var ps = con.prepareStatement("SELECT pg_notify('campaign_status', ?)")) {
                ps.setString(1, payload);
                ps.execute();
                return null;
            }
        });
    }
}
