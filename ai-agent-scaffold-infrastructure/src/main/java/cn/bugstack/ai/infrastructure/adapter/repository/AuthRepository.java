package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.auth.adapter.repository.IAuthRepository;
import cn.bugstack.ai.domain.auth.model.entity.AuthUserEntity;
import cn.bugstack.ai.domain.auth.model.entity.RefreshTokenEntity;
import cn.bugstack.ai.domain.auth.model.entity.UpdateProfileCommandEntity;
import cn.bugstack.ai.domain.auth.model.entity.UserProfileEntity;
import cn.bugstack.ai.infrastructure.dao.ITenantDao;
import cn.bugstack.ai.infrastructure.dao.ITenantUserDao;
import cn.bugstack.ai.infrastructure.dao.IUserAccountDao;
import cn.bugstack.ai.infrastructure.dao.IUserSecretDao;
import cn.bugstack.ai.infrastructure.dao.po.TenantPO;
import cn.bugstack.ai.infrastructure.dao.po.TenantUserPO;
import cn.bugstack.ai.infrastructure.dao.po.UserAccountPO;
import cn.bugstack.ai.infrastructure.dao.po.UserSecretPO;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public class AuthRepository implements IAuthRepository {

    private static final String SECRET_TYPE_PASSWORD = "password";
    private static final String SECRET_TYPE_REFRESH_TOKEN = "refresh_token";
    private static final String STATUS_ACTIVE = "active";

    private final ITenantDao tenantDao;
    private final IUserAccountDao userAccountDao;
    private final ITenantUserDao tenantUserDao;
    private final IUserSecretDao userSecretDao;

    /**
     * 创建认证仓储；参数是认证相关 DAO；返回仓储实例。
     */
    public AuthRepository(ITenantDao tenantDao,
                          IUserAccountDao userAccountDao,
                          ITenantUserDao tenantUserDao,
                          IUserSecretDao userSecretDao) {
        this.tenantDao = tenantDao;
        this.userAccountDao = userAccountDao;
        this.tenantUserDao = tenantUserDao;
        this.userSecretDao = userSecretDao;
    }

    /**
     * 按用户名查询登录资料；参数是 username；返回账号、密码和租户关系。
     */
    @Override
    public AuthUserEntity queryAuthUserByUsername(String username) {
        UserAccountPO userAccount = userAccountDao.queryByUsername(username);
        return buildAuthUser(userAccount);
    }

    /**
     * 按用户ID查询登录资料；参数是 userId；返回账号、密码和租户关系。
     */
    @Override
    public AuthUserEntity queryAuthUserByUserId(String userId) {
        UserAccountPO userAccount = userAccountDao.queryByUserId(userId);
        return buildAuthUser(userAccount);
    }

    /**
     * 判断用户名是否存在；参数是 username；返回是否存在。
     */
    @Override
    public boolean existsUsername(String username) {
        return userAccountDao.queryByUsername(username) != null;
    }

    /**
     * 注册租户管理员；参数是租户、账号和密码哈希；无返回值。
     */
    @Override
    public void registerTenantOwner(String tenantId,
                                    String tenantName,
                                    String tenantCode,
                                    String userId,
                                    String username,
                                    String nickname,
                                    String email,
                                    String phone,
                                    String passwordHash) {
        tenantDao.insert(TenantPO.builder()
                .tenantId(tenantId)
                .tenantName(tenantName)
                .tenantCode(tenantCode)
                .status(STATUS_ACTIVE)
                .build());

        userAccountDao.insert(UserAccountPO.builder()
                .userId(userId)
                .username(username)
                .nickname(nickname)
                .email(email)
                .phone(phone)
                .status(STATUS_ACTIVE)
                .build());

        tenantUserDao.insert(TenantUserPO.builder()
                .tenantId(tenantId)
                .userId(userId)
                .roleCode("owner")
                .status(STATUS_ACTIVE)
                .joinedTime(LocalDateTime.now())
                .build());

        userSecretDao.insert(UserSecretPO.builder()
                .tenantId(tenantId)
                .userId(userId)
                .secretType(SECRET_TYPE_PASSWORD)
                .secretValueHash(passwordHash)
                .status(STATUS_ACTIVE)
                .build());
    }

    /**
     * 查询用户资料；参数是 userId；返回当前用户资料。
     */
    @Override
    public UserProfileEntity queryUserProfileByUserId(String userId) {
        UserAccountPO userAccount = userAccountDao.queryByUserId(userId);
        if (userAccount == null) {
            return null;
        }

        List<TenantUserPO> tenantUsers = tenantUserDao.queryActiveListByUserId(userAccount.getUserId());
        TenantUserPO tenantUser = tenantUsers == null || tenantUsers.isEmpty() ? null : tenantUsers.get(0);

        return UserProfileEntity.builder()
                .tenantId(tenantUser == null ? null : tenantUser.getTenantId())
                .userId(userAccount.getUserId())
                .username(userAccount.getUsername())
                .nickname(userAccount.getNickname())
                .email(userAccount.getEmail())
                .phone(userAccount.getPhone())
                .avatar(userAccount.getAvatar())
                .roleCode(tenantUser == null ? null : tenantUser.getRoleCode())
                .build();
    }

    /**
     * 保存刷新令牌；参数是用户、令牌哈希和过期时间；无返回值。
     */
    @Override
    public void saveRefreshToken(String tenantId, String userId, String refreshTokenHash, LocalDateTime expireTime) {
        disableRefreshTokenByUserId(userId);
        userSecretDao.insert(UserSecretPO.builder()
                .tenantId(tenantId)
                .userId(userId)
                .secretType(SECRET_TYPE_REFRESH_TOKEN)
                .secretValueHash(refreshTokenHash)
                .expireTime(expireTime)
                .status(STATUS_ACTIVE)
                .build());
    }

    /**
     * 查询可用刷新令牌；参数是 userId；返回刷新令牌哈希和状态。
     */
    @Override
    public RefreshTokenEntity queryActiveRefreshTokenByUserId(String userId) {
        UserSecretPO userSecret = userSecretDao.queryActiveByUserIdAndType(userId, SECRET_TYPE_REFRESH_TOKEN);
        if (userSecret == null) {
            return null;
        }
        return RefreshTokenEntity.builder()
                .tenantId(userSecret.getTenantId())
                .userId(userSecret.getUserId())
                .tokenHash(userSecret.getSecretValueHash())
                .expireTime(userSecret.getExpireTime())
                .status(userSecret.getStatus())
                .build();
    }

    /**
     * 禁用刷新令牌；参数是 userId；返回影响行数。
     */
    @Override
    public int disableRefreshTokenByUserId(String userId) {
        return userSecretDao.disableActiveByUserIdAndType(userId, SECRET_TYPE_REFRESH_TOKEN);
    }

    /**
     * 更新密码哈希；参数是 userId 和新密码哈希；返回影响行数。
     */
    @Override
    public int updatePasswordByUserId(String userId, String passwordHash) {
        UserSecretPO userSecret = userSecretDao.queryPasswordByUserId(userId);
        if (userSecret == null) {
            return 0;
        }
        userSecret.setSecretValueHash(passwordHash);
        userSecret.setStatus(STATUS_ACTIVE);
        return userSecretDao.updateById(userSecret);
    }

    /**
     * 更新用户资料；参数是允许修改的资料字段；返回影响行数。
     */
    @Override
    public int updateProfileByUserId(UpdateProfileCommandEntity command) {
        return userAccountDao.updateProfileByUserId(UserAccountPO.builder()
                .userId(command.getUserId())
                .nickname(command.getNickname())
                .email(command.getEmail())
                .phone(command.getPhone())
                .avatar(command.getAvatar())
                .build());
    }

    /**
     * 组装登录资料；参数是用户账号；返回账号、密码和租户关系。
     */
    private AuthUserEntity buildAuthUser(UserAccountPO userAccount) {
        if (userAccount == null) {
            return null;
        }
        UserSecretPO passwordSecret = userSecretDao.queryPasswordByUserId(userAccount.getUserId());
        List<TenantUserPO> tenantUsers = tenantUserDao.queryActiveListByUserId(userAccount.getUserId());
        TenantUserPO tenantUser = tenantUsers == null || tenantUsers.isEmpty() ? null : tenantUsers.get(0);

        return AuthUserEntity.builder()
                .tenantId(tenantUser == null ? null : tenantUser.getTenantId())
                .userId(userAccount.getUserId())
                .username(userAccount.getUsername())
                .nickname(userAccount.getNickname())
                .email(userAccount.getEmail())
                .phone(userAccount.getPhone())
                .avatar(userAccount.getAvatar())
                .userStatus(userAccount.getStatus())
                .roleCode(tenantUser == null ? null : tenantUser.getRoleCode())
                .tenantUserStatus(tenantUser == null ? null : tenantUser.getStatus())
                .passwordHash(passwordSecret == null ? null : passwordSecret.getSecretValueHash())
                .passwordExpireTime(passwordSecret == null ? null : passwordSecret.getExpireTime())
                .passwordStatus(passwordSecret == null ? null : passwordSecret.getStatus())
                .build();
    }
}
