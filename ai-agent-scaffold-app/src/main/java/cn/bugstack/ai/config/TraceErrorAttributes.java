package cn.bugstack.ai.config;

import cn.bugstack.ai.types.observability.TraceContext;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.servlet.error.DefaultErrorAttributes;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.WebRequest;

import java.util.Map;

/**
 * 为 Spring 默认错误响应补充原请求链路编号。
 */
@Component
public class TraceErrorAttributes extends DefaultErrorAttributes {

    /**
     * 获取默认错误字段并补充 traceId。
     *
     * @param webRequest 请求上下文
     * @param options    错误字段选项
     * @return 包含链路编号的错误字段
     */
    @Override
    public Map<String, Object> getErrorAttributes(WebRequest webRequest, ErrorAttributeOptions options) {
        Map<String, Object> attributes = super.getErrorAttributes(webRequest, options);
        Object requestTraceId = webRequest.getAttribute(
                TraceContext.TRACE_ID_REQUEST_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        String traceId = requestTraceId == null ? TraceContext.currentOrNewTraceId() : String.valueOf(requestTraceId);
        attributes.put("traceId", traceId);
        return attributes;
    }
}
