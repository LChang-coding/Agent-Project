package cn.bugstack.ai.domain.workflow.model.entity;

import cn.bugstack.ai.domain.context.model.RagContextEvidence;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 智能运行时调用一个已编译 Agent 节点后的公开结果。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowNodeInvocationResultEntity {
    private String output;
    private List<RagContextEvidence> evidence;
}
