package com.broadcastmail.worker.resolution;

import com.broadcastmail.common.campaign.Campaign;
import com.broadcastmail.common.campaign.CampaignRepository;
import com.broadcastmail.common.campaign.recipient.CampaignRecipientRepository;
import com.broadcastmail.common.connection.Connection;
import com.broadcastmail.common.connection.ConnectionRepository;
import com.broadcastmail.common.filterablecolumn.FilterableColumn;
import com.broadcastmail.common.filterablecolumn.FilterableColumnRepository;
import com.broadcastmail.worker.common.SecurityUtil;
import com.broadcastmail.worker.common.exceptions.CampaignNotFoundException;
import com.broadcastmail.worker.config.EncryptionProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class TemplateAttributeService {

    private static final Pattern TEMPLATE_VAR_PATTERN = Pattern.compile("\\{\\{([a-zA-Z_]\\w*)\\}\\}");

    private final CampaignRepository campaignRepository;
    private final CampaignRecipientRepository campaignRecipientRepository;
    private final ConnectionRepository connectionRepository;
    private final FilterableColumnRepository filterableColumnRepository;
    private final ExternalRecipientQueryService externalRecipientQueryService;
    private final EncryptionProperties encryptionProperties;

    private final Cache<UUID, Map<String, Map<String, Object>>> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(30))
            .build();

    public Map<String, Map<String, Object>> getForCampaign(UUID campaignId) {
        return cache.get(campaignId, this::load);
    }

    public void invalidate(UUID campaignId) {
        cache.invalidate(campaignId);
    }

    private Map<String, Map<String, Object>> load(UUID campaignId) {
        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new CampaignNotFoundException(campaignId));

        Set<String> referenced = extractTemplateVars(campaign.getBodyHtml());
        if (referenced.isEmpty()) return Map.of();

        Connection connection = connectionRepository.findByAccountId(campaign.getAccountId())
                .orElse(null);
        if (connection == null) return Map.of();

        List<FilterableColumn> columns = filterableColumnRepository.findByConnectionId(connection.getId())
                .stream()
                .filter(c -> c.getEnabled() && referenced.contains(c.getColumnName()))
                .toList();
        if (columns.isEmpty()) return Map.of();

        List<String> externalUserIds = campaignRecipientRepository
                .findExternalUserIdsByCampaignId(campaignId);
        if (externalUserIds.isEmpty()) return Map.of();

        String rolePassword = SecurityUtil.decrypt(connection.getEncryptedCreds(), encryptionProperties.key());
        return externalRecipientQueryService.fetchTemplateAttributes(
                connection, rolePassword, externalUserIds, columns);
    }

    private static Set<String> extractTemplateVars(String html) {
        Set<String> vars = new java.util.HashSet<>();
        Matcher m = TEMPLATE_VAR_PATTERN.matcher(html);
        while (m.find()) {
            vars.add(m.group(1));
        }
        return vars;
    }
}
