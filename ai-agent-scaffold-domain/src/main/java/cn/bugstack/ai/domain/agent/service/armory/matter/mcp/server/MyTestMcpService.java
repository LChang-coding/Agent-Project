package cn.bugstack.ai.domain.agent.service.armory.matter.mcp.server;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

/** 本地 MCP 接线测试工具；只验证结构化入参与返回值映射。 */
@Slf4j
@Service
public class MyTestMcpService {

    /** 将必填英文单词转为大写，不访问外部系统。 */
    @Tool(description = "小写字母转换为大写字母")
    public XxxResponse toUpperCase(XxxRequest request) {
        XxxResponse xxxResponse = new XxxResponse();
        xxxResponse.setContent(request.getWord().toUpperCase());
        return xxxResponse;
    }

    /** MCP 根据注解生成必填字符串参数 schema。 */
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class XxxRequest {
        @JsonProperty(required = true, value = "word")
        @JsonPropertyDescription("英文单词，字符串，字母。例如: good,xiaofuge")
        private String word;
    }

    /** MCP 返回 schema 只包含转换结果。 */
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class XxxResponse {
        @JsonProperty(required = true, value = "content")
        @JsonPropertyDescription("单词转换结果")
        private String content;
    }

}
