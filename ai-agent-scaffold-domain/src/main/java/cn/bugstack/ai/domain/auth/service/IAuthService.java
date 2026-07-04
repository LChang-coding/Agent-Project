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

public interface IAuthService {

    /**
     * 注册用户；参数是租户、账号和密码；返回新用户身份信息。
     */
    RegisterResultEntity register(RegisterCommandEntity command);

    /**
     * 登录用户；参数是账号和密码；返回访问令牌和刷新令牌。
     */
    LoginResultEntity login(LoginCommandEntity command);

    /**
     * 刷新令牌；参数是 refreshToken；返回新的访问令牌和刷新令牌。
     */
    TokenResultEntity refresh(TokenRefreshCommandEntity command);

    /**
     * 查询当前用户；参数是 userId；返回当前用户资料。
     */
    UserProfileEntity currentUser(String userId);

    /**
     * 修改密码；参数是 userId、旧密码和新密码；返回当前用户资料。
     */
    UserProfileEntity changePassword(ChangePasswordCommandEntity command);

    /**
     * 修改资料；参数是 userId 和允许修改的资料字段；返回更新后的用户资料。
     */
    UserProfileEntity updateProfile(UpdateProfileCommandEntity command);
}
