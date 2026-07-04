package cn.bugstack.ai.domain.auth.adapter.repository;

import cn.bugstack.ai.domain.auth.model.entity.AuthUserEntity;

public interface IAuthRepository {

    AuthUserEntity queryAuthUserByUsername(String username);

    boolean existsUsername(String username);

    void registerTenantOwner(String tenantId,
                             String tenantName,
                             String tenantCode,
                             String userId,
                             String username,
                             String nickname,
                             String email,
                             String phone,
                             String passwordHash);
}
