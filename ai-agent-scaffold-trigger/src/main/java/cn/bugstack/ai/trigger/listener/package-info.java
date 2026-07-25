/**
 * 异步消息消费适配器。
 * <p>负责恢复租户与 Trace 上下文、回查数据库任务账本并调用领域服务，消费线程结束前必须清理请求级上下文。</p>
 */
package cn.bugstack.ai.trigger.listener;
