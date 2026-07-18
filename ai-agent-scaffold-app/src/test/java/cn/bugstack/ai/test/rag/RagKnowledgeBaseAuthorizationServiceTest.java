package cn.bugstack.ai.test.rag;

import cn.bugstack.ai.domain.rag.adapter.repository.IRagRepository;
import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseEntity;
import cn.bugstack.ai.domain.rag.model.valobj.RagKnowledgeBaseStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagVisibility;
import cn.bugstack.ai.domain.rag.service.RagKnowledgeBaseAuthorizationService;
import cn.bugstack.ai.types.exception.AppException;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Method;

/**
 * RAG 管理权限与仓储租户边界测试。
 */
public class RagKnowledgeBaseAuthorizationServiceTest {

    private final RagKnowledgeBaseAuthorizationService service = new RagKnowledgeBaseAuthorizationService();

    @Test
    public void shouldAllowOnlyTrustedTenantAdministrators() {
        service.requireManageable("tenant-a", "owner-1", "owner", knowledgeBase("tenant-a"));
        service.requireManageable("tenant-a", "admin-1", "ADMIN", knowledgeBase("tenant-a"));

        assertAppException("RAG_ADMIN_REQUIRED",
                () -> service.requireManageable("tenant-a", "member-1", "member", knowledgeBase("tenant-a")));
        assertAppException("RAG_AUTH_CONTEXT_MISSING",
                () -> service.requireManageable(null, "admin-1", "admin", knowledgeBase("tenant-a")));
    }

    @Test
    public void shouldHideCrossTenantKnowledgeBase() {
        assertAppException("RAG_KNOWLEDGE_BASE_NOT_FOUND",
                () -> service.requireManageable("tenant-a", "admin-1", "admin", knowledgeBase("tenant-b")));
        assertAppException("RAG_TENANT_SCOPE_MISMATCH",
                () -> service.requireTenantScope("tenant-a", "tenant-b"));
    }

    @Test
    public void shouldKeepTenantIdAsFirstRepositoryArgument() {
        for (Method method : IRagRepository.class.getDeclaredMethods()) {
            Assert.assertTrue("仓储方法必须至少包含 tenantId: " + method.getName(), method.getParameterCount() > 0);
            Assert.assertEquals("仓储方法首参必须是 tenantId 字符串: " + method.getName(),
                    String.class, method.getParameterTypes()[0]);
        }
    }

    private RagKnowledgeBaseEntity knowledgeBase(String tenantId) {
        return new RagKnowledgeBaseEntity(tenantId, "owner-1", "kb-1", "企业知识库", null,
                RagVisibility.TENANT, RagKnowledgeBaseStatus.ACTIVE, "profile-1", 768,
                "rag-tenant-a-kb-1", 1L, 0L);
    }

    private void assertAppException(String code, Runnable action) {
        try {
            action.run();
            Assert.fail("预期抛出领域异常：" + code);
        } catch (AppException e) {
            Assert.assertEquals(code, e.getCode());
        }
    }
}
