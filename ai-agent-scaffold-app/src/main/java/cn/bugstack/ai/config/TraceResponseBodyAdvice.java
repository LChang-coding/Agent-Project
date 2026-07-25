package cn.bugstack.ai.config;

import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.types.observability.TraceContext;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * 统一向 JSON 业务响应写入当前链路编号。
 */
@RestControllerAdvice
public class TraceResponseBodyAdvice implements ResponseBodyAdvice<Object> {

    /**
     * 判断是否处理响应；统一在写出前检查实际响应类型。
     *
     * @param returnType    控制器返回类型
     * @param converterType 消息转换器类型
     * @return 始终进入统一检查
     */
    @Override
    public boolean supports(MethodParameter returnType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    /**
     * 向统一响应补充 traceId；非业务响应保持原样。
     *
     * @param body                  原响应体
     * @param returnType            控制器返回类型
     * @param selectedContentType   响应媒体类型
     * @param selectedConverterType 消息转换器类型
     * @param request               HTTP 请求
     * @param response              HTTP 响应
     * @return 补充链路编号后的响应体
     */
    @Override
    public Object beforeBodyWrite(Object body,
                                  MethodParameter returnType,
                                  MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request,
                                  ServerHttpResponse response) {
        if (body instanceof Response<?> apiResponse
                && (apiResponse.getTraceId() == null || apiResponse.getTraceId().isBlank())) {
            apiResponse.setTraceId(TraceContext.ensureTraceId());
        }
        return body;
    }
}
