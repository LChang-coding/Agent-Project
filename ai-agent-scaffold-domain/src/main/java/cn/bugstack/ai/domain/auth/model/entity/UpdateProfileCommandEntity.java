package cn.bugstack.ai.domain.auth.model.entity;

import lombok.Data;

@Data
public class UpdateProfileCommandEntity {

    private String userId;

    private String nickname;

    private String email;

    private String phone;

    private String avatar;
}
