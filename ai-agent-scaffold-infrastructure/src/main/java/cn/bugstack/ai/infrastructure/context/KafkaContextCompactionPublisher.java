package cn.bugstack.ai.infrastructure.context;

import cn.bugstack.ai.domain.context.adapter.port.ContextCompactionPublisher;
import cn.bugstack.ai.domain.context.model.ContextCompactionCommand;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Kafka 上下文压缩命令发布器。
 * <p>消息只负责唤醒消费者；压缩任务表才是状态与幂等事实源。</p>
 */
@Component
public class KafkaContextCompactionPublisher implements ContextCompactionPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    /** 关闭时保留数据库任务，由扫描补偿链路接管。 */
    private final boolean enabled;
    /** 版本化主题承载可演进的压缩命令协议。 */
    private final String topic;

    /**
     * 创建 Kafka 发布器；参数是 Kafka 模板、JSON 工具和功能开关；返回发布器实例。
     */
    public KafkaContextCompactionPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                           ObjectMapper objectMapper,
                                           @Value("${ai.context.kafka.enabled:false}") boolean enabled,
                                           @Value("${ai.context.kafka.topic:context.compaction.request.v1}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.topic = topic;
    }

    /**
     * 发布压缩命令；参数是任务命令；功能关闭时不投递。
     */
    @Override
    public void publish(ContextCompactionCommand command) {
        if (!enabled || command == null || command.taskId() == null || command.taskId().isBlank()) {
            return;
        }
        try {
            // 同租户同会话使用稳定分区键，避免同会话压缩命令乱序。
            String key = String.join(":", blank(command.tenantId()), blank(command.sessionId()));
            kafkaTemplate.send(topic, key, objectMapper.writeValueAsString(command));
        } catch (JsonProcessingException e) {
            // 序列化失败表示命令无法投递，必须显式失败而非制造假成功。
            throw new IllegalStateException("上下文压缩命令序列化失败", e);
        }
    }

    /** 将空分区键片段规范化为空串。 */
    private String blank(String value) {
        return value == null ? "" : value;
    }
}
