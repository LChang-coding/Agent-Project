package cn.bugstack.ai.domain.agent.model.valobj.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@AllArgsConstructor
@NoArgsConstructor
/** 组合 Agent 类型到装配节点 Bean 的映射。 */
public enum AgentTypeEnum {

    /** 重复执行子 Agent。 */
    Loop("循环执行", "loop", "loopAgentNode"),
    /** 并发执行子 Agent。 */
    Parallel("并行执行", "parallel", "parallelAgentNode"),
    /** 顺序执行子 Agent。 */
    Sequential("串行执行", "sequential", "sequentialAgentNode"),

    ;

    /** 中文展示名。 */
    private String name;
    /** 配置文件类型值。 */
    private String type;
    /** 负责装配的 Spring Bean 名。 */
    private String node;

    /** 忽略大小写解析组合类型，未知类型返回空。 */
    public static AgentTypeEnum formType(String type) {
        if (type == null) {
            return null;
        }

        for (AgentTypeEnum value : values()) {
            if (value.getType().equalsIgnoreCase(type)) {
                return value;
            }
        }

        return null;
    }

}
