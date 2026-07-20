package cn.bugstack.ai.test.agent;

import cn.bugstack.ai.trigger.http.AgentServiceController;
import cn.bugstack.ai.domain.run.service.ActiveRunRegistry;
import cn.bugstack.ai.types.exception.AppException;
import cn.bugstack.ai.domain.rag.model.entity.RagAnswerCitationValidation;
import cn.bugstack.ai.domain.rag.service.RagAnswerCitationMetadataService;
import org.mockito.Mockito;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import io.reactivex.rxjava3.disposables.Disposable;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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

    @Test
    public void shouldSendCitationTerminalEventAfterPersistedAnswerIsReadable() {
        AgentServiceController controller = new AgentServiceController();
        RagAnswerCitationMetadataService service = Mockito.mock(RagAnswerCitationMetadataService.class);
        RagAnswerCitationValidation validation = new RagAnswerCitationValidation(
                RagAnswerCitationValidation.Status.NO_RAG, List.of(), List.of(), List.of(), List.of(), List.of());
        Mockito.when(service.queryRunAnswer("tenant-1", "user-1", "session-1", "run-1"))
                .thenReturn(new RagAnswerCitationMetadataService.AnswerSnapshot("msg-1", validation));
        ReflectionTestUtils.setField(controller, "citationMetadataService", service);
        CapturingEmitter emitter = new CapturingEmitter();

        ReflectionTestUtils.invokeMethod(controller, "completeSseWithCitation", emitter,
                "tenant-1", "user-1", "session-1", "run-1");

        Assert.assertTrue(emitter.sent);
        Assert.assertTrue(emitter.completed);
        Assert.assertFalse(emitter.completedWithError);
        Mockito.verify(service).queryRunAnswer("tenant-1", "user-1", "session-1", "run-1");
    }

    @Test
    public void shouldMergeCumulativeAgentEventsWithoutRepeatingPrefixes() {
        AgentServiceController controller = new AgentServiceController();

        String content = ReflectionTestUtils.invokeMethod(controller, "mergeAgentContents",
                List.of("N", "NOT", "NOT_IN", "NOT_IN_DOCUMENT", "NOT_IN_DOCUMENT"));

        Assert.assertEquals("NOT_IN_DOCUMENT", content);
    }

    @Test
    public void shouldCompleteEmitterAndDisposeLateSubscriptionWhenRunIsCancelled() {
        AgentServiceController controller = new AgentServiceController();
        ActiveRunRegistry registry = new ActiveRunRegistry();
        ReflectionTestUtils.setField(controller, "activeRunRegistry", registry);
        CapturingEmitter emitter = new CapturingEmitter();
        AtomicReference<Disposable> disposableRef = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean(false);

        ReflectionTestUtils.invokeMethod(controller, "registerActiveStream", "run-race", emitter,
                disposableRef, interrupted);
        Assert.assertTrue(registry.interrupt("run-race"));
        Disposable disposable = Disposable.empty();
        ReflectionTestUtils.invokeMethod(controller, "attachDisposable", disposableRef, interrupted, disposable);

        Assert.assertTrue(interrupted.get());
        Assert.assertTrue(disposable.isDisposed());
        Assert.assertTrue(emitter.completed);
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
