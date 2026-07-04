package cn.bugstack.ai.domain.auth.service;

import cn.bugstack.ai.types.context.LoginUser;

public interface IJwtTokenService {

    String generateToken(LoginUser loginUser);

    long expireSeconds();
}
