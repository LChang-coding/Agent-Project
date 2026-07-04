package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.UserAccountPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 用户账号 DAO。
 * <p>负责 `user_account` 表的基础持久化操作。</p>
 */
@Mapper
public interface IUserAccountDao {

    /**
     * 新增用户账号记录。
     *
     * @param userAccount 用户账号持久化对象
     * @return 影响行数
     */
    int insert(UserAccountPO userAccount);

    /**
     * 按主键更新用户账号记录。
     *
     * @param userAccount 用户账号持久化对象
     * @return 影响行数
     */
    int updateById(UserAccountPO userAccount);

    /**
     * 按主键查询用户账号记录。
     *
     * @param id 主键ID
     * @return 用户账号持久化对象
     */
    UserAccountPO queryById(@Param("id") Long id);

    /**
     * 按用户业务ID查询用户账号记录。
     *
     * @param userId 用户业务ID
     * @return 用户账号持久化对象
     */
    UserAccountPO queryByUserId(@Param("userId") String userId);

    /**
     * 按用户名查询用户账号记录。
     *
     * @param username 用户名
     * @return 用户账号持久化对象
     */
    UserAccountPO queryByUsername(@Param("username") String username);

    /**
     * 按用户业务ID查询用户账号列表。
     *
     * @param userId 用户业务ID
     * @return 用户账号持久化对象列表
     */
    List<UserAccountPO> queryListByUserId(@Param("userId") String userId);
}
