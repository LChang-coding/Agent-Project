package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.api.IAuthApiService;
import cn.bugstack.ai.api.dto.LoginRequestDTO;
import cn.bugstack.ai.api.dto.LoginResponseDTO;
import cn.bugstack.ai.api.dto.RegisterRequestDTO;
import cn.bugstack.ai.api.dto.RegisterResponseDTO;
import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.domain.auth.model.entity.LoginCommandEntity;
import cn.bugstack.ai.domain.auth.model.entity.LoginResultEntity;
import cn.bugstack.ai.domain.auth.model.entity.RegisterCommandEntity;
import cn.bugstack.ai.domain.auth.model.entity.RegisterResultEntity;
import cn.bugstack.ai.domain.auth.service.IAuthService;
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
            responseDTO.setTokenType(result.getTokenType());
            responseDTO.setExpiresIn(result.getExpiresIn());
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

    private String safeUsername(RegisterRequestDTO requestDTO) {
        return requestDTO == null ? null : requestDTO.getUsername();
    }

    private String safeUsername(LoginRequestDTO requestDTO) {
        return requestDTO == null ? null : requestDTO.getUsername();
    }
}
