package cn.bugstack.ai.domain.auth.model.entity;

import lombok.Data;

@Data
/** 使用长期凭证轮换双令牌的命令。 */
public class TokenRefreshCommandEntity {

    /** 客户端持有的刷新令牌明文。 */
    private String refreshToken;
}
