package cn.bugstack.ai.test.agent;

import cn.bugstack.ai.trigger.http.AgentServiceController;
import cn.bugstack.ai.types.exception.AppException;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * 流式错误响应测试。
 */
public class AgentServiceControllerSseTest {

    @Test
    public void shouldSendBusinessFailureAsSseEventAndCompleteNormally() {
        AgentServiceController controller = new AgentServiceController();
        CapturingEmitter emitter = new CapturingEmitter();

        ReflectionTestUtils.invokeMethod(controller, "completeSseWithError", emitter,
                new AppException("ASSET_BIND_DENIED", "附件已用于其他消息"));

        Assert.assertTrue(emitter.sent);
        Assert.assertTrue(emitter.completed);
        Assert.assertFalse(emitter.completedWithError);
    }

    private static class CapturingEmitter extends SseEmitter {
        private boolean sent;
        private boolean completed;
        private boolean completedWithError;

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            sent = true;
        }

        @Override
        public void complete() {
            completed = true;
        }

        @Override
        public void completeWithError(Throwable ex) {
            completedWithError = true;
        }
    }
}
