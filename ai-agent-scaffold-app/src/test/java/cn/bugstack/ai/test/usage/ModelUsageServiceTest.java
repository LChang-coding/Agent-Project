package cn.bugstack.ai.test.usage;

import cn.bugstack.ai.domain.usage.adapter.IModelUsageRepository;
import cn.bugstack.ai.domain.usage.model.ModelUsageEntity;
import cn.bugstack.ai.domain.usage.model.ModelUsageSummaryEntity;
import cn.bugstack.ai.domain.usage.service.ModelUsageService;
import cn.bugstack.ai.types.exception.AppException;
import org.junit.Assert;
import org.junit.Test;

/**
 * 模型用量领域服务测试。
 */
public class ModelUsageServiceTest {

    /**
     * 校验供应商未返回总量时补全总 Token；无参数；验证落库实体保持调用身份。
     */
    @Test
    public void shouldCalculateMissingTotalTokensBeforeUpsert() {
        FakeRepository repository = new FakeRepository();
        ModelUsageService service = new ModelUsageService(repository);
        ModelUsageEntity usage = ModelUsageEntity.builder().userId("user_1").sessionId("session_1")
                .callId("call_1").invocationId("invocation_1").callStatus("success")
                .promptTokens(12).candidateTokens(8).build();

        int affected = service.record(usage);

        Assert.assertEquals(1, affected);
        Assert.assertSame(usage, repository.saved);
        Assert.assertEquals(Integer.valueOf(20), repository.saved.getTotalTokens());
    }

    /**
     * 校验空聚合结果被归零；无参数；验证前端不会收到空统计字段。
     */
    @Test
    public void shouldReturnZeroSummaryWhenRepositoryHasNoUsage() {
        ModelUsageSummaryEntity summary = new ModelUsageService(new FakeRepository())
                .summarizeSession("tenant_1", "user_1", "session_1", null);

        Assert.assertEquals(Long.valueOf(0), summary.getCallCount());
        Assert.assertEquals(Long.valueOf(0), summary.getFailedCount());
        Assert.assertEquals(Long.valueOf(0), summary.getTotalTokens());
    }

    /**
     * 校验数据库返回部分空聚合时逐字段归零；无参数；验证空成功数不会泄漏到接口。
     */
    @Test
    public void shouldNormalizePartiallyNullSummary() {
        FakeRepository repository = new FakeRepository();
        repository.summary = ModelUsageSummaryEntity.builder().callCount(2L).totalTokens(40L).build();

        ModelUsageSummaryEntity result = new ModelUsageService(repository)
                .summarizeSession("tenant_1", "user_1", "session_1", null);

        Assert.assertEquals(Long.valueOf(2), result.getCallCount());
        Assert.assertEquals(Long.valueOf(0), result.getSuccessCount());
        Assert.assertEquals(Long.valueOf(40), result.getTotalTokens());
    }

    /**
     * 校验近期统计范围；无参数；验证非法天数在访问仓储前被拒绝。
     */
    @Test
    public void shouldRejectInvalidRecentRange() {
        try {
            new ModelUsageService(new FakeRepository()).summarizeRecent("tenant_1", "user_1", 0);
            Assert.fail("非法统计范围必须被拒绝");
        } catch (AppException exception) {
            Assert.assertEquals("MODEL_USAGE_RANGE_INVALID", exception.getCode());
        }
    }

    private static class FakeRepository implements IModelUsageRepository {
        private ModelUsageEntity saved;
        private ModelUsageSummaryEntity summary;

        @Override
        public int upsert(ModelUsageEntity usage) {
            saved = usage;
            return 1;
        }

        @Override
        public ModelUsageEntity queryLatest(String tenantId, String userId, String sessionId) {
            return saved;
        }

        @Override
        public ModelUsageSummaryEntity summarizeSession(String tenantId, String userId, String sessionId,
                                                         String runId) {
            return summary;
        }

        @Override
        public ModelUsageSummaryEntity summarizeRecent(String tenantId, String userId, int days) {
            return summary;
        }

        @Override
        public int cancelRunning(String tenantId, String userId, String sessionId, String runId, String reason) {
            return 1;
        }
    }
}
