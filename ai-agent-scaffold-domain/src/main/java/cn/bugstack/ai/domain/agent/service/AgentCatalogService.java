package cn.bugstack.ai.domain.agent.service;

import cn.bugstack.ai.domain.agent.model.entity.AgentCatalogEntryEntity;
import cn.bugstack.ai.domain.agent.model.entity.AgentConfigStatusEntity;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 从现有静态 Agent 事实源构造受主 Agent 白名单约束的模板目录。 */
@Service
public class AgentCatalogService {
    private final AgentAvailabilityService availabilityService;

    public AgentCatalogService(AgentAvailabilityService availabilityService) {
        this.availabilityService = availabilityService;
    }

    public List<AgentCatalogEntryEntity> search(String tenantId, List<String> allowedAgentIds,
                                                String query, String category) {
        Set<String> allowed = allowedAgentIds == null ? Set.of() : new HashSet<>(allowedAgentIds);
        String normalizedQuery = normalize(query);
        String normalizedCategory = normalize(category);
        return availabilityService.queryConfigs(tenantId, false).stream()
                .filter(agent -> allowed.contains(agent.getAgentId()))
                .filter(agent -> normalizedCategory.isEmpty() || normalizedCategory.equals(normalize(agent.getCategory())))
                .filter(agent -> matches(agent, normalizedQuery))
                .map(this::entry).toList();
    }

    private boolean matches(AgentConfigStatusEntity agent, String query) {
        if (query.isEmpty()) return true;
        return normalize(agent.getAgentName()).contains(query) || normalize(agent.getAgentDesc()).contains(query)
                || values(agent.getBestFor()).stream().map(this::normalize).anyMatch(value -> value.contains(query))
                || values(agent.getCapabilities()).stream().map(this::normalize).anyMatch(value -> value.contains(query));
    }

    private AgentCatalogEntryEntity entry(AgentConfigStatusEntity value) {
        return AgentCatalogEntryEntity.builder().agentId(value.getAgentId()).agentName(value.getAgentName())
                .description(value.getAgentDesc()).category(value.getCategory()).bestFor(values(value.getBestFor()))
                .notFor(values(value.getNotFor())).capabilities(values(value.getCapabilities())).build();
    }

    private List<String> values(List<String> values) { return values == null ? List.of() : List.copyOf(values); }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
