package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.SkillVersionPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Skill 版本 DAO。
 * <p>负责 `skill_version` 表的基础持久化操作。</p>
 */
@Mapper
public interface ISkillVersionDao {

    /**
     * 新增 Skill 版本；参数是版本持久化对象；返回影响行数。
     */
    int insert(SkillVersionPO skillVersion);

    /**
     * 按主键更新 Skill 版本；参数是版本持久化对象；返回影响行数。
     */
    int updateById(SkillVersionPO skillVersion);

    /**
     * 按版本业务ID查询 Skill 版本；参数是版本业务ID；返回版本持久化对象。
     */
    SkillVersionPO queryByVersionId(@Param("versionId") String versionId);

    /**
     * 按 Skill 和版本号查询版本；参数是 Skill ID 和版本号；返回版本持久化对象。
     */
    SkillVersionPO queryBySkillIdAndVersion(@Param("skillId") String skillId, @Param("version") String version);

    /**
     * 查询 Skill 的版本列表；参数是 Skill ID；返回版本列表。
     */
    List<SkillVersionPO> queryListBySkillId(@Param("skillId") String skillId);

    /**
     * 查询 Skill 当前生效版本；参数是 Skill ID；返回 active 版本。
     */
    SkillVersionPO queryActiveBySkillId(@Param("skillId") String skillId);
}
