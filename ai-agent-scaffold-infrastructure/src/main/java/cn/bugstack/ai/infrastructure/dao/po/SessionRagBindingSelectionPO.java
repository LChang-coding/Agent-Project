package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 会话RAG手动绑定选择持久化对象。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class SessionRagBindingSelectionPO extends BasePO {
    /** 选择所属租户。 */
    private String tenantId;
    /** 选择所属用户。 */
    private String userId;
    /** 选择所属会话。 */
    private String sessionId;
    /** 会话实际运行目标类型。 */
    private String targetType;
    /** 会话实际运行目标 ID。 */
    private String targetId;
    /** 用户手动选中的绑定 ID。 */
    private String bindingId;
    /** 多绑定检索与展示顺序。 */
    private Integer selectionOrder;
}
