package cn.bugstack.ai.test.config;

import cn.bugstack.ai.config.TraceErrorAttributes;
import cn.bugstack.ai.types.observability.TraceContext;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

import java.util.Map;

/**
 * Spring 默认错误响应链路号测试。
 */
public class TraceErrorAttributesTest {

    @Test
    public void shouldReuseOriginalRequestTraceIdAfterFilterContextCleared() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/missing");
        request.setAttribute(TraceContext.TRACE_ID_REQUEST_ATTRIBUTE, "trace-default-error");

        Map<String, Object> attributes = new TraceErrorAttributes().getErrorAttributes(
                new ServletWebRequest(request), ErrorAttributeOptions.defaults());

        Assert.assertEquals("trace-default-error", attributes.get("traceId"));
    }
}
