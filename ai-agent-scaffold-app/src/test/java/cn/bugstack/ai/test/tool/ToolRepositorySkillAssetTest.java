package cn.bugstack.ai.test.tool;

import cn.bugstack.ai.domain.tool.model.entity.SkillPackageUploadResultEntity;
import cn.bugstack.ai.infrastructure.adapter.repository.ToolRepository;
import cn.bugstack.ai.infrastructure.dao.IArtifactAssetDao;
import cn.bugstack.ai.infrastructure.dao.IMcpConfigVersionDao;
import cn.bugstack.ai.infrastructure.dao.IMcpServerConfigDao;
import cn.bugstack.ai.infrastructure.dao.ISkillDefinitionDao;
import cn.bugstack.ai.infrastructure.dao.ISkillVersionDao;
import cn.bugstack.ai.infrastructure.dao.IToolCallLogDao;
import cn.bugstack.ai.infrastructure.dao.po.ArtifactAssetPO;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** Skill 包资产持久化契约测试。 */
public class ToolRepositorySkillAssetTest {

    @Test
    public void shouldPersistNonNullSkillPackageKind() {
        IArtifactAssetDao assetDao = mock(IArtifactAssetDao.class);
        ToolRepository repository = new ToolRepository(assetDao, mock(ISkillDefinitionDao.class),
                mock(ISkillVersionDao.class), mock(IMcpServerConfigDao.class),
                mock(IMcpConfigVersionDao.class), mock(IToolCallLogDao.class));

        repository.saveSkillAsset("tenant", "user", SkillPackageUploadResultEntity.builder()
                .assetId("asset_1").bucket("skills").objectKey("tenant/skill.zip")
                .fileName("skill.zip").sizeBytes(128L).sha256("abc").build());

        ArgumentCaptor<ArtifactAssetPO> captor = ArgumentCaptor.forClass(ArtifactAssetPO.class);
        verify(assetDao).insert(captor.capture());
        Assert.assertEquals("skill_package", captor.getValue().getAssetKind());
        Assert.assertEquals("skill_package", captor.getValue().getAssetType());
        Assert.assertEquals("unsupported", captor.getValue().getParseStatus());
        Assert.assertEquals("abc", captor.getValue().getSha256());
    }
}
