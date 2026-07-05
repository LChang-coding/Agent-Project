package cn.bugstack.ai.api.dto.tool;

import lombok.Data;

/**
 * 工具发布请求。
 */
@Data
public class ToolPublishRequestDTO {

    /**
     * 要发布的版本号。
     */
    private String version;
}
