package cn.bugstack.ai.domain.auth.service;

import cn.bugstack.ai.domain.auth.adapter.repository.IAuthRepository;
import cn.bugstack.ai.domain.auth.model.entity.AuthUserEntity;
import cn.bugstack.ai.domain.auth.model.entity.LoginCommandEntity;
import cn.bugstack.ai.domain.auth.model.entity.LoginResultEntity;
import cn.bugstack.ai.domain.auth.model.entity.RegisterCommandEntity;
import cn.bugstack.ai.domain.auth.model.entity.RegisterResultEntity;
import cn.bugstack.ai.types.context.LoginUser;
import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class AuthService implements IAuthService {

    private static final String ROLE_OWNER = "owner";
    private static final String STATUS_ACTIVE = "active";

    @Resource
    private IAuthRepository authRepository;

    @Resource
    private PasswordEncoder passwordEncoder;

    @Resource
    private IJwtTokenService jwtTokenService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RegisterResultEntity register(RegisterCommandEntity command) {
        checkRegisterCommand(command);
        String username = command.getUsername().trim();
        if (authRepository.existsUsername(username)) {
            throw new AppException(ResponseCode.AUTH_REGISTER_FAILED.getCode(), "用户名已存在");
        }

        String tenantId = "tenant_" + UUID.randomUUID();
        String userId = "user_" + UUID.randomUUID();
        String tenantName = blankToDefault(command.getTenantName(), username);
        String tenantCode = username + "_" + tenantId.substring(tenantId.length() - 8);
        String nickname = blankToDefault(command.getNickname(), username);
        String passwordHash = passwordEncoder.encode(command.getPassword());

        authRepository.registerTenantOwner(tenantId, tenantName, tenantCode, userId, username,
                nickname, command.getEmail(), command.getPhone(), passwordHash);

        return RegisterResultEntity.builder()
                .tenantId(tenantId)
                .userId(userId)
                .username(username)
                .roleCode(ROLE_OWNER)
                .build();
    }

    @Override
    public LoginResultEntity login(LoginCommandEntity command) {
        checkLoginCommand(command);
        AuthUserEntity authUser = authRepository.queryAuthUserByUsername(command.getUsername().trim());
        if (authUser == null
                || !STATUS_ACTIVE.equals(authUser.getUserStatus())
                || !STATUS_ACTIVE.equals(authUser.getTenantUserStatus())
                || !STATUS_ACTIVE.equals(authUser.getPasswordStatus())) {
            throw new AppException(ResponseCode.AUTH_LOGIN_FAILED.getCode(), "用户名或密码错误");
        }

        if (authUser.getPasswordExpireTime() != null && authUser.getPasswordExpireTime().isBefore(LocalDateTime.now())) {
            throw new AppException(ResponseCode.AUTH_LOGIN_FAILED.getCode(), "密码凭证已过期");
        }

        if (!passwordEncoder.matches(command.getPassword(), authUser.getPasswordHash())) {
            throw new AppException(ResponseCode.AUTH_LOGIN_FAILED.getCode(), "用户名或密码错误");
        }

        LoginUser loginUser = LoginUser.builder()
                .tenantId(authUser.getTenantId())
                .userId(authUser.getUserId())
                .username(authUser.getUsername())
                .roleCode(authUser.getRoleCode())
                .build();

        return LoginResultEntity.builder()
                .token(jwtTokenService.generateToken(loginUser))
                .tokenType("Bearer")
                .expiresIn(jwtTokenService.expireSeconds())
                .tenantId(authUser.getTenantId())
                .userId(authUser.getUserId())
                .username(authUser.getUsername())
                .roleCode(authUser.getRoleCode())
                .build();
    }

    private void checkRegisterCommand(RegisterCommandEntity command) {
        if (command == null
                || isBlank(command.getUsername())
                || isBlank(command.getPassword())) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "用户名和密码不能为空");
        }
    }

    private void checkLoginCommand(LoginCommandEntity command) {
        if (command == null
                || isBlank(command.getUsername())
                || isBlank(command.getPassword())) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "用户名和密码不能为空");
        }
    }

    private String blankToDefault(String value, String defaultValue) {
        return isBlank(value) ? defaultValue : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
