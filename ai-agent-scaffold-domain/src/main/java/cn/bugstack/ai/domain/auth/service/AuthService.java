package cn.bugstack.ai.domain.auth.service;

import cn.bugstack.ai.domain.auth.adapter.repository.IAuthRepository;
import cn.bugstack.ai.domain.auth.model.entity.AuthUserEntity;
import cn.bugstack.ai.domain.auth.model.entity.ChangePasswordCommandEntity;
import cn.bugstack.ai.domain.auth.model.entity.LoginCommandEntity;
import cn.bugstack.ai.domain.auth.model.entity.LoginResultEntity;
import cn.bugstack.ai.domain.auth.model.entity.RefreshTokenEntity;
import cn.bugstack.ai.domain.auth.model.entity.RegisterCommandEntity;
import cn.bugstack.ai.domain.auth.model.entity.RegisterResultEntity;
import cn.bugstack.ai.domain.auth.model.entity.TokenRefreshCommandEntity;
import cn.bugstack.ai.domain.auth.model.entity.TokenResultEntity;
import cn.bugstack.ai.domain.auth.model.entity.UpdateProfileCommandEntity;
import cn.bugstack.ai.domain.auth.model.entity.UserProfileEntity;
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
    private static final String TOKEN_TYPE_BEARER = "Bearer";

    @Resource
    private IAuthRepository authRepository;

    @Resource
    private PasswordEncoder passwordEncoder;

    @Resource
    private IJwtTokenService jwtTokenService;

    /**
     * 注册用户；参数是租户、账号和密码；返回新用户身份信息。
     */
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

    /**
     * 登录用户；参数是账号和密码；返回访问令牌和刷新令牌。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public LoginResultEntity login(LoginCommandEntity command) {
        checkLoginCommand(command);
        AuthUserEntity authUser = authRepository.queryAuthUserByUsername(command.getUsername().trim());
        checkAuthUser(authUser, ResponseCode.AUTH_LOGIN_FAILED, "用户名或密码错误");
        if (!passwordEncoder.matches(command.getPassword(), authUser.getPasswordHash())) {
            throw new AppException(ResponseCode.AUTH_LOGIN_FAILED.getCode(), "用户名或密码错误");
        }

        TokenResultEntity tokenResult = issueTokens(authUser);

        return LoginResultEntity.builder()
                .token(tokenResult.getToken())
                .refreshToken(tokenResult.getRefreshToken())
                .tokenType(tokenResult.getTokenType())
                .expiresIn(tokenResult.getExpiresIn())
                .refreshExpiresIn(tokenResult.getRefreshExpiresIn())
                .tenantId(tokenResult.getTenantId())
                .userId(tokenResult.getUserId())
                .username(tokenResult.getUsername())
                .roleCode(tokenResult.getRoleCode())
                .build();
    }

    /**
     * 刷新令牌；参数是 refreshToken；返回新的访问令牌和刷新令牌。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public TokenResultEntity refresh(TokenRefreshCommandEntity command) {
        checkRefreshCommand(command);
        LoginUser loginUser;
        try {
            loginUser = jwtTokenService.parseRefreshToken(command.getRefreshToken());
        } catch (Exception e) {
            throw new AppException(ResponseCode.AUTH_REFRESH_FAILED.getCode(), "刷新令牌无效或已过期");
        }

        RefreshTokenEntity refreshToken = authRepository.queryActiveRefreshTokenByUserId(loginUser.getUserId());
        if (refreshToken == null
                || refreshToken.getExpireTime() == null
                || refreshToken.getExpireTime().isBefore(LocalDateTime.now())
                || !passwordEncoder.matches(command.getRefreshToken(), refreshToken.getTokenHash())) {
            throw new AppException(ResponseCode.AUTH_REFRESH_FAILED.getCode(), "刷新令牌无效或已过期");
        }

        AuthUserEntity authUser = authRepository.queryAuthUserByUserId(loginUser.getUserId());
        checkAuthUser(authUser, ResponseCode.AUTH_REFRESH_FAILED, "当前用户不可用");
        return issueTokens(authUser);
    }

    /**
     * 查询当前用户；参数是 userId；返回当前用户资料。
     */
    @Override
    public UserProfileEntity currentUser(String userId) {
        if (isBlank(userId)) {
            throw new AppException(ResponseCode.AUTH_UNAUTHORIZED.getCode(), "未登录或登录已过期");
        }
        UserProfileEntity userProfile = authRepository.queryUserProfileByUserId(userId);
        if (userProfile == null) {
            throw new AppException(ResponseCode.AUTH_UNAUTHORIZED.getCode(), "当前用户不存在");
        }
        return userProfile;
    }

    /**
     * 修改密码；参数是 userId、旧密码和新密码；返回当前用户资料。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserProfileEntity changePassword(ChangePasswordCommandEntity command) {
        checkChangePasswordCommand(command);
        AuthUserEntity authUser = authRepository.queryAuthUserByUserId(command.getUserId());
        checkAuthUser(authUser, ResponseCode.AUTH_CHANGE_PASSWORD_FAILED, "当前用户不可用");
        if (!passwordEncoder.matches(command.getOldPassword(), authUser.getPasswordHash())) {
            throw new AppException(ResponseCode.AUTH_CHANGE_PASSWORD_FAILED.getCode(), "旧密码不正确");
        }

        int count = authRepository.updatePasswordByUserId(command.getUserId(), passwordEncoder.encode(command.getNewPassword()));
        if (count <= 0) {
            throw new AppException(ResponseCode.AUTH_CHANGE_PASSWORD_FAILED.getCode(), "密码更新失败");
        }
        authRepository.disableRefreshTokenByUserId(command.getUserId());
        return currentUser(command.getUserId());
    }

    /**
     * 修改资料；参数是 userId 和允许修改的资料字段；返回更新后的用户资料。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserProfileEntity updateProfile(UpdateProfileCommandEntity command) {
        checkUpdateProfileCommand(command);
        UserProfileEntity currentProfile = currentUser(command.getUserId());
        command.setNickname(valueOrDefault(command.getNickname(), currentProfile.getNickname()));
        command.setEmail(valueOrDefault(command.getEmail(), currentProfile.getEmail()));
        command.setPhone(valueOrDefault(command.getPhone(), currentProfile.getPhone()));
        command.setAvatar(valueOrDefault(command.getAvatar(), currentProfile.getAvatar()));
        int count = authRepository.updateProfileByUserId(command);
        if (count <= 0) {
            throw new AppException(ResponseCode.AUTH_UPDATE_PROFILE_FAILED.getCode(), "用户资料更新失败");
        }
        return currentUser(command.getUserId());
    }

    /**
     * 校验注册参数；参数是注册命令；非法时抛出异常。
     */
    private void checkRegisterCommand(RegisterCommandEntity command) {
        if (command == null
                || isBlank(command.getUsername())
                || isBlank(command.getPassword())) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "用户名和密码不能为空");
        }
    }

    /**
     * 校验登录参数；参数是登录命令；非法时抛出异常。
     */
    private void checkLoginCommand(LoginCommandEntity command) {
        if (command == null
                || isBlank(command.getUsername())
                || isBlank(command.getPassword())) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "用户名和密码不能为空");
        }
    }

    /**
     * 校验刷新参数；参数是刷新命令；非法时抛出异常。
     */
    private void checkRefreshCommand(TokenRefreshCommandEntity command) {
        if (command == null || isBlank(command.getRefreshToken())) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "刷新令牌不能为空");
        }
    }

    /**
     * 校验改密参数；参数是改密命令；非法时抛出异常。
     */
    private void checkChangePasswordCommand(ChangePasswordCommandEntity command) {
        if (command == null
                || isBlank(command.getUserId())
                || isBlank(command.getOldPassword())
                || isBlank(command.getNewPassword())) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "用户ID、旧密码和新密码不能为空");
        }
    }

    /**
     * 校验资料参数；参数是资料命令；非法时抛出异常。
     */
    private void checkUpdateProfileCommand(UpdateProfileCommandEntity command) {
        if (command == null || isBlank(command.getUserId())) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "用户ID不能为空");
        }
    }

    /**
     * 校验用户可登录状态；参数是登录资料和错误码；非法时抛出异常。
     */
    private void checkAuthUser(AuthUserEntity authUser, ResponseCode responseCode, String message) {
        if (authUser == null
                || !STATUS_ACTIVE.equals(authUser.getUserStatus())
                || !STATUS_ACTIVE.equals(authUser.getTenantUserStatus())
                || !STATUS_ACTIVE.equals(authUser.getPasswordStatus())) {
            throw new AppException(responseCode.getCode(), message);
        }

        if (authUser.getPasswordExpireTime() != null && authUser.getPasswordExpireTime().isBefore(LocalDateTime.now())) {
            throw new AppException(responseCode.getCode(), "密码凭证已过期");
        }
    }

    /**
     * 签发双令牌；参数是登录资料；返回访问令牌和刷新令牌。
     */
    private TokenResultEntity issueTokens(AuthUserEntity authUser) {
        LoginUser loginUser = LoginUser.builder()
                .tenantId(authUser.getTenantId())
                .userId(authUser.getUserId())
                .username(authUser.getUsername())
                .roleCode(authUser.getRoleCode())
                .build();
        String token = jwtTokenService.generateToken(loginUser);
        String refreshToken = jwtTokenService.generateRefreshToken(loginUser);
        authRepository.saveRefreshToken(
                authUser.getTenantId(),
                authUser.getUserId(),
                passwordEncoder.encode(refreshToken),
                LocalDateTime.now().plusSeconds(jwtTokenService.refreshExpireSeconds())
        );
        return TokenResultEntity.builder()
                .token(token)
                .refreshToken(refreshToken)
                .tokenType(TOKEN_TYPE_BEARER)
                .expiresIn(jwtTokenService.expireSeconds())
                .refreshExpiresIn(jwtTokenService.refreshExpireSeconds())
                .tenantId(authUser.getTenantId())
                .userId(authUser.getUserId())
                .username(authUser.getUsername())
                .roleCode(authUser.getRoleCode())
                .build();
    }

    /**
     * 取默认字符串；参数是候选值和默认值；返回非空字符串。
     */
    private String blankToDefault(String value, String defaultValue) {
        return isBlank(value) ? defaultValue : value.trim();
    }

    /**
     * 取更新字符串；参数是候选值和默认值；返回可保存的字段值。
     */
    private String valueOrDefault(String value, String defaultValue) {
        return value == null ? defaultValue : value.trim();
    }

    /**
     * 判断字符串为空；参数是字符串；返回是否为空。
     */
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
