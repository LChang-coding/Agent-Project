package cn.bugstack.ai.api.dto;

import lombok.Data;

@Data
public class UpdateProfileRequestDTO {

    private String nickname;

    private String email;

    private String phone;

    private String avatar;
}
