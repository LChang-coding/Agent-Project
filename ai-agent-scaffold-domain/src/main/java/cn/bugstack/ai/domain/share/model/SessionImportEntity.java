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
    /** 数据库自增主键。 */
    private Long id;
    /** 导入记录业务标识。 */
    private String importId;
    /** 来源分享标识。 */
    private String shareId;
    /** 接收租户与用户的不可逆摘要。 */
    private String recipientScopeKey;
    /** 新会话所属租户。 */
    private String tenantId;
    /** 新会话所属用户。 */
    private String userId;
    /** 导入时验证的源快照摘要。 */
    private String sourceSha256;
    /** 创建出的独立会话标识。 */
    private String newSessionId;
    /** 导入事务状态。 */
    private String status;
}
