package cn.bugstack.ai.domain.auth.service;

import cn.bugstack.ai.types.context.LoginUser;

public interface IJwtTokenService {

    /**
     * 生成访问令牌；参数是登录用户；返回短期 access token。
     */
    String generateToken(LoginUser loginUser);

    /**
     * 生成刷新令牌；参数是登录用户；返回长期 refresh token。
     */
    String generateRefreshToken(LoginUser loginUser);

    /**
     * 解析访问令牌；参数是 access token；返回令牌中的登录用户。
     */
    LoginUser parseToken(String token);

    /**
     * 解析刷新令牌；参数是 refresh token；返回令牌中的登录用户。
     */
    LoginUser parseRefreshToken(String token);

    /**
     * 读取访问令牌有效期；无参数；返回秒数。
     */
    long expireSeconds();

    /**
     * 读取刷新令牌有效期；无参数；返回秒数。
     */
    long refreshExpireSeconds();
}
