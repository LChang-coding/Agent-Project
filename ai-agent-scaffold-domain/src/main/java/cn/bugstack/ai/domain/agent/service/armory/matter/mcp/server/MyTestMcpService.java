package cn.bugstack.ai.domain.agent.service.armory.matter.mcp.server;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

/**
 * 一个用来验证「工具接线是否通」的最小示例工具。
 *
 * <p>解决什么问题：排查工具链路问题时，需要一个绝对不会失败、也不依赖外部系统的工具，
 * 用来确认模型能不能正确识别参数结构、能不能拿到返回值。这就是它的全部用途。</p>
 *
 * <p>所属层次：领域层的装配辅料（本地工具实现）。</p>
 *
 * <p>谁会调用它：模型在对话中决定调用工具时，由工具网关转发进来。</p>
 *
 * <p>它不负责什么：没有任何业务价值，不访问数据库、不调外部接口、不做权限判断。
 * 生产配置里不应该把它挂给用户可见的 Agent。</p>
 */
@Slf4j
@Service
public class MyTestMcpService {

    /**
     * 把传入的英文单词转成大写并返回。
     *
     * <p>纯内存计算，不访问任何外部系统，因此可以安全地反复调用。
     * 注解里的描述文本是给模型看的——模型靠它判断什么时候该调用这个工具。</p>
     *
     * <p>入参 word 为空时会抛空指针；这里刻意不做兜底，因为参数是必填的，
     * 模型没按 schema 传参本身就是需要暴露的问题。</p>
     */
    @Tool(description = "小写字母转换为大写字母")
    public XxxResponse toUpperCase(XxxRequest request) {
        // 新建返回对象；工具的返回值会被序列化成 JSON 交给模型。
        XxxResponse xxxResponse = new XxxResponse();
        // 把入参单词转成大写写进返回体，这是这个工具唯一的实际动作。
        xxxResponse.setContent(request.getWord().toUpperCase());
        // 返回结构化结果，模型据此继续推理。
        return xxxResponse;
    }

    /**
     * 工具的入参结构，注解会被转换成模型可见的参数 schema。
     *
     * <p>字段上的必填标记和描述直接影响模型传参的准确度：描述写得含糊，模型就容易传错。</p>
     */
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class XxxRequest {
        /** 要转换的英文单词，必填；模型按注解里的示例决定传什么，为空会导致工具执行抛空指针。 */
        @JsonProperty(required = true, value = "word")
        @JsonPropertyDescription("英文单词，字符串，字母。例如: good,xiaofuge")
        private String word;
    }

    /**
     * 工具的返回结构，注解同样会转换成模型可见的 schema。
     *
     * <p>只暴露转换结果一个字段，模型读到它之后继续组织回答。</p>
     */
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class XxxResponse {
        /** 转换后的大写结果，是模型能读到的唯一输出字段。 */
        @JsonProperty(required = true, value = "content")
        @JsonPropertyDescription("单词转换结果")
        private String content;
    }

}
