/**
 * 业务域「领域服务」的预留包：当一条业务规则需要同时读写多个聚合、或需要按顺序调用多个仓储端口时，
 * 这段编排逻辑既不属于任何单个实体，也不该放进控制器，就落在领域服务里。
 *
 * <p>所属层次：领域层（domain）的服务部分，是业务规则真正的落脚点。</p>
 *
 * <p>谁会调用它：触发器层（HTTP 控制器）和应用层的任务调度器；它们只负责传参和翻译异常，不做业务判断。</p>
 *
 * <p>它向下调用什么：本包内的模型对象，以及 {@code business.adapter.repository} 下声明的仓储端口。</p>
 *
 * <p>它不负责什么：不处理 HTTP 参数绑定、不拼装对外 DTO、不写 SQL，也不关心用了哪个数据库。</p>
 *
 * <p>当前状态：脚手架预留的空包。实际在跑的领域服务示例见 {@code session.service.SessionDomain}（会话与消息编排）、
 * {@code context.service.ConversationMemoryService}（上下文装配与压缩）。</p>
 */
package cn.bugstack.ai.domain.business.service;
