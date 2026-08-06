package cn.bugstack.ai.test.tool;

import cn.bugstack.ai.types.exception.AppException;
import cn.bugstack.ai.types.observability.AiLog;
import org.junit.Assert;
import org.junit.Test;

import java.util.Map;

/** 平台工具业务异常的日志契约测试。 */
public class ToolFailureObservabilityTest {

    @Test
    public void shouldExposeBusinessCodeAndMessageInToolFailureLog() {
        AppException exception = new AppException("RAG_TOOL_INVALID_REQUEST", "maxContextTokens不合法");

        Map<String, Object> fields = AiLog.tool().callFailed("tenant", "user", "session", "run",
                "platform", "rag_retrieve", "rag_retrieve", "trace", 1L, exception).fields();

        Assert.assertEquals("RAG_TOOL_INVALID_REQUEST", fields.get("errorCode"));
        Assert.assertEquals("maxContextTokens不合法", fields.get("errorMessage"));
    }
}
