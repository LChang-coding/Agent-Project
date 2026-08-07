package cn.bugstack.ai.test.rag;

import cn.bugstack.ai.domain.rag.adapter.repository.IRagRepository;
import cn.bugstack.ai.domain.rag.model.entity.RagAgentBindingEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseEntity;
import cn.bugstack.ai.domain.rag.model.valobj.RagBindingTargetType;
import cn.bugstack.ai.domain.rag.model.valobj.RagKnowledgeBaseStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagVisibility;
import cn.bugstack.ai.domain.rag.service.RagToolCapabilityService;
import org.junit.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class RagToolCapabilityServiceTest {

    @Test
    public void describesFrozenKnowledgeBasesForAgentToolMode() {
        IRagRepository repository = mock(IRagRepository.class);
        RagAgentBindingEntity binding = new RagAgentBindingEntity("tenant", "binding-1",
                RagBindingTargetType.WORKFLOW, "workflow-1", "kb-1", "profile-1", true, 1200, 0, 1);
        RagKnowledgeBaseEntity knowledgeBase = new RagKnowledgeBaseEntity("tenant", "owner", "kb-1",
                "员工手册", "desc", RagVisibility.TENANT, RagKnowledgeBaseStatus.ACTIVE,
                "profile-1", 768, "alias", 1, 1);
        when(repository.findBinding(eq("tenant"), eq("binding-1"))).thenReturn(Optional.of(binding));
        when(repository.findKnowledgeBase(eq("tenant"), eq("kb-1"))).thenReturn(Optional.of(knowledgeBase));

        String guidance = new RagToolCapabilityService(repository)
                .guidance("tenant", "AGENT_TOOL", List.of("binding-1"), true);

        assertTrue(guidance.contains("rag_retrieve"));
        assertTrue(guidance.contains("员工手册"));
        assertTrue(guidance.contains("binding-1"));
        assertTrue(guidance.contains("不要把外部网页搜索当作本地知识库检索"));
    }

    @Test
    public void doesNotDescribeRagForAutomaticContextMode() {
        IRagRepository repository = mock(IRagRepository.class);
        String guidance = new RagToolCapabilityService(repository)
                .guidance("tenant", "AUTO_CONTEXT", List.of("binding-1"), true);
        assertTrue(guidance.isEmpty());
    }

    @Test
    public void doesNotAdvertiseToolWhenPlatformRagIsDisabled() {
        IRagRepository repository = mock(IRagRepository.class);
        String guidance = new RagToolCapabilityService(repository, false)
                .guidance("tenant", "AGENT_TOOL", List.of("binding-1"), true);
        assertTrue(guidance.isEmpty());
    }

    @Test
    public void explicitlyReportsMissingFrozenBindings() {
        IRagRepository repository = mock(IRagRepository.class);
        String guidance = new RagToolCapabilityService(repository)
                .guidance("tenant", "AGENT_TOOL", List.of(), true);
        assertTrue(guidance.contains("没有冻结的知识库绑定"));
        assertFalse(guidance.contains("员工手册"));
    }
}
