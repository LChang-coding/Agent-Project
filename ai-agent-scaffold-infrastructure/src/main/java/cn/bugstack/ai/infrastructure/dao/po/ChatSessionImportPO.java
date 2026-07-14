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
    private String importId;
    private String shareId;
    private String recipientScopeKey;
    private String tenantId;
    private String userId;
    private String sourceSha256;
    private String newSessionId;
    private String status;
}
