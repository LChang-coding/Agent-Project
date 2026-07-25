package cn.bugstack.ai.domain.context.model;

/**
 * 上下文压缩异步命令。
 * <p>Kafka 事件用于即时通知，最终执行范围仍以 MySQL 任务账本为准。</p>
 *
 * @param taskId 消费者回查任务账本的唯一标识
 * @param tenantId 任务所属租户
 * @param userId 任务所属用户
 * @param sessionId 任务所属会话
 * @param fromSequence 压缩起始序号
 * @param toSequence 压缩结束序号
 * @param expectedMemoryVersion 创建任务时的摘要基线版本
 * @param policyVersion 创建任务时的策略版本
 * @param traceId 原始触发链路标识
 */
public record ContextCompactionCommand(String taskId,
                                       String tenantId,
                                       String userId,
                                       String sessionId,
                                       Integer fromSequence,
                                       Integer toSequence,
                                       Integer expectedMemoryVersion,
                                       String policyVersion,
                                       String traceId) {
}
