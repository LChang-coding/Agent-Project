package cn.bugstack.ai.domain.agent.model.entity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 装配命令：把「要装配哪一张 Agent 配置表」这件事包成一个对象，在装配链的各个节点之间传递。
 *
 * <p>所属层次：领域层的实体（命令实体），没有任何行为，只承载入参。</p>
 *
 * <p>谁会调用它：{@code ArmoryService} 在遍历配置表时为每张表构造一个实例，
 * 然后交给 {@code RootNode} 起头的装配责任链，链上每个节点都从它里面取自己关心的那一段配置。</p>
 *
 * <p>它不负责什么：不校验配置是否完整、不创建任何 Bean、不落库。校验和装配都在各个节点里做。</p>
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ArmoryCommandEntity {
    /**
     * 本次要装配的整棵配置树，里面依次包含 API 端点、聊天模型、MCP/Skill 工具、原子 Agent、
     * 组合工作流和 Runner 的配置。
     *
     * <p>装配链的每个节点都只读它、不改它；节点产出的 Bean 通过 Spring 容器注册，不回写到这里。
     * 为空会导致后续节点取不到配置而装配失败，该 Agent 在对话时就查不到运行时句柄。</p>
     */
    private AiAgentConfigTableVO aiAgentConfigTableVO;
}
