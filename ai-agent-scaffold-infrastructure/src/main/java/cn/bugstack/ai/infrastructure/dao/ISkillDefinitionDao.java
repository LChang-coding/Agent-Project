package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.SkillDefinitionPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Skill 定义 DAO。
 * <p>负责 `skill_definition` 表的基础持久化操作。</p>
 */
@Mapper
public interface ISkillDefinitionDao {

    /**
     * 新增Skill 定义记录。
     *
     * @param skillDefinition Skill 定义持久化对象
     * @return 影响行数
     */
    int insert(SkillDefinitionPO skillDefinition);

    /**
     * 按主键更新Skill 定义记录。
     *
     * @param skillDefinition Skill 定义持久化对象
     * @return 影响行数
     */
    int updateById(SkillDefinitionPO skillDefinition);

    /**
     * 按主键查询Skill 定义记录。
     *
     * @param id 主键ID
     * @return Skill 定义持久化对象
     */
    SkillDefinitionPO queryById(@Param("id") Long id);

    /**
     * 按Skill 业务ID查询Skill 定义记录。
     *
     * @param skillId Skill 业务ID
     * @return Skill 定义持久化对象
     */
    SkillDefinitionPO queryBySkillId(@Param("skillId") String skillId);

    /**
     * 按租户业务ID查询Skill 定义列表。
     *
     * @param tenantId 租户业务ID
     * @return Skill 定义持久化对象列表
     */
    List<SkillDefinitionPO> queryListByTenantId(@Param("tenantId") String tenantId);

    /**
     * 按拥有者用户ID查询Skill 定义列表。
     *
     * @param ownerUserId 拥有者用户ID
     * @return Skill 定义持久化对象列表
     */
    List<SkillDefinitionPO> queryListByOwnerUserId(@Param("ownerUserId") String ownerUserId);

    /**
     * 按租户和可见范围查询Skill 定义列表。
     *
     * @param tenantId 租户业务ID
     * @param visibility 可见范围：private/tenant_public
     * @return Skill 定义持久化对象列表
     */
    List<SkillDefinitionPO> queryListByTenantIdAndVisibility(@Param("tenantId") String tenantId, @Param("visibility") String visibility);
}
