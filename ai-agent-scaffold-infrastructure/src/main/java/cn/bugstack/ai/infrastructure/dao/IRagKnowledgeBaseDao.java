package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.RagKnowledgeBasePO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 知识库 DAO。
 * <p>负责 `rag_knowledge_base` 表的基础持久化操作。</p>
 */
@Mapper
public interface IRagKnowledgeBaseDao {

    /**
     * 新增知识库记录。
     *
     * @param ragKnowledgeBase 知识库持久化对象
     * @return 影响行数
     */
    int insert(RagKnowledgeBasePO ragKnowledgeBase);

    /**
     * 按主键更新知识库记录。
     *
     * @param ragKnowledgeBase 知识库持久化对象
     * @return 影响行数
     */
    int updateById(RagKnowledgeBasePO ragKnowledgeBase);

    /**
     * 按主键查询知识库记录。
     *
     * @param id 主键ID
     * @return 知识库持久化对象
     */
    RagKnowledgeBasePO queryById(@Param("id") Long id);

    /**
     * 按知识库业务ID查询知识库记录。
     *
     * @param knowledgeBaseId 知识库业务ID
     * @return 知识库持久化对象
     */
    RagKnowledgeBasePO queryByKnowledgeBaseId(@Param("knowledgeBaseId") String knowledgeBaseId);

    /**
     * 按租户业务ID查询知识库列表。
     *
     * @param tenantId 租户业务ID
     * @return 知识库持久化对象列表
     */
    List<RagKnowledgeBasePO> queryListByTenantId(@Param("tenantId") String tenantId);

    /**
     * 按拥有者用户ID查询知识库列表。
     *
     * @param ownerUserId 拥有者用户ID
     * @return 知识库持久化对象列表
     */
    List<RagKnowledgeBasePO> queryListByOwnerUserId(@Param("ownerUserId") String ownerUserId);

    /**
     * 按知识库业务ID查询知识库列表。
     *
     * @param knowledgeBaseId 知识库业务ID
     * @return 知识库持久化对象列表
     */
    List<RagKnowledgeBasePO> queryListByKnowledgeBaseId(@Param("knowledgeBaseId") String knowledgeBaseId);

    /**
     * 按租户和可见范围查询知识库列表。
     *
     * @param tenantId 租户业务ID
     * @param visibility 可见范围：private/tenant_public
     * @return 知识库持久化对象列表
     */
    List<RagKnowledgeBasePO> queryListByTenantIdAndVisibility(@Param("tenantId") String tenantId, @Param("visibility") String visibility);
}
