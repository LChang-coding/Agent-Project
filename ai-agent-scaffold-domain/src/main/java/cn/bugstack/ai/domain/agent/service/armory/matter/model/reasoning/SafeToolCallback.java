package cn.bugstack.ai.domain.agent.service.armory.matter.model.reasoning;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * 把模型生成的非法工具 JSON 转成可反馈给模型的工具结果，避免参数解析异常中断整轮对话。
 */
final class SafeToolCallback implements ToolCallback {

    private static final String INVALID_ARGUMENTS = "{\"success\":false,\"errorCode\":\"TOOL_ARGUMENTS_INVALID\","
            + "\"error\":\"工具参数不是合法 JSON，请严格按照参数 Schema 重新生成后重试\"}";

    private final ToolCallback delegate;

    SafeToolCallback(ToolCallback delegate) {
        this.delegate = delegate;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        try {
            return delegate.call(toolInput);
        } catch (RuntimeException exception) {
            if (isInvalidJson(exception)) return INVALID_ARGUMENTS;
            throw exception;
        }
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        try {
            return delegate.call(toolInput, toolContext);
        } catch (RuntimeException exception) {
            if (isInvalidJson(exception)) return INVALID_ARGUMENTS;
            throw exception;
        }
    }

    private boolean isInvalidJson(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && (message.contains("Conversion from JSON")
                    || message.contains("JsonParseException")
                    || message.contains("Unexpected character"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
