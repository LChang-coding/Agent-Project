package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** MCP 定义、版本指针和最近连接测试状态。 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class McpServerConfigPO extends BasePO {

    /**
     * 租户业务ID，个体户可为空
     */
    private String tenantId;

    /**
     * MCP 拥有者用户ID
     */
    private String ownerUserId;

    /**
     * 可见范围：private/tenant_public
     */
    private String visibility;

    /**
     * MCP 配置业务ID
     */
    private String mcpId;

    /**
     * MCP 名称
     */
    private String mcpName;

    /**
     * 传输类型：stdio/sse/http
     */
    private String transportType;

    /**
     * 远程 MCP 地址，stdio 类型可为空
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
     * 运行环境变量，敏感值应加密或引用 user_secret
     */
    private String env;

    /**
     * MCP 描述
     */
    private String description;

    /**
     * 当前草稿版本号
     */
    private String currentVersion;

    /**
     * 当前发布版本号
     */
    private String publishedVersion;

    /**
     * 当前生效版本业务ID
     */
    private String activeVersionId;

    /**
     * 测试状态：untested/success/failed
     */
    private String testStatus;

    /**
     * 最近一次测试结果说明
     */
    private String testMessage;

    /**
     * 最近一次测试时间
     */
    private LocalDateTime lastTestTime;

    /**
     * MCP 状态：draft/active/disabled/archived/pending_review
     */
    private String status;

    /**
     * 扩展信息
     */
    private String metadata;
}
