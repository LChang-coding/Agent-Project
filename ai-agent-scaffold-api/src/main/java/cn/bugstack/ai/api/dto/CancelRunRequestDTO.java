package cn.bugstack.ai.api.dto;

import lombok.Data;

/**
 * 取消运行请求。
 */
@Data
public class CancelRunRequestDTO {

    private String reason;
}
