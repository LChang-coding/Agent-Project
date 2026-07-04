package cn.bugstack.ai.domain.auth.model.entity;

import lombok.Data;

@Data
public class ChangePasswordCommandEntity {

    private String userId;

    private String oldPassword;

    private String newPassword;
}
