package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.TenantPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 租户 DAO。
 * <p>负责 `tenant` 表的基础持久化操作。</p>
 */
@Mapper
public interface ITenantDao {

    /**
     * 查询未删除租户数量，用于数据库连通性检查。
     *
     * @return 未删除租户数量
     */
    int queryTenantCount();

    /**
     * 新增租户记录。
     *
     * @param tenant 租户持久化对象
     * @return 影响行数
     */
    int insert(TenantPO tenant);

    /**
     * 按主键更新租户记录。
     *
     * @param tenant 租户持久化对象
     * @return 影响行数
     */
    int updateById(TenantPO tenant);

    /**
     * 按主键查询租户记录。
     *
     * @param id 主键ID
     * @return 租户持久化对象
     */
    TenantPO queryById(@Param("id") Long id);

    /**
     * 按租户业务ID查询租户记录。
     *
     * @param tenantId 租户业务ID
     * @return 租户持久化对象
     */
    TenantPO queryByTenantId(@Param("tenantId") String tenantId);

    /**
     * 按租户业务ID查询租户列表。
     *
     * @param tenantId 租户业务ID
     * @return 租户持久化对象列表
     */
    List<TenantPO> queryListByTenantId(@Param("tenantId") String tenantId);
}
