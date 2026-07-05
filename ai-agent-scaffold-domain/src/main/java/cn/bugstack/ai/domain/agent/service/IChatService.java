package cn.bugstack.ai.domain.agent.service;

import cn.bugstack.ai.domain.agent.model.entity.ChatCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.google.adk.events.Event;
import io.reactivex.rxjava3.core.Flowable;

import java.util.List;

/**
 * 对话接口
 *
 * 2025/12/17 08:13
 */
public interface IChatService {

    /**
     * 查询 Agent 配置；无参数；返回当前可用 Agent 列表。
     */
    List<AiAgentConfigTableVO.Agent> queryAiAgentConfigList();

    /**
     * 创建会话；参数是 Agent ID 和用户ID；返回平台会话ID。
     */
    String createSession(String agentId, String userId);

    /**
     * 创建工作流会话；参数是工作流、版本、模型和用户ID；返回平台会话ID。
     */
    String createWorkflowSession(String workflowId, Integer workflowVersion, String modelCode, String userId);

    /**
     * 发送消息；参数是 Agent ID、用户ID和消息；返回模型回复列表。
     */
    List<String> handleMessage(String agentId, String userId, String message);

    /**
     * 发送消息；参数是 Agent ID、用户ID、会话ID和消息；返回模型回复列表。
     */
    List<String> handleMessage(String agentId, String userId, String sessionId, String message);

    /**
     * 发送工作流消息；参数是工作流、版本、模型、用户、会话和消息；返回模型回复列表。
     */
    List<String> handleWorkflowMessage(String workflowId, Integer workflowVersion, String modelCode, String userId, String sessionId, String message);

    /**
     * 流式发送消息；参数是 Agent ID、用户ID、会话ID和消息；返回事件流。
     */
    Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message);

    /**
     * 流式发送工作流消息；参数是工作流、版本、模型、用户、会话和消息；返回事件流。
     */
    Flowable<Event> handleWorkflowMessageStream(String workflowId, Integer workflowVersion, String modelCode, String userId, String sessionId, String message);

    /**
     * 流式发送工作流最终文本；参数是工作流、版本、模型、用户、会话和消息；返回最终输出文本流。
     */
    Flowable<String> handleWorkflowMessageTextStream(String workflowId, Integer workflowVersion, String modelCode, String userId, String sessionId, String message);

    /**
     * 发送复合消息；参数是聊天命令；返回模型回复列表。
     */
    List<String> handleMessage(ChatCommandEntity chatCommandEntity);

}
