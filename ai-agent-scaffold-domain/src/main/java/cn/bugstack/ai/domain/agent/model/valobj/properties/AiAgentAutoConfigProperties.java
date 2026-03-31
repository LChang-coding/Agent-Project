package cn.bugstack.ai.domain.agent.model.valobj.properties;

import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

@Data
@ConfigurationProperties(prefix = "ai.agent.config", ignoreInvalidFields = true)
//这个注解表示这个类是一个配置属性类，前缀为"ai.agent.config"，并且在绑定属性时忽略无效字段。
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
