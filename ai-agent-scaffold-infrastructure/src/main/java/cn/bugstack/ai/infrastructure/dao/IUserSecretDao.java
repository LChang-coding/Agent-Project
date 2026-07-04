package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.UserSecretPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 用户凭证 DAO。
 * <p>负责 `user_secret` 表的基础持久化操作。</p>
 */
@Mapper
public interface IUserSecretDao {

    /**
     * 新增用户凭证记录。
     *
     * @param userSecret 用户凭证持久化对象
     * @return 影响行数
     */
    int insert(UserSecretPO userSecret);

    /**
     * 按主键更新用户凭证记录。
     *
     * @param userSecret 用户凭证持久化对象
     * @return 影响行数
     */
    int updateById(UserSecretPO userSecret);

    /**
     * 按主键查询用户凭证记录。
     *
     * @param id 主键ID
     * @return 用户凭证持久化对象
     */
    UserSecretPO queryById(@Param("id") Long id);

    /**
     * 按租户业务ID查询用户凭证列表。
     *
     * @param tenantId 租户业务ID
     * @return 用户凭证持久化对象列表
     */
    List<UserSecretPO> queryListByTenantId(@Param("tenantId") String tenantId);

    /**
     * 按用户业务ID查询用户凭证列表。
     *
     * @param userId 用户业务ID
     * @return 用户凭证持久化对象列表
     */
    List<UserSecretPO> queryListByUserId(@Param("userId") String userId);

    /**
     * 查询用户可用密码凭证。
     *
     * @param userId 用户业务ID
     * @return 用户密码凭证持久化对象
     */
    UserSecretPO queryPasswordByUserId(@Param("userId") String userId);
}
