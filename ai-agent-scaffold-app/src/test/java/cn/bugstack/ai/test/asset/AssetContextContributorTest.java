package cn.bugstack.ai.test.asset;

import cn.bugstack.ai.domain.asset.adapter.IAssetRepository;
import cn.bugstack.ai.domain.asset.model.AssetEntity;
import cn.bugstack.ai.domain.asset.service.AssetContextContributor;
import cn.bugstack.ai.domain.context.model.ContextAssembleRequest;
import cn.bugstack.ai.domain.context.model.ContextContribution;
import cn.bugstack.ai.domain.context.model.ContextFragmentType;
import cn.bugstack.ai.domain.context.model.ContextPolicyProperties;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.List;

/**
 * 附件上下文贡献器测试。
 */
public class AssetContextContributorTest {

    @Test
    public void shouldQueryVisibleActiveAssetsWithinBudget() {
        IAssetRepository repository = Mockito.mock(IAssetRepository.class);
        Mockito.when(repository.queryContextAssets("tenant_1", "user_1", "session_1", 12))
                .thenReturn(List.of(
                        AssetEntity.builder().assetId("asset_1").fileName("需求.md").extractedText("必须支持取消").build(),
                        AssetEntity.builder().assetId("asset_2").fileName("photo.png").extractedText(null).build()));
        ContextPolicyProperties properties = new ContextPolicyProperties();
        properties.setAttachmentTokens(1024);

        List<ContextContribution> result = new AssetContextContributor(repository).contribute(
                ContextAssembleRequest.builder().tenantId("tenant_1").userId("user_1")
                        .sessionId("session_1").visibleThroughSequence(12).build(), properties);

        Assert.assertEquals(1, result.size());
        Assert.assertEquals(ContextFragmentType.ATTACHMENT, result.get(0).getType());
        Assert.assertTrue(result.get(0).getContent().contains("必须支持取消"));
        Assert.assertFalse(result.get(0).getContent().contains("photo.png"));
        Mockito.verify(repository).queryContextAssets("tenant_1", "user_1", "session_1", 12);
    }
}
