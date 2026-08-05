/**
 * 业务域「实体」的预留包：实体是有唯一编号、并且会随时间改变状态的对象，
 * 判断两个实体是否相同看的是编号（例如 sessionId、runId），而不是字段内容是否一样。
 *
 * <p>所属层次：领域层（domain）的模型部分，是承载生命周期状态的载体。</p>
 *
 * <p>谁会用它：领域服务读取和修改实体，仓储端口负责把实体持久化；
 * 对外返回时通常还要转成 DTO，避免把内部字段直接暴露给前端。</p>
 *
 * <p>约束：实体可以携带状态流转方法（例如「标记为已完成」），但同样不能依赖仓储或外部服务。
 * 涉及租户隔离的实体必须带上 tenantId 字段，否则查询时无法把不同租户的数据隔开。</p>
 *
 * <p>当前状态：脚手架预留的空包。实际在跑的实体示例见 {@code session.model.entity.ChatSessionEntity}、
 * {@code auth.model.entity.AuthUserEntity}。</p>
 */
package cn.bugstack.ai.domain.business.model.entity;
