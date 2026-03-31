package cn.bugstack.ai.domain.agent.model.entity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 装配命令
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/12/17 08:15
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ArmoryCommandEntity {
        //装配指令实体
    private AiAgentConfigTableVO aiAgentConfigTableVO;

}
