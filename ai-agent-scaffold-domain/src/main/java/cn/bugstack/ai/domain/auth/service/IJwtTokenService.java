package cn.bugstack.ai.domain.auth.service;

import cn.bugstack.ai.types.context.LoginUser;

/** Access/Refresh JWT 的签发、解析和有效期契约。 */
public interface IJwtTokenService {

    /**
     * 生成访问令牌。
     */
    String generateToken(LoginUser loginUser);

    /**
     * 生成刷新令牌。
     */
    String generateRefreshToken(LoginUser loginUser);

    /**
     * 解析访问令牌。
     */
    LoginUser parseToken(String token);

    /**
     * 解析刷新令牌。
     */
    LoginUser parseRefreshToken(String token);

    /**
     * 读取访问令牌有效期；返回秒数。
     */
    long expireSeconds();

    /**
     * 读取刷新令牌有效期；返回秒数。
     */
    long refreshExpireSeconds();
}
