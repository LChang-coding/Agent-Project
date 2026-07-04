package cn.bugstack.ai.domain.auth.adapter.repository;

import cn.bugstack.ai.domain.auth.model.entity.AuthUserEntity;
import cn.bugstack.ai.domain.auth.model.entity.RefreshTokenEntity;
import cn.bugstack.ai.domain.auth.model.entity.UpdateProfileCommandEntity;
import cn.bugstack.ai.domain.auth.model.entity.UserProfileEntity;

import java.time.LocalDateTime;

public interface IAuthRepository {

    /**
     * 按用户名查询登录资料；参数是 username；返回账号、密码和租户关系。
     */
    AuthUserEntity queryAuthUserByUsername(String username);

    /**
     * 按用户ID查询登录资料；参数是 userId；返回账号、密码和租户关系。
     */
    AuthUserEntity queryAuthUserByUserId(String userId);

    /**
     * 判断用户名是否存在；参数是 username；返回是否已存在。
     */
    boolean existsUsername(String username);

    /**
     * 注册租户管理员；参数是租户、账号和密码哈希；无返回值。
     */
    void registerTenantOwner(String tenantId,
                             String tenantName,
                             String tenantCode,
                             String userId,
                             String username,
                             String nickname,
                             String email,
                             String phone,
                             String passwordHash);

    /**
     * 查询用户资料；参数是 userId；返回当前用户资料。
     */
    UserProfileEntity queryUserProfileByUserId(String userId);

    /**
     * 保存刷新令牌；参数是用户、令牌哈希和过期时间；无返回值。
     */
    void saveRefreshToken(String tenantId, String userId, String refreshTokenHash, LocalDateTime expireTime);

    /**
     * 查询可用刷新令牌；参数是 userId；返回刷新令牌哈希和状态。
     */
    RefreshTokenEntity queryActiveRefreshTokenByUserId(String userId);

    /**
     * 禁用刷新令牌；参数是 userId；返回影响行数。
     */
    int disableRefreshTokenByUserId(String userId);

    /**
     * 更新密码哈希；参数是 userId 和新密码哈希；返回影响行数。
     */
    int updatePasswordByUserId(String userId, String passwordHash);

    /**
     * 更新用户资料；参数是允许修改的资料字段；返回影响行数。
     */
    int updateProfileByUserId(UpdateProfileCommandEntity command);
}
