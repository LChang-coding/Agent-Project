package cn.bugstack.ai.domain.context.service;

import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.bugstack.ai.domain.asset.adapter.IAssetRepository;
import cn.bugstack.ai.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import cn.bugstack.ai.domain.context.adapter.repository.IContextCompactionTaskRepository;
import cn.bugstack.ai.domain.context.model.ContextAssembleRequest;
import cn.bugstack.ai.domain.context.model.ContextAssemblyResult;
import cn.bugstack.ai.domain.context.model.ContextCompactionTaskEntity;
import cn.bugstack.ai.domain.context.model.ContextInsightEntity;
import cn.bugstack.ai.domain.context.model.ContextPolicyProperties;
import cn.bugstack.ai.domain.session.model.entity.ChatSessionEntity;
import cn.bugstack.ai.domain.session.service.SessionDomain;
import cn.bugstack.ai.domain.tool.adapter.repository.IToolRepository;
import cn.bugstack.ai.domain.tool.model.entity.ToolCallStatisticsEntity;
import org.springframework.stereotype.Service;

/**
 * 上下文洞察服务。
 * <p>使用真实组装策略只读计算当前会话的模型上下文构成。</p>
 */
@Service
public class ContextInsightService {

    private final SessionDomain sessionDomain;
    private final ConversationMemoryService memoryService;
    private final IContextCompactionTaskRepository taskRepository;
    private final IToolRepository toolRepository;
    private final ContextPolicyProperties properties;
    private final AiAgentAutoConfigProperties agentProperties;
    private final IAssetRepository assetRepository;
    private final CharacterTokenCounter tokenCounter = new CharacterTokenCounter();

    /**
     * 创建上下文洞察服务；参数是会话、上下文、工具与配置依赖；返回服务实例。
     */
    public ContextInsightService(SessionDomain sessionDomain, ConversationMemoryService memoryService,
                                 IContextCompactionTaskRepository taskRepository, IToolRepository toolRepository,
                                 ContextPolicyProperties properties, AiAgentAutoConfigProperties agentProperties,
                                 IAssetRepository assetRepository) {
        this.sessionDomain = sessionDomain;
        this.memoryService = memoryService;
        this.taskRepository = taskRepository;
        this.toolRepository = toolRepository;
        this.properties = properties;
        this.agentProperties = agentProperties;
        this.assetRepository = assetRepository;
    }

    /**
     * 查询会话上下文洞察；参数是可信身份和会话；返回只读统计。
     */
    public ContextInsightEntity query(String tenantId, String userId, String sessionId) {
        ChatSessionEntity session = sessionDomain.assertSessionAccess(tenantId, userId, sessionId, null);
        int visibleThrough = safe(sessionDomain.queryMaxValidSequenceNo(session.getTenantId(), session.getUserId(),
                session.getSessionId()));
        ContextAssemblyResult assembly = memoryService.preview(ContextAssembleRequest.builder()
                .tenantId(session.getTenantId()).userId(session.getUserId()).sessionId(session.getSessionId())
                .visibleThroughSequence(visibleThrough).build());
        int systemTokens = systemTokens(session);
        int effectiveTokens = systemTokens + safe(assembly.getEstimatedTokenCount());
        int window = properties.getModelWindowTokens();
        ToolCallStatisticsEntity toolStatistics = toolRepository.summarizeToolCalls(session.getTenantId(),
                session.getUserId(), session.getSessionId());
        ContextCompactionTaskEntity latestTask = taskRepository.queryLatest(session.getTenantId(),
                session.getUserId(), session.getSessionId());
        int attachmentCount = assetRepository.countContextAssets(session.getTenantId(), session.getUserId(),
                session.getSessionId(), assembly.getCoveredToSequence(), visibleThrough);
        return ContextInsightEntity.builder().sessionId(sessionId).contextRevision(session.getContextRevision())
                .modelWindowTokens(window).effectiveTokens(effectiveTokens)
                .utilization(window <= 0 ? 0D : Math.min(1D, (double) effectiveTokens / window))
                .systemTokens(systemTokens).historyTokens(safe(assembly.getHistoryTokens()))
                .summaryTokens(safe(assembly.getSummaryTokens())).toolResultTokens(0)
                .attachmentTokens(safe(assembly.getAttachmentTokens()))
                .ragTokens(safe(assembly.getRagTokens())).upstreamTokens(safe(assembly.getUpstreamTokens()))
                .effectiveFromSequence(assembly.getEffectiveFromSequence())
                .effectiveToSequence(assembly.getEffectiveToSequence()).memoryVersion(assembly.getMemoryVersion())
                .compactionStatus(latestTask == null ? "idle" : latestTask.getStatus().name().toLowerCase())
                .toolCount(safeCount(toolStatistics == null ? null : toolStatistics.getToolCount()))
                .callCount(safeCount(toolStatistics == null ? null : toolStatistics.getCallCount()))
                .attachmentCount(attachmentCount)
                .trimReason(assembly.getTrimReason()).build();
    }

    private int systemTokens(ChatSessionEntity session) {
        if (agentProperties.getTables() == null) {
            return 0;
        }
        return agentProperties.getTables().values().stream()
                .filter(table -> session.getAppName() != null && session.getAppName().equals(table.getAppName()))
                .map(AiAgentConfigTableVO::getModule).filter(module -> module != null && module.getAgents() != null)
                .flatMap(module -> module.getAgents().stream()).map(AiAgentConfigTableVO.Module.Agent::getInstruction)
                .filter(value -> value != null && !value.isBlank()).mapToInt(tokenCounter::estimate).sum();
    }

    private int safe(Integer value) {
        return value == null ? 0 : value;
    }

    private int safeCount(Long value) {
        if (value == null || value <= 0) {
            return 0;
        }
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : value.intValue();
    }
}
