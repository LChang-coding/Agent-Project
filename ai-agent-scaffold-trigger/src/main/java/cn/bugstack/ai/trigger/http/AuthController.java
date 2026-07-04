package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.api.IAuthApiService;
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
import cn.bugstack.ai.domain.auth.model.entity.ChangePasswordCommandEntity;
import cn.bugstack.ai.domain.auth.model.entity.LoginCommandEntity;
import cn.bugstack.ai.domain.auth.model.entity.LoginResultEntity;
import cn.bugstack.ai.domain.auth.model.entity.RegisterCommandEntity;
import cn.bugstack.ai.domain.auth.model.entity.RegisterResultEntity;
import cn.bugstack.ai.domain.auth.model.entity.TokenRefreshCommandEntity;
import cn.bugstack.ai.domain.auth.model.entity.TokenResultEntity;
import cn.bugstack.ai.domain.auth.model.entity.UpdateProfileCommandEntity;
import cn.bugstack.ai.domain.auth.model.entity.UserProfileEntity;
import cn.bugstack.ai.domain.auth.service.IAuthService;
import cn.bugstack.ai.types.context.TenantContextHolder;
import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;
import cn.bugstack.ai.types.observability.AiLog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@Slf4j
@RestController
@RequestMapping("/api/v1/auth/")
@CrossOrigin(origins = "*")
public class AuthController implements IAuthApiService {

    @Resource
    private IAuthService authService;

