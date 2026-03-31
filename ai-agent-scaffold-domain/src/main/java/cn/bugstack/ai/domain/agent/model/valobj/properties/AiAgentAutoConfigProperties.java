package cn.bugstack.ai.domain.agent.model.valobj.properties;

import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

@Data
@ConfigurationProperties(prefix = "ai.agent.config", ignoreInvalidFields = true)
/**
 * Ai Agent自动装配配置属性类
 *
 */
public class AiAgentAutoConfigProperties {

    /**
     * 是否启用AI Agent自动装配
     */
    private boolean enabled = false;

    private Map<String, AiAgentConfigTableVO> tables;

}
