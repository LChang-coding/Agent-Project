package cn.bugstack.ai.test.context;

import cn.bugstack.ai.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import cn.bugstack.ai.domain.asset.adapter.IAssetRepository;
import cn.bugstack.ai.domain.context.adapter.repository.IContextCompactionTaskRepository;
import cn.bugstack.ai.domain.context.model.ContextAssemblyResult;
import cn.bugstack.ai.domain.context.model.ContextCompactionTaskEntity;
import cn.bugstack.ai.domain.context.model.ContextCompactionTaskStatus;
import cn.bugstack.ai.domain.context.model.ContextInsightEntity;
import cn.bugstack.ai.domain.context.model.ContextPolicyProperties;
import cn.bugstack.ai.domain.context.service.ContextInsightService;
import cn.bugstack.ai.domain.context.service.ConversationMemoryService;
import cn.bugstack.ai.domain.session.model.entity.ChatMessageEntity;
import cn.bugstack.ai.domain.session.model.entity.ChatSessionEntity;
import cn.bugstack.ai.domain.session.service.SessionDomain;
import cn.bugstack.ai.domain.tool.adapter.repository.IToolRepository;
import cn.bugstack.ai.domain.tool.model.entity.ToolCallLogEntity;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

/**
 * 上下文洞察服务测试。
 */
public class ContextInsightServiceTest {

    /**
     * 校验洞察使用可信会话范围和真实组装结果；无参数；验证组件、工具及压缩状态统计。
     */
    @Test
    public void shouldBuildInsightFromTrustedSessionScope() {
        SessionDomain sessionDomain = Mockito.mock(SessionDomain.class);
        ConversationMemoryService memoryService = Mockito.mock(ConversationMemoryService.class);
        IContextCompactionTaskRepository taskRepository = Mockito.mock(IContextCompactionTaskRepository.class);
        IToolRepository toolRepository = Mockito.mock(IToolRepository.class);
        IAssetRepository assetRepository = Mockito.mock(IAssetRepository.class);
        ContextPolicyProperties properties = new ContextPolicyProperties();
        properties.setModelWindowTokens(1000);
        AiAgentAutoConfigProperties agentProperties = new AiAgentAutoConfigProperties();
        agentProperties.setTables(Map.of());
        ChatSessionEntity session = ChatSessionEntity.builder().tenantId("tenant_1").userId("user_1")
                .sessionId("session_1").contextRevision(3L).build();
        Mockito.when(sessionDomain.assertSessionAccess("tenant_1", "user_1", "session_1", null)).thenReturn(session);
        Mockito.when(sessionDomain.queryValidMessages("tenant_1", "user_1", "session_1")).thenReturn(List.of(
                ChatMessageEntity.builder().sequenceNo(2).build(), ChatMessageEntity.builder().sequenceNo(5).build()));
        Mockito.when(memoryService.preview(Mockito.any())).thenReturn(ContextAssemblyResult.builder()
                .estimatedTokenCount(300).summaryTokens(100).historyTokens(160).ragTokens(20).upstreamTokens(20)
                .effectiveFromSequence(2).effectiveToSequence(5).memoryVersion(2).build());
        Mockito.when(taskRepository.queryLatest("tenant_1", "user_1", "session_1"))
                .thenReturn(ContextCompactionTaskEntity.builder().status(ContextCompactionTaskStatus.PROCESSING).build());
        Mockito.when(toolRepository.queryToolCallLogs("tenant_1", "user_1", "session_1")).thenReturn(List.of(
                ToolCallLogEntity.builder().toolId("tool_a").build(),
                ToolCallLogEntity.builder().toolId("tool_a").build(),
                ToolCallLogEntity.builder().toolId("tool_b").build()));

        ContextInsightEntity result = new ContextInsightService(sessionDomain, memoryService, taskRepository,
                toolRepository, properties, agentProperties, assetRepository).query("tenant_1", "user_1", "session_1");

        Assert.assertEquals(Integer.valueOf(300), result.getEffectiveTokens());
        Assert.assertEquals(Integer.valueOf(100), result.getSummaryTokens());
        Assert.assertEquals(Integer.valueOf(2), result.getToolCount());
        Assert.assertEquals(Integer.valueOf(3), result.getCallCount());
        Assert.assertEquals("processing", result.getCompactionStatus());
        ArgumentCaptor<cn.bugstack.ai.domain.context.model.ContextAssembleRequest> request =
                ArgumentCaptor.forClass(cn.bugstack.ai.domain.context.model.ContextAssembleRequest.class);
        Mockito.verify(memoryService).preview(request.capture());
        Assert.assertEquals(Integer.valueOf(5), request.getValue().getVisibleThroughSequence());
    }
}
