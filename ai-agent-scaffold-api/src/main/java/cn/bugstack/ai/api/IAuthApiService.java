package cn.bugstack.ai.api;

import cn.bugstack.ai.api.dto.LoginRequestDTO;
import cn.bugstack.ai.api.dto.LoginResponseDTO;
import cn.bugstack.ai.api.dto.RegisterRequestDTO;
import cn.bugstack.ai.api.dto.RegisterResponseDTO;
import cn.bugstack.ai.api.dto.TokenRefreshRequestDTO;
import cn.bugstack.ai.api.dto.TokenRefreshResponseDTO;
import cn.bugstack.ai.api.dto.UpdatePasswordRequestDTO;
import cn.bugstack.ai.api.dto.UpdateProfileRequestDTO;
import cn.bugstack.ai.api.dto.UserProfileResponseDTO;
import cn.bugstack.ai.api.response.Response;

/**
 * 认证服务接口。
 */
public interface IAuthApiService {

    /**
     * 注册新用户；参数是租户、账号和密码；返回新租户、新用户和角色信息。
     */
    Response<RegisterResponseDTO> register(RegisterRequestDTO requestDTO);

    /**
     * 用户登录；参数是用户名和密码；返回访问令牌、刷新令牌和当前身份信息。
     */
    Response<LoginResponseDTO> login(LoginRequestDTO requestDTO);

    /**
     * 刷新令牌；参数是 refreshToken；返回新的访问令牌和刷新令牌。
     */
    Response<TokenRefreshResponseDTO> refresh(TokenRefreshRequestDTO requestDTO);

    /**
     * 自动登录检查；参数来自请求头里的访问令牌；返回当前登录用户信息。
     */
    Response<UserProfileResponseDTO> me();

    /**
     * 修改密码；参数是旧密码和新密码；返回当前用户信息。
     */
    Response<UserProfileResponseDTO> changePassword(UpdatePasswordRequestDTO requestDTO);

    /**
     * 修改资料；参数是允许修改的昵称、邮箱、手机号和头像；返回更新后的用户信息。
     */
    Response<UserProfileResponseDTO> updateProfile(UpdateProfileRequestDTO requestDTO);
}
