package cn.bugstack.ai.domain.agent.model.entity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 将一张完整 Agent 配置表交给装配链。 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ArmoryCommandEntity {
    /** 待装配的 API、模型、工具、Agent、工作流和 Runner 配置。 */
    private AiAgentConfigTableVO aiAgentConfigTableVO;
}
