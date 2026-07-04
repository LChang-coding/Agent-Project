package cn.bugstack.ai.domain.auth.model.entity;

import lombok.Data;

@Data
public class LoginCommandEntity {

    private String username;

    private String password;
}
