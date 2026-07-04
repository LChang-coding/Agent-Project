package cn.bugstack.ai.api;

import cn.bugstack.ai.api.dto.LoginRequestDTO;
import cn.bugstack.ai.api.dto.LoginResponseDTO;
import cn.bugstack.ai.api.dto.RegisterRequestDTO;
import cn.bugstack.ai.api.dto.RegisterResponseDTO;
import cn.bugstack.ai.api.response.Response;

/**
 * 认证服务接口。
 */
public interface IAuthApiService {

    Response<RegisterResponseDTO> register(RegisterRequestDTO requestDTO);

    Response<LoginResponseDTO> login(LoginRequestDTO requestDTO);
}
