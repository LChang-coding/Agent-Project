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
        Mockito.when(repository.queryContextAssets("tenant_1", "user_1", "session_1", 8, 13, 32, 131072))
                .thenReturn(List.of(
                        AssetEntity.builder().assetId("asset_1").fileName("需求.md").extractedText("必须支持取消").build(),
                        AssetEntity.builder().assetId("asset_2").fileName("photo.png").extractedText(null).build()));
        ContextPolicyProperties properties = new ContextPolicyProperties();
        properties.setAttachmentTokens(1024);

        List<ContextContribution> result = new AssetContextContributor(repository).contribute(
                ContextAssembleRequest.builder().tenantId("tenant_1").userId("user_1")
                        .sessionId("session_1").visibleThroughSequence(12)
                        .attachmentVisibleThroughSequence(13).coveredToSequence(8).build(), properties);

        Assert.assertEquals(1, result.size());
        Assert.assertEquals(ContextFragmentType.ATTACHMENT, result.get(0).getType());
        Assert.assertTrue(result.get(0).getContent().contains("必须支持取消"));
        Assert.assertFalse(result.get(0).getContent().contains("photo.png"));
        Mockito.verify(repository).queryContextAssets("tenant_1", "user_1", "session_1", 8, 13, 32, 131072);
    }

    @Test
    public void shouldDeduplicateSameContentHashWithinOneAssembly() {
        IAssetRepository repository = Mockito.mock(IAssetRepository.class);
        Mockito.when(repository.queryContextAssets("tenant_1", "user_1", "session_1", 4, 9, 32, 131072))
                .thenReturn(List.of(
                        AssetEntity.builder().assetId("asset_recent").sha256("same_hash")
                                .fileName("需求-新.md").extractedText("同一份需求").build(),
                        AssetEntity.builder().assetId("asset_old").sha256("same_hash")
                                .fileName("需求-旧.md").extractedText("同一份需求").build()));
        ContextPolicyProperties properties = new ContextPolicyProperties();
        properties.setAttachmentTokens(1024);

        String content = new AssetContextContributor(repository).contribute(ContextAssembleRequest.builder()
                .tenantId("tenant_1").userId("user_1").sessionId("session_1")
                .coveredToSequence(4).attachmentVisibleThroughSequence(9).build(), properties).get(0).getContent();

        Assert.assertTrue(content.contains("asset_recent"));
        Assert.assertFalse(content.contains("asset_old"));
        Assert.assertEquals(content.indexOf("同一份需求"), content.lastIndexOf("同一份需求"));
    }

    @Test
    public void shouldNotReturnInvalidatedAttachmentWhenRepositoryFiltersItOut() {
        IAssetRepository repository = Mockito.mock(IAssetRepository.class);
        Mockito.when(repository.queryContextAssets("tenant_1", "user_1", "session_1", 2, 6, 32, 131072))
                .thenReturn(List.of());
        ContextPolicyProperties properties = new ContextPolicyProperties();
        properties.setAttachmentTokens(1024);

        List<ContextContribution> result = new AssetContextContributor(repository).contribute(
                ContextAssembleRequest.builder().tenantId("tenant_1").userId("user_1").sessionId("session_1")
                        .coveredToSequence(2).attachmentVisibleThroughSequence(6).build(), properties);

        Assert.assertTrue(result.isEmpty());
    }

    @Test
    public void shouldApplyCandidateAndCumulativeContentBoundariesWithoutChangingSequenceScope() {
        IAssetRepository repository = Mockito.mock(IAssetRepository.class);
        Mockito.when(repository.queryContextAssets("tenant_1", "user_1", "session_1", 3, 11, 2, 5))
                .thenReturn(List.of(
                        AssetEntity.builder().assetId("asset_recent").fileName("新.txt")
                                .extractedText("1234").build(),
                        AssetEntity.builder().assetId("asset_old").fileName("旧.txt")
                                .extractedText("ABCDE").build()));
        ContextPolicyProperties properties = new ContextPolicyProperties();
        properties.setAttachmentTokens(1024);
        properties.setAttachmentCandidateLimit(2);
        properties.setAttachmentMaxContentChars(5);

        String content = new AssetContextContributor(repository).contribute(ContextAssembleRequest.builder()
                .tenantId("tenant_1").userId("user_1").sessionId("session_1")
                .coveredToSequence(3).attachmentVisibleThroughSequence(11).build(), properties)
                .get(0).getContent();

        Assert.assertTrue(content.contains("1234"));
        Assert.assertTrue(content.contains("A"));
        Assert.assertFalse(content.contains("AB"));
        Mockito.verify(repository).queryContextAssets("tenant_1", "user_1", "session_1", 3, 11, 2, 5);
    }
}
