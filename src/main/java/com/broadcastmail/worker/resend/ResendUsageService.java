package com.broadcastmail.worker.resend;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.resend.Resend;
import com.resend.services.usage.model.UsageEmailsPeriod;
import com.resend.services.usage.model.UsageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.OptionalInt;

/**
 * Checks remaining Resend daily email quota per API key.
 * Returns empty (unlimited) for paid plans; a value for free (100/day) and
 * other plans that expose a daily cap.  Cached for 1 minute to avoid
 * hammering the usage endpoint on every poll cycle.
 */
@Service
public class ResendUsageService {

    private static final Logger log = LoggerFactory.getLogger(ResendUsageService.class);

    private final Cache<String, OptionalInt> quotaCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(1))
            .build();

    private final Cache<String, Resend> clientsByApiKey = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofHours(1))
            .build();

    /**
     * Returns the number of emails still sendable today for this API key.
     * Empty means no daily cap (unlimited or unknown — caller should proceed).
     */
    public OptionalInt getRemainingDailyQuota(String apiKey) {
        return quotaCache.get(apiKey, this::fetch);
    }

    /** Evict a key so the next call re-fetches fresh quota. */
    public void evict(String apiKey) {
        quotaCache.invalidate(apiKey);
    }

    private OptionalInt fetch(String apiKey) {
        try {
            Resend resend = clientsByApiKey.get(apiKey, Resend::new);
            assert resend != null;
            UsageResponse usage = resend.usage().get();
            if (usage.getEmails() == null) return OptionalInt.empty();
            UsageEmailsPeriod daily = usage.getEmails().getDaily();
            if (daily == null || daily.getLimit() == null || daily.getLimit() == 0) {
                return OptionalInt.empty();
            }
            int used = daily.getUsed() != null ? daily.getUsed() : 0;
            return OptionalInt.of(Math.max(0, daily.getLimit() - used));
        } catch (Exception e) {
            log.warn("Could not fetch Resend usage quota — proceeding without cap: {}", e.getMessage());
            return OptionalInt.empty();
        }
    }
}
