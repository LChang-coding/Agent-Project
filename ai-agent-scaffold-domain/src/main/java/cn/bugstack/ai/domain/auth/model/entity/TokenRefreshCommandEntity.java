package cn.bugstack.ai.domain.auth.model.entity;

import lombok.Data;

@Data
public class TokenRefreshCommandEntity {

    private String refreshToken;
}