    /**
     * 注册用户；参数是注册请求；返回新用户身份信息。
     */
    @RequestMapping(value = "register", method = RequestMethod.POST)
    @Override
    public Response<RegisterResponseDTO> register(@RequestBody RegisterRequestDTO requestDTO) {
        try {
            RegisterCommandEntity command = new RegisterCommandEntity();
            command.setTenantName(requestDTO.getTenantName());
            command.setUsername(requestDTO.getUsername());
            command.setPassword(requestDTO.getPassword());
            command.setNickname(requestDTO.getNickname());
            command.setEmail(requestDTO.getEmail());
            command.setPhone(requestDTO.getPhone());
            RegisterResultEntity result = authService.register(command);

            RegisterResponseDTO responseDTO = new RegisterResponseDTO();
            responseDTO.setTenantId(result.getTenantId());
            responseDTO.setUserId(result.getUserId());
            responseDTO.setUsername(result.getUsername());
            responseDTO.setRoleCode(result.getRoleCode());

            AiLog.info(AiLog.auth().registerSuccess(
                    result.getTenantId(),
                    result.getUserId(),
                    result.getUsername(),
                    result.getRoleCode()
            ));
            log.info("用户注册成功 username:{} tenantId:{} userId:{} roleCode:{}",
                    result.getUsername(), result.getTenantId(), result.getUserId(), result.getRoleCode());

            return Response.<RegisterResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();
        } catch (AppException e) {
            AiLog.error(AiLog.auth().registerFailed(
                    safeUsername(requestDTO),
                    e.getCode(),
                    e.getInfo()
            ));
            log.error("用户注册失败 username:{}", requestDTO == null ? null : requestDTO.getUsername(), e);
            return Response.<RegisterResponseDTO>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            AiLog.error(AiLog.auth().registerFailed(
                    safeUsername(requestDTO),
                    ResponseCode.UN_ERROR.getCode(),
                    ResponseCode.UN_ERROR.getInfo()
            ).error(e));
            log.error("用户注册异常 username:{}", requestDTO == null ? null : requestDTO.getUsername(), e);
            return Response.<RegisterResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    /**
     * 用户登录；参数是账号和密码；返回访问令牌和刷新令牌。
     */
    @RequestMapping(value = "login", method = RequestMethod.POST)
    @Override
    public Response<LoginResponseDTO> login(@RequestBody LoginRequestDTO requestDTO) {
        try {
            LoginCommandEntity command = new LoginCommandEntity();
            command.setUsername(requestDTO.getUsername());
            command.setPassword(requestDTO.getPassword());
            LoginResultEntity result = authService.login(command);

            LoginResponseDTO responseDTO = new LoginResponseDTO();
            responseDTO.setToken(result.getToken());
            responseDTO.setRefreshToken(result.getRefreshToken());
            responseDTO.setTokenType(result.getTokenType());
            responseDTO.setExpiresIn(result.getExpiresIn());
            responseDTO.setRefreshExpiresIn(result.getRefreshExpiresIn());
            responseDTO.setTenantId(result.getTenantId());
            responseDTO.setUserId(result.getUserId());
            responseDTO.setUsername(result.getUsername());
            responseDTO.setRoleCode(result.getRoleCode());

            AiLog.info(AiLog.auth().loginSuccess(
                    result.getTenantId(),
                    result.getUserId(),
                    result.getUsername(),
                    result.getRoleCode()
            ));
            log.info("用户登录成功 username:{} tenantId:{} userId:{} roleCode:{}",
                    result.getUsername(), result.getTenantId(), result.getUserId(), result.getRoleCode());

            return Response.<LoginResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();
        } catch (AppException e) {
            AiLog.error(AiLog.auth().loginFailed(
                    safeUsername(requestDTO),
                    e.getCode(),
                    e.getInfo()
            ));
            log.error("用户登录失败 username:{}", requestDTO == null ? null : requestDTO.getUsername(), e);
            return Response.<LoginResponseDTO>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            AiLog.error(AiLog.auth().loginFailed(
                    safeUsername(requestDTO),
                    ResponseCode.UN_ERROR.getCode(),
                    ResponseCode.UN_ERROR.getInfo()
            ).error(e));
            log.error("用户登录异常 username:{}", requestDTO == null ? null : requestDTO.getUsername(), e);
            return Response.<LoginResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    /**
     * 刷新令牌；参数是 refreshToken；返回新的访问令牌和刷新令牌。
     */
    @RequestMapping(value = "refresh", method = RequestMethod.POST)
    @Override
    public Response<TokenRefreshResponseDTO> refresh(@RequestBody TokenRefreshRequestDTO requestDTO) {
        try {
            TokenRefreshCommandEntity command = new TokenRefreshCommandEntity();
            command.setRefreshToken(requestDTO == null ? null : requestDTO.getRefreshToken());
            TokenResultEntity result = authService.refresh(command);
            TokenRefreshResponseDTO responseDTO = toTokenRefreshResponse(result);

            AiLog.info(AiLog.auth().refreshSuccess(
                    result.getTenantId(),
                    result.getUserId(),
                    result.getUsername(),
                    result.getRoleCode()
            ));
            log.info("用户令牌续期成功 username:{} tenantId:{} userId:{} roleCode:{}",
                    result.getUsername(), result.getTenantId(), result.getUserId(), result.getRoleCode());

            return success(responseDTO);
        } catch (AppException e) {
            AiLog.error(AiLog.auth().refreshFailed(null, e.getCode(), e.getInfo()));
            log.error("用户令牌续期失败", e);
            return fail(e);
        } catch (Exception e) {
            AiLog.error(AiLog.auth().refreshFailed(null, ResponseCode.UN_ERROR.getCode(), ResponseCode.UN_ERROR.getInfo()).error(e));
            log.error("用户令牌续期异常", e);
            return fail(ResponseCode.UN_ERROR);
        }
    }

    /**
     * 自动登录检查；参数来自请求头令牌；返回当前用户资料。
     */
    @RequestMapping(value = "me", method = RequestMethod.GET)
    @Override
    public Response<UserProfileResponseDTO> me() {
        try {
            UserProfileEntity result = authService.currentUser(TenantContextHolder.getUserId());
            return success(toUserProfileResponse(result));
        } catch (AppException e) {
            return fail(e);
        } catch (Exception e) {
            log.error("查询当前用户异常 userId:{}", TenantContextHolder.getUserId(), e);
            return fail(ResponseCode.UN_ERROR);
        }
    }

    /**
     * 修改密码；参数是旧密码和新密码；返回当前用户资料。
     */
    @RequestMapping(value = "change_password", method = RequestMethod.POST)
    @Override
    public Response<UserProfileResponseDTO> changePassword(@RequestBody UpdatePasswordRequestDTO requestDTO) {
        try {
            ChangePasswordCommandEntity command = new ChangePasswordCommandEntity();
            command.setUserId(TenantContextHolder.getUserId());
            command.setOldPassword(requestDTO == null ? null : requestDTO.getOldPassword());
            command.setNewPassword(requestDTO == null ? null : requestDTO.getNewPassword());
            UserProfileEntity result = authService.changePassword(command);

            AiLog.info(AiLog.auth().passwordChanged(
                    result.getTenantId(),
                    result.getUserId(),
                    result.getUsername(),
                    result.getRoleCode()
            ));
            log.info("用户修改密码成功 username:{} tenantId:{} userId:{} roleCode:{}",
                    result.getUsername(), result.getTenantId(), result.getUserId(), result.getRoleCode());

            return success(toUserProfileResponse(result));
        } catch (AppException e) {
            AiLog.error(AiLog.auth().passwordChangeFailed(null, e.getCode(), e.getInfo()));
            log.error("用户修改密码失败 userId:{}", TenantContextHolder.getUserId(), e);
            return fail(e);
        } catch (Exception e) {
            AiLog.error(AiLog.auth().passwordChangeFailed(null, ResponseCode.UN_ERROR.getCode(), ResponseCode.UN_ERROR.getInfo()).error(e));
            log.error("用户修改密码异常 userId:{}", TenantContextHolder.getUserId(), e);
            return fail(ResponseCode.UN_ERROR);
        }
    }

    /**
     * 修改资料；参数是昵称、邮箱、手机号和头像；返回更新后的用户资料。
     */
    @RequestMapping(value = "profile", method = RequestMethod.POST)
    @Override
    public Response<UserProfileResponseDTO> updateProfile(@RequestBody UpdateProfileRequestDTO requestDTO) {
        try {
            UpdateProfileCommandEntity command = new UpdateProfileCommandEntity();
            command.setUserId(TenantContextHolder.getUserId());
            command.setNickname(requestDTO == null ? null : requestDTO.getNickname());
            command.setEmail(requestDTO == null ? null : requestDTO.getEmail());
            command.setPhone(requestDTO == null ? null : requestDTO.getPhone());
            command.setAvatar(requestDTO == null ? null : requestDTO.getAvatar());
            UserProfileEntity result = authService.updateProfile(command);

            AiLog.info(AiLog.auth().profileUpdated(
                    result.getTenantId(),
                    result.getUserId(),
                    result.getUsername(),
                    result.getRoleCode()
            ));
            log.info("用户修改资料成功 username:{} tenantId:{} userId:{} roleCode:{}",
                    result.getUsername(), result.getTenantId(), result.getUserId(), result.getRoleCode());

            return success(toUserProfileResponse(result));
        } catch (AppException e) {
            AiLog.error(AiLog.auth().profileUpdateFailed(null, e.getCode(), e.getInfo()));
            log.error("用户修改资料失败 userId:{}", TenantContextHolder.getUserId(), e);
            return fail(e);
        } catch (Exception e) {
            AiLog.error(AiLog.auth().profileUpdateFailed(null, ResponseCode.UN_ERROR.getCode(), ResponseCode.UN_ERROR.getInfo()).error(e));
            log.error("用户修改资料异常 userId:{}", TenantContextHolder.getUserId(), e);
            return fail(ResponseCode.UN_ERROR);
        }
    }

    /**
     * 转换令牌响应；参数是领域令牌结果；返回接口响应对象。
     */
    private TokenRefreshResponseDTO toTokenRefreshResponse(TokenResultEntity result) {
        TokenRefreshResponseDTO responseDTO = new TokenRefreshResponseDTO();
        responseDTO.setToken(result.getToken());
        responseDTO.setRefreshToken(result.getRefreshToken());
        responseDTO.setTokenType(result.getTokenType());
        responseDTO.setExpiresIn(result.getExpiresIn());
        responseDTO.setRefreshExpiresIn(result.getRefreshExpiresIn());
        responseDTO.setTenantId(result.getTenantId());
        responseDTO.setUserId(result.getUserId());
        responseDTO.setUsername(result.getUsername());
        responseDTO.setRoleCode(result.getRoleCode());
        return responseDTO;
    }

    /**
     * 转换用户资料响应；参数是领域用户资料；返回接口响应对象。
     */
    private UserProfileResponseDTO toUserProfileResponse(UserProfileEntity result) {
        UserProfileResponseDTO responseDTO = new UserProfileResponseDTO();
        responseDTO.setTenantId(result.getTenantId());
        responseDTO.setUserId(result.getUserId());
        responseDTO.setUsername(result.getUsername());
        responseDTO.setNickname(result.getNickname());
        responseDTO.setEmail(result.getEmail());
        responseDTO.setPhone(result.getPhone());
        responseDTO.setAvatar(result.getAvatar());
        responseDTO.setRoleCode(result.getRoleCode());
        return responseDTO;
    }

    /**
     * 包装成功响应；参数是数据对象；返回统一响应。
     */
    private <T> Response<T> success(T data) {
        return Response.<T>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(data)
                .build();
    }

    /**
     * 包装业务失败响应；参数是业务异常；返回统一响应。
     */
    private <T> Response<T> fail(AppException e) {
        return Response.<T>builder()
                .code(e.getCode())
                .info(e.getInfo())
                .build();
    }

    /**
     * 包装系统失败响应；参数是响应码；返回统一响应。
     */
    private <T> Response<T> fail(ResponseCode responseCode) {
        return Response.<T>builder()
                .code(responseCode.getCode())
                .info(responseCode.getInfo())
                .build();
    }

    /**
     * 安全读取注册用户名；参数是注册请求；返回用户名。
     */
    private String safeUsername(RegisterRequestDTO requestDTO) {
        return requestDTO == null ? null : requestDTO.getUsername();
    }

    /**
     * 安全读取登录用户名；参数是登录请求；返回用户名。
     */
    private String safeUsername(LoginRequestDTO requestDTO) {
        return requestDTO == null ? null : requestDTO.getUsername();
    }
}
