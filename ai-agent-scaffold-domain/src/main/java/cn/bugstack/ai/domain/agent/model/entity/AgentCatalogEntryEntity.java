package cn.bugstack.ai.domain.agent.model.entity;

import lombok.Builder;

import java.util.List;

/** 可安全暴露给主 Agent 的子 Agent 模板目录项。 */
@Builder
public record AgentCatalogEntryEntity(String agentId, String agentName, String description, String category,
                                      List<String> bestFor, List<String> notFor, List<String> capabilities) {
}
