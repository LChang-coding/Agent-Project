package cn.bugstack.ai.config;

import cn.bugstack.ai.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Configuration;

import javax.annotation.Resource;

@Slf4j
@Configuration
@EnableConfigurationProperties(AiAgentAutoConfigProperties.class)//这个注解是为了让Spring Boot能够扫描到AiAgentAutoConfigProperties这个配置类，并将其注册为一个Bean，这样我们就可以在AiAgentAutoConfig中通过@Resource注解来注入它。
public class AiAgentAutoConfig implements ApplicationListener<ApplicationReadyEvent> {
//容器启动时就记载配置文件中的智能体配置表，进行装配
    @Resource
    private AiAgentAutoConfigProperties aiAgentAutoConfigProperties;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        try {
            log.info("Ai Agent 智能体装配 {}", JSON.toJSONString(aiAgentAutoConfigProperties.getTables().values()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}
