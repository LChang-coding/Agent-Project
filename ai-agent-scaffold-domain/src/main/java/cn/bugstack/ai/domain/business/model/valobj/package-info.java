/**
 * 业务域「值对象」的预留包：值对象没有编号，只要字段内容一样就认为是同一个东西（例如一段 token 预算、一个金额）。
 *
 * <p>所属层次：领域层（domain）的模型部分。</p>
 *
 * <p>谁会用它：实体和聚合根把值对象当成自己的属性，领域服务在计算过程中创建和传递值对象。</p>
 *
 * <p>约束：值对象必须不可变——一旦创建就不再修改字段，需要新值时创建新对象。
 * 这样多个线程共享同一个值对象也不会互相影响，可以安全地放进缓存或并发流程里。</p>
 *
 * <p>当前状态：脚手架预留的空包。实际在跑的值对象示例见 {@code context.model.ContextBudget}（token 预算）、
 * {@code context.model.ContextFragmentType}（上下文片段类型枚举）。</p>
 */
package cn.bugstack.ai.domain.business.model.valobj;
