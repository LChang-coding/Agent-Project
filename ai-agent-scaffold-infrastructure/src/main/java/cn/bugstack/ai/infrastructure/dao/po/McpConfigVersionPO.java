package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * MCP 配置版本持久化对象。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class McpConfigVersionPO extends BasePO {

    /**
     * 租户业务ID，个体户可为空
     */
    private String tenantId;

    /**
     * 版本发布用户ID
     */
    private String ownerUserId;

    /**
     * MCP 配置业务ID
     */
    private String mcpId;

    /**
     * MCP 版本业务ID
     */
    private String versionId;

    /**
     * 版本号
     */
    private String version;

    /**
     * 传输类型：sse/http/stdio/local
     */
    private String transportType;

    /**
     * 远程 MCP 地址
     */
    private String endpoint;

    /**
     * stdio 启动命令
     */
    private String command;

    /**
     * stdio 启动参数
     */
    private String args;

    /**
     * 运行环境变量或 user_secret 引用
     */
    private String env;

    /**
     * 最近一次测试得到的工具 Schema
     */
    private String toolSchemaJson;

    /**
     * 测试状态：untested/success/failed
     */
    private String testStatus;

    /**
     * 测试结果说明
     */
    private String testMessage;

    /**
     * 版本状态：draft/active/disabled/archived
     */
    private String status;

    /**
     * 扩展信息
     */
    private String metadata;
}
