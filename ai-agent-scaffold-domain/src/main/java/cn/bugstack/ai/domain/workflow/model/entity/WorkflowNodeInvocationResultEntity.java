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

    /** 当前 Agent 节点返回并提供给路由判断的文本。 */
    private String output;

    /** 当前节点模型调用实际使用的 RAG 证据。 */
    private List<RagContextEvidence> evidence;
}
