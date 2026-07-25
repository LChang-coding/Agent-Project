package cn.bugstack.ai.test.config;

import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.config.TraceIdFilter;
import cn.bugstack.ai.config.TraceResponseBodyAdvice;
import cn.bugstack.ai.types.observability.TraceContext;
import org.junit.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 响应头与 JSON 响应体链路号一致性测试。
 */
public class TraceResponseIntegrationTest {

    @Test
    public void shouldExposeSameTraceIdInHeaderAndJsonBody() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new TraceResponseBodyAdvice())
                .addFilters(new TraceIdFilter())
                .build();

        mockMvc.perform(get("/trace-probe")
                        .header(TraceContext.TRACE_ID_HEADER, "trace-http-contract")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(header().string(TraceContext.TRACE_ID_HEADER, "trace-http-contract"))
                .andExpect(header().string("Access-Control-Expose-Headers", TraceContext.TRACE_ID_HEADER))
                .andExpect(jsonPath("$.traceId").value("trace-http-contract"))
                .andExpect(jsonPath("$.data").value("ok"));
    }

    @RestController
    private static class ProbeController {

        @GetMapping("/trace-probe")
        public Response<String> probe() {
            return Response.<String>builder().code("0000").info("成功").data("ok").build();
        }
    }
}
