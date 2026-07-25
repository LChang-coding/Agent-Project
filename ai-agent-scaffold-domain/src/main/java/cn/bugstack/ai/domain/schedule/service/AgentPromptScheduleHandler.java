package cn.bugstack.ai.domain.schedule.service;

import cn.bugstack.ai.domain.agent.service.IChatService;
import cn.bugstack.ai.types.exception.AppException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 以配置所有者的固化身份执行一次 Agent 对话。
 */
@Component
@RequiredArgsConstructor
public class AgentPromptScheduleHandler implements ScheduleTaskHandler {

    private final IChatService chatService;
    private final ObjectMapper objectMapper;

    /** 返回持久化配置使用的处理器路由键。 */
    @Override
    public String taskType() {
        return "agent_prompt";
    }

    /** 校验固化载荷后，以配置中的执行身份发起一次 Agent 对话。 */
    @Override
    public String execute(ScheduleTaskContext context) throws Exception {
        // 调度配置是持久化输入，执行前仍须拒绝缺失消息的历史或异常数据。
        JsonNode payload = objectMapper.readTree(context.config().getTaskPayload());
        String message = payload == null ? null : payload.path("message").asText(null);
        if (message == null || message.isBlank()) {
            throw new AppException("SCHEDULE_PAYLOAD_INVALID", "定时任务消息不能为空");
        }
        List<String> replies = chatService.handleMessage(context.config().getAgentId(),
                context.config().getRunAsUserId(), message);
        // 统一序列化完整回复，便于执行记录审计而不依赖日志拼接。
        return objectMapper.writeValueAsString(replies);
    }
}
