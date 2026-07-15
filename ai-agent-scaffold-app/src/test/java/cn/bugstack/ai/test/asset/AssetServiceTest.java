package cn.bugstack.ai.test.asset;

import cn.bugstack.ai.domain.asset.adapter.AssetTextExtractor;
import cn.bugstack.ai.domain.asset.adapter.IAssetRepository;
import cn.bugstack.ai.domain.asset.model.AssetEntity;
import cn.bugstack.ai.domain.asset.model.AssetParseResultEntity;
import cn.bugstack.ai.domain.asset.model.AssetUploadCommandEntity;
import cn.bugstack.ai.domain.asset.service.AssetService;
import cn.bugstack.ai.domain.session.service.SessionDomain;
import cn.bugstack.ai.domain.storage.model.entity.ObjectStorageResultEntity;
import cn.bugstack.ai.domain.storage.service.ObjectStorageService;
import cn.bugstack.ai.types.exception.AppException;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 资产领域服务测试。
 */
public class AssetServiceTest {

    @Test
    public void shouldUploadWithTrustedStoragePathAndParseResult() {
        IAssetRepository repository = Mockito.mock(IAssetRepository.class);
        AssetTextExtractor extractor = Mockito.mock(AssetTextExtractor.class);
        ObjectStorageService storage = Mockito.mock(ObjectStorageService.class);
        SessionDomain sessionDomain = Mockito.mock(SessionDomain.class);
        AssetService service = new AssetService(repository, extractor, storage, sessionDomain);
        byte[] bytes = "附件正文".getBytes(StandardCharsets.UTF_8);
        Mockito.when(storage.assetBucket()).thenReturn("assets");
        Mockito.when(storage.putObject(Mockito.any())).thenReturn(ObjectStorageResultEntity.builder()
                .sha256("a".repeat(64)).bucket("assets").objectKey("ignored").sizeBytes((long) bytes.length).build());
        Mockito.when(extractor.extract("../合同.md", "text/markdown", bytes)).thenReturn(
                AssetParseResultEntity.builder().parseStatus("ready").extractedText("附件正文").build());
        Mockito.when(repository.insert(Mockito.any())).thenAnswer(invocation -> invocation.getArgument(0));

        AssetEntity result = service.uploadChatAttachment(AssetUploadCommandEntity.builder()
                .tenantId("tenant_1").ownerUserId("user_1").sessionId("session_1")
                .fileName("../合同.md").mimeType("text/markdown").bytes(bytes).build());

        Assert.assertEquals("chat_attachment", result.getAssetKind());
        Assert.assertEquals("合同.md", result.getFileName());
        Assert.assertEquals("ready", result.getParseStatus());
        ArgumentCaptor<cn.bugstack.ai.domain.storage.model.entity.ObjectStorageCommandEntity> command =
                ArgumentCaptor.forClass(cn.bugstack.ai.domain.storage.model.entity.ObjectStorageCommandEntity.class);
        Mockito.verify(storage).putObject(command.capture());
        Assert.assertTrue(command.getValue().getObjectKey().startsWith("assets/tenant_1/user_1/"));
        Mockito.verify(sessionDomain).assertSessionAccess("tenant_1", "user_1", "session_1", null);
    }

    @Test
    public void shouldRejectBindingWhenAnyAssetIsNotReadyOrOwned() {
        IAssetRepository repository = Mockito.mock(IAssetRepository.class);
        AssetService service = new AssetService(repository, Mockito.mock(AssetTextExtractor.class),
                Mockito.mock(ObjectStorageService.class), Mockito.mock(SessionDomain.class));
        Mockito.when(repository.bindReadyAssets("tenant_1", "user_1", "session_1", "msg_1",
                List.of("asset_1", "asset_2"))).thenReturn(1);

        try {
            service.bindToMessage("tenant_1", "user_1", "session_1", "msg_1",
                    List.of("asset_1", "asset_2"));
            Assert.fail("应拒绝部分绑定");
        } catch (AppException e) {
            Assert.assertEquals("ASSET_BIND_DENIED", e.getCode());
        }
    }

    @Test
    public void shouldReuseObjectButCreateNewReference() {
        IAssetRepository repository = Mockito.mock(IAssetRepository.class);
        AssetTextExtractor extractor = Mockito.mock(AssetTextExtractor.class);
        ObjectStorageService storage = Mockito.mock(ObjectStorageService.class);
        AssetService service = new AssetService(repository, extractor, storage, Mockito.mock(SessionDomain.class));
        AssetEntity reusable = AssetEntity.builder().bucket("assets").objectKey("assets/t/u/hash.txt")
                .parseStatus("ready").extractedText("same").build();
        Mockito.when(repository.queryReusableByHash(Mockito.eq("tenant_1"), Mockito.eq("user_1"), Mockito.anyString()))
                .thenReturn(reusable);
        Mockito.when(repository.insert(Mockito.any())).thenAnswer(invocation -> invocation.getArgument(0));

        AssetEntity result = service.uploadChatAttachment(AssetUploadCommandEntity.builder().tenantId("tenant_1")
                .ownerUserId("user_1").fileName("a.txt").mimeType("text/plain")
                .bytes("same".getBytes(StandardCharsets.UTF_8)).build());

        Assert.assertEquals("assets/t/u/hash.txt", result.getObjectKey());
        Mockito.verify(storage, Mockito.never()).putObject(Mockito.any());
        Mockito.verify(extractor, Mockito.never()).extract(Mockito.anyString(), Mockito.anyString(), Mockito.any());
    }
}
