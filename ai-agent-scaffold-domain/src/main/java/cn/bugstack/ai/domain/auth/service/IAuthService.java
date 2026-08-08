package cn.bugstack.ai.domain.auth.service;

import cn.bugstack.ai.domain.auth.model.entity.LoginCommandEntity;
import cn.bugstack.ai.domain.auth.model.entity.LoginResultEntity;
import cn.bugstack.ai.domain.auth.model.entity.RegisterCommandEntity;
import cn.bugstack.ai.domain.auth.model.entity.RegisterResultEntity;
import cn.bugstack.ai.domain.auth.model.entity.ChangePasswordCommandEntity;
import cn.bugstack.ai.domain.auth.model.entity.TokenRefreshCommandEntity;
import cn.bugstack.ai.domain.auth.model.entity.TokenResultEntity;
import cn.bugstack.ai.domain.auth.model.entity.UpdateProfileCommandEntity;
import cn.bugstack.ai.domain.auth.model.entity.UserProfileEntity;

/** 注册、登录、令牌轮换和当前用户维护用例。 */
public interface IAuthService {

    /**
     * 注册用户。
     */
    RegisterResultEntity register(RegisterCommandEntity command);

    /**
     * 登录用户。
     */
    LoginResultEntity login(LoginCommandEntity command);

    /**
     * 刷新令牌。
     */
    TokenResultEntity refresh(TokenRefreshCommandEntity command);

    /**
     * 查询当前用户。
     */
    UserProfileEntity currentUser(String userId);

    /**
     * 修改密码。
     */
    UserProfileEntity changePassword(ChangePasswordCommandEntity command);

    /**
     * 修改资料。
     */
    UserProfileEntity updateProfile(UpdateProfileCommandEntity command);
}
