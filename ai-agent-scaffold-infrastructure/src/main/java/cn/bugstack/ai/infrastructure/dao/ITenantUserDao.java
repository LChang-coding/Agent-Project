package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.TenantUserPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 租户用户关系 DAO。
 * <p>负责 `tenant_user` 表的基础持久化操作。</p>
 */
@Mapper
public interface ITenantUserDao {

    /**
     * 新增租户用户关系记录。
     *
     * @param tenantUser 租户用户关系持久化对象
     * @return 影响行数
     */
    int insert(TenantUserPO tenantUser);

    /**
     * 按主键更新租户用户关系记录。
     *
     * @param tenantUser 租户用户关系持久化对象
     * @return 影响行数
     */
    int updateById(TenantUserPO tenantUser);

    /**
     * 按主键查询租户用户关系记录。
     *
     * @param id 主键ID
     * @return 租户用户关系持久化对象
     */
    TenantUserPO queryById(@Param("id") Long id);

    /**
     * 按租户业务ID查询租户用户关系列表。
     *
     * @param tenantId 租户业务ID
     * @return 租户用户关系持久化对象列表
     */
    List<TenantUserPO> queryListByTenantId(@Param("tenantId") String tenantId);

    /**
     * 按用户业务ID查询租户用户关系列表。
     *
     * @param userId 用户业务ID
     * @return 租户用户关系持久化对象列表
     */
    List<TenantUserPO> queryListByUserId(@Param("userId") String userId);

    /**
     * 查询用户启用状态的租户关系。
     *
     * @param userId 用户业务ID
     * @return 启用状态的租户用户关系列表
     */
    List<TenantUserPO> queryActiveListByUserId(@Param("userId") String userId);
}
