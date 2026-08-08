package cn.bugstack.ai.domain.workflow.model.entity;

import cn.bugstack.ai.domain.workflow.model.valobj.WorkflowRouteIntentStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/** 模型已选择但尚未被运行时消费的工作流路由意图。 */
@Data
@Builder
public class WorkflowRouteIntentEntity {

    /** 路由意图所属租户，参与所有查询和唯一约束。 */
    private String tenantId;

    /** 发起当前工作流运行的可信用户。 */
    private String userId;

    /** 路由意图所属的工作流运行。 */
    private String runId;

    /** 本次节点执行标识；同一运行重复访问节点时仍可区分每次执行。 */
    private String nodeExecutionId;

    /** 本次运行使用的工作流定义标识。 */
    private String workflowId;

    /** 本次运行冻结的工作流版本。 */
    private Integer workflowVersion;

    /** 冻结定义摘要，防止意图被应用到已经变更的工作流图。 */
    private String definitionHash;

    /** 产生路由意图的源节点。 */
    private String nodeId;

    /** 模型选择的主路由键。 */
    private String routeKey;

    /** 经过统一规则标准化的路由键，用于精确比较和唯一性判断。 */
    private String normalizedRouteKey;

    /** 服务端根据路由键解析出的工作流边。 */
    private String resolvedEdgeId;

    /** 服务端根据工作流边解析出的目标节点。 */
    private String resolvedTargetNodeId;

    /** 模型提交的选择理由，只用于展示和审计，不参与路由匹配。 */
    private String reason;

    /** 模型函数调用标识，用于相同工具调用的幂等重放。 */
    private String functionCallId;

    /** 意图来源，例如正常模型工具调用或修复调用。 */
    private String source;

    /** 意图当前处于待消费还是已消费状态。 */
    private WorkflowRouteIntentStatus status;

    /** 与工作流根运行一致的跟踪标识。 */
    private String traceId;

    /** 运行时成功消费该意图的时间；待消费时为空。 */
    private LocalDateTime consumedAt;
}
