package cn.bugstack.ai.api.dto;

import lombok.Data;

@Data
public class RegisterRequestDTO {

    private String tenantName;

    private String username;

    private String password;

    private String nickname;

    private String email;

    private String phone;
}
