package cn.bugstack.ai;

import cn.bugstack.ai.types.exception.AppException;
import io.reactivex.rxjava3.exceptions.UndeliverableException;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ApplicationReactiveConfigTest {
    private final String previous = System.getProperty("rx3.buffer-size");

    @After
    public void restore() {
        if (previous == null) System.clearProperty("rx3.buffer-size");
        else System.setProperty("rx3.buffer-size", previous);
    }

    @Test
    public void shouldUseBoundedDefaultAndRespectExplicitOverride() {
        System.clearProperty("rx3.buffer-size");
        Application.configureRxJavaPrefetch();
        assertEquals("4", System.getProperty("rx3.buffer-size"));

        System.setProperty("rx3.buffer-size", "8");
        Application.configureRxJavaPrefetch();
        assertEquals("8", System.getProperty("rx3.buffer-size"));
    }

    @Test
    public void shouldIgnoreOnlyCancelledRunErrorsFromDisposedStreams() {
        assertTrue(Application.isExpectedCancelledStreamError(new UndeliverableException(
                new AppException("RUN_NOT_EXECUTABLE", "运行已结束"))));
        assertFalse(Application.isExpectedCancelledStreamError(new UndeliverableException(
                new AppException("MODEL_FAILED", "模型失败"))));
        assertFalse(Application.isExpectedCancelledStreamError(
                new AppException("RUN_NOT_EXECUTABLE", "运行已结束")));
    }
}
