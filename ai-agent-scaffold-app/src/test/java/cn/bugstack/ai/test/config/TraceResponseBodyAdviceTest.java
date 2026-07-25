package cn.bugstack.ai.test.config;

import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.config.TraceResponseBodyAdvice;
import cn.bugstack.ai.types.observability.TraceContext;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

/**
 * 统一响应链路号注入测试。
 */
public class TraceResponseBodyAdviceTest {

    @After
    public void clearTraceContext() {
        TraceContext.clear();
    }

    @Test
    public void shouldInjectCurrentTraceIdIntoApiResponse() {
        TraceContext.setTraceId("trace-json-response");
        Response<String> response = Response.<String>builder().code("0000").info("成功").data("ok").build();

        Object actual = new TraceResponseBodyAdvice().beforeBodyWrite(response, null, null, null, null, null);

        Assert.assertSame(response, actual);
        Assert.assertEquals("trace-json-response", response.getTraceId());
    }

    @Test
    public void shouldPreserveExplicitTraceId() {
        TraceContext.setTraceId("trace-current");
        Response<String> response = Response.<String>builder()
                .code("0000").info("成功").traceId("trace-explicit").data("ok").build();

        new TraceResponseBodyAdvice().beforeBodyWrite(response, null, null, null, null, null);

        Assert.assertEquals("trace-explicit", response.getTraceId());
    }
}
