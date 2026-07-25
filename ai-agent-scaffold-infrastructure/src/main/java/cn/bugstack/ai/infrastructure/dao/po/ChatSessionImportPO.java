package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 会话复制导入持久化对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class ChatSessionImportPO extends BasePO {
    /** 本次导入业务 ID。 */
    private String importId;
    /** 来源分享 ID。 */
    private String shareId;
    /** 匿名或登录接收方的稳定去重范围键。 */
    private String recipientScopeKey;
    /** 导入后会话所属租户。 */
    private String tenantId;
    /** 导入后会话所属用户。 */
    private String userId;
    /** 已校验分享文件的 SHA-256。 */
    private String sourceSha256;
    /** 导入生成的新会话 ID。 */
    private String newSessionId;
    /** 导入处理状态。 */
    private String status;
}
