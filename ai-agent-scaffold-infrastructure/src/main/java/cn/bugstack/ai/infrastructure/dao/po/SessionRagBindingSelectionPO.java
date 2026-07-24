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
    private String tenantId;
    private String userId;
    private String sessionId;
    private String targetType;
    private String targetId;
    private String bindingId;
    private Integer selectionOrder;
}
