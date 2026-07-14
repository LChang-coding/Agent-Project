package cn.bugstack.ai.domain.share.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 会话导入幂等实体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionImportEntity {
    private Long id;
    private String importId;
    private String shareId;
    private String recipientScopeKey;
    private String tenantId;
    private String userId;
    private String sourceSha256;
    private String newSessionId;
    private String status;
}
