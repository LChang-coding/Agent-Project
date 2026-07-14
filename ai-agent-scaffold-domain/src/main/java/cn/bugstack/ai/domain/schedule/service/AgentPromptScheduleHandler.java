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

    @Override
    public String taskType() {
        return "agent_prompt";
    }

    @Override
    public String execute(ScheduleTaskContext context) throws Exception {
        JsonNode payload = objectMapper.readTree(context.config().getTaskPayload());
        String message = payload == null ? null : payload.path("message").asText(null);
        if (message == null || message.isBlank()) {
            throw new AppException("SCHEDULE_PAYLOAD_INVALID", "定时任务消息不能为空");
        }
        List<String> replies = chatService.handleMessage(context.config().getAgentId(),
                context.config().getRunAsUserId(), message);
        return objectMapper.writeValueAsString(replies);
    }
}
