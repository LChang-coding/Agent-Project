package cn.bugstack.ai.test.context;

import cn.bugstack.ai.domain.context.model.ContextCompactionCommand;
import cn.bugstack.ai.infrastructure.context.KafkaContextCompactionPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class KafkaContextCompactionPublisherTest {

    @Test
    public void shouldWaitForKafkaAcknowledgement() {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        @SuppressWarnings("unchecked") SendResult<String, String> sendResult = mock(SendResult.class);
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(sendResult));
        KafkaContextCompactionPublisher publisher = new KafkaContextCompactionPublisher(
                kafka, new ObjectMapper(), true, "context-topic", 500L);

        publisher.publish(command("task-1"));

        verify(kafka).send(org.mockito.ArgumentMatchers.eq("context-topic"),
                org.mockito.ArgumentMatchers.eq("tenant:session"), anyString());
    }

    @Test
    public void shouldExposeKafkaSendFailure() {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));
        KafkaContextCompactionPublisher publisher = new KafkaContextCompactionPublisher(
                kafka, new ObjectMapper(), true, "context-topic", 500L);

        try {
            publisher.publish(command("task-1"));
            Assert.fail("Kafka 未确认时不能伪装成发送成功");
        } catch (IllegalStateException exception) {
            Assert.assertEquals("上下文压缩命令未得到 Kafka 确认", exception.getMessage());
        }
    }

    private ContextCompactionCommand command(String taskId) {
        return new ContextCompactionCommand(taskId, "tenant", "user", "session",
                1, 10, 0, "v1", "trace-1");
    }
}
