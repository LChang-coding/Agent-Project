package cn.bugstack.ai.domain.auth.model.entity;

import lombok.Data;

@Data
public class RegisterCommandEntity {

    private String tenantName;

    private String username;

    private String password;

    private String nickname;

    private String email;

    private String phone;
}
