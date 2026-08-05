package cn.bugstack.ai.domain.agent.service;

import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;

import java.util.List;

/**
 * 装配服务：把静态配置表变成进程内可以直接执行的 Agent、工作流和 Runner。
 *
 * <p>解决什么问题：配置文件里写的只是文字描述，模型客户端、MCP 工具连接、Agent 实例都得真实创建出来。
 * 这件事很重（要连网络、要拉子进程、要读文件），所以集中在启动或热更新时做一次，之后对话直接复用。</p>
 *
 * <p>所属层次：领域层的领域服务接口。</p>
 *
 * <p>谁会调用它：应用启动时的自动装配入口，以及需要重新加载配置的管理操作。</p>
 *
 * <p>它向下调用什么：装配责任链上的各个节点，按 API 端点 → 聊天模型 → 原子 Agent → 组合工作流 → Runner
 * 的依赖顺序逐层创建并注册进 Spring 容器。</p>
 *
 * <p>它不负责什么：不处理对话、不建会话、不落库。装配失败不会留下半成品数据，
 * 但已经注册进容器的前几层 Bean 会保留，需要修好配置后重新装配。</p>
 */
public interface IArmoryService {

    /**
     * 按依赖拓扑装配传入的全部配置表。
     *
     * <p>关键输入：一批完整的配置表，每张表对应一个独立的智能体应用。</p>
     *
     * <p>不返回值，成果以 Spring Bean 的形式注册进容器，对话时按 agentId 取用。</p>
     *
     * <p>主要失败条件：模型服务地址不通、MCP 工具连不上、引用了不存在的 Agent 或模型名。
     * 任一必需节点失败就抛异常中断，避免注册出一个「能取到但跑不通」的半残 Agent，
     * 让问题在启动阶段暴露而不是等到用户对话时才炸。</p>
     */
    void acceptArmoryAgents(List<AiAgentConfigTableVO> tables) throws Exception;

}
