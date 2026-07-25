package cn.bugstack.ai.domain.agent.model.valobj.properties;

import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

@Data
@ConfigurationProperties(prefix = "ai.agent.config", ignoreInvalidFields = true)
/** 绑定 `ai.agent.config` 下的自动装配开关和配置表。 */
public class AiAgentAutoConfigProperties {

    /** 是否在启动阶段装配 Agent。 */
    private boolean enabled = false;

    /** 配置表名称到完整 Agent 配置的映射。 */
    private Map<String, AiAgentConfigTableVO> tables;

}
