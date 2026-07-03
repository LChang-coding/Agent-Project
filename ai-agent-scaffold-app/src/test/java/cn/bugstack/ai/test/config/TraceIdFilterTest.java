package cn.bugstack.ai.test.config;

import cn.bugstack.ai.config.TraceIdFilter;
import cn.bugstack.ai.types.observability.TraceContext;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

public class TraceIdFilterTest {

    @Test
    public void shouldReuseIncomingTraceIdAndClearContextAfterRequest() throws Exception {
        TraceIdFilter filter = new TraceIdFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/chat");
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader(TraceContext.TRACE_ID_HEADER, "trace-from-client");
        String[] traceIdInRequest = new String[1];

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                traceIdInRequest[0] = TraceContext.getTraceId());

        Assert.assertEquals("trace-from-client", traceIdInRequest[0]);
        Assert.assertEquals("trace-from-client", response.getHeader(TraceContext.TRACE_ID_HEADER));
        Assert.assertNull(TraceContext.getTraceId());
    }

    @Test
    public void shouldCreateTraceIdWhenHeaderMissing() throws Exception {
        TraceIdFilter filter = new TraceIdFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/chat");
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] traceIdInRequest = new String[1];

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                traceIdInRequest[0] = TraceContext.getTraceId());

        Assert.assertNotNull(response.getHeader(TraceContext.TRACE_ID_HEADER));
        Assert.assertEquals(response.getHeader(TraceContext.TRACE_ID_HEADER), traceIdInRequest[0]);
        Assert.assertNull(TraceContext.getTraceId());
    }
}
