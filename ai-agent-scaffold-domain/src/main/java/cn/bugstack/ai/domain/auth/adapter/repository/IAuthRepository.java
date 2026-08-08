package cn.bugstack.ai.domain.auth.adapter.repository;

import cn.bugstack.ai.domain.auth.model.entity.AuthUserEntity;
import cn.bugstack.ai.domain.auth.model.entity.RefreshTokenEntity;
import cn.bugstack.ai.domain.auth.model.entity.UpdateProfileCommandEntity;
import cn.bugstack.ai.domain.auth.model.entity.UserProfileEntity;

import java.time.LocalDateTime;

/** 认证账号、租户成员关系和刷新令牌的持久化契约。 */
public interface IAuthRepository {

    /**
     * 按用户名查询登录资料。
     */
    AuthUserEntity queryAuthUserByUsername(String username);

    /**
     * 按用户ID查询登录资料。
     */
    AuthUserEntity queryAuthUserByUserId(String userId);

    /**
     * 判断用户名是否存在。
     */
    boolean existsUsername(String username);

    /**
     * 注册租户管理员。
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
     * 查询用户资料。
     */
    UserProfileEntity queryUserProfileByUserId(String userId);

    /**
     * 保存刷新令牌。
     */
    void saveRefreshToken(String tenantId, String userId, String refreshTokenHash, LocalDateTime expireTime);

    /**
     * 查询可用刷新令牌。
     */
    RefreshTokenEntity queryActiveRefreshTokenByUserId(String userId);

    /**
     * 禁用刷新令牌。
     */
    int disableRefreshTokenByUserId(String userId);

    /**
     * 更新密码哈希。
     */
    int updatePasswordByUserId(String userId, String passwordHash);

    /**
     * 更新用户资料。
     */
    int updateProfileByUserId(UpdateProfileCommandEntity command);
}
