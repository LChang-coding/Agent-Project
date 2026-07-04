package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.auth.adapter.repository.IAuthRepository;
import cn.bugstack.ai.domain.auth.model.entity.AuthUserEntity;
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

    private final ITenantDao tenantDao;
    private final IUserAccountDao userAccountDao;
    private final ITenantUserDao tenantUserDao;
    private final IUserSecretDao userSecretDao;

    public AuthRepository(ITenantDao tenantDao,
                          IUserAccountDao userAccountDao,
                          ITenantUserDao tenantUserDao,
                          IUserSecretDao userSecretDao) {
        this.tenantDao = tenantDao;
        this.userAccountDao = userAccountDao;
        this.tenantUserDao = tenantUserDao;
        this.userSecretDao = userSecretDao;
    }

    @Override
    public AuthUserEntity queryAuthUserByUsername(String username) {
        UserAccountPO userAccount = userAccountDao.queryByUsername(username);
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
                .userStatus(userAccount.getStatus())
                .roleCode(tenantUser == null ? null : tenantUser.getRoleCode())
                .tenantUserStatus(tenantUser == null ? null : tenantUser.getStatus())
                .passwordHash(passwordSecret == null ? null : passwordSecret.getSecretValueHash())
                .passwordExpireTime(passwordSecret == null ? null : passwordSecret.getExpireTime())
                .passwordStatus(passwordSecret == null ? null : passwordSecret.getStatus())
                .build();
    }

    @Override
    public boolean existsUsername(String username) {
        return userAccountDao.queryByUsername(username) != null;
    }

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
                .status("active")
                .build());

        userAccountDao.insert(UserAccountPO.builder()
                .userId(userId)
                .username(username)
                .nickname(nickname)
                .email(email)
                .phone(phone)
                .status("active")
                .build());

        tenantUserDao.insert(TenantUserPO.builder()
                .tenantId(tenantId)
                .userId(userId)
                .roleCode("owner")
                .status("active")
                .joinedTime(LocalDateTime.now())
                .build());

        userSecretDao.insert(UserSecretPO.builder()
                .tenantId(tenantId)
                .userId(userId)
                .secretType("password")
                .secretValueHash(passwordHash)
                .status("active")
                .build());
    }
}
