package cn.bugstack.ai.api;

import cn.bugstack.ai.api.dto.*;
import cn.bugstack.ai.api.response.Response;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.List;

/**
 * 智能体服务接口
 * 2026/1/20 08:16
 */
public interface IAgentService {

    /**
     * 查询智能体配置；无参数；返回智能体配置列表。
     */
    Response<List<AiAgentConfigResponseDTO>> queryAiAgentConfigList();

    /**
     * 创建会话；参数是 Agent 和用户信息；返回会话ID。
     */
    Response<CreateSessionResponseDTO> createSession(CreateSessionRequestDTO requestDTO);

    /**
     * 发起普通对话；参数是聊天请求；返回会话ID和模型回复。
     */
    Response<ChatResponseDTO> chat(ChatRequestDTO requestDTO);

    /**
     * 发起流式对话；参数是聊天请求；返回 SSE 事件流。
     */
    ResponseBodyEmitter chatStream(ChatRequestDTO requestDTO);

}
