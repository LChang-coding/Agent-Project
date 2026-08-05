package cn.bugstack.ai.domain.run.model;

import io.reactivex.rxjava3.core.Flowable;
import lombok.Builder;
import lombok.Data;

/**
 * 把「运行身份」和「事件流」绑成一个返回值，交给上层同时拿到 runId 和数据流。
 *
 * <p>解决什么问题：流式对话必须先有 runId 才能登记取消句柄、才能给前端下发 run 事件，
 * 但真正的数据要靠订阅流才拿得到。如果只返回流，上层就无从取消；如果只返回运行记录，上层又拿不到内容。
 * 所以这里成对返回：运行记录在流开始前就已经落库，流则可以稍后订阅。</p>
 *
 * <p>所属层次：领域层运行模型。由 {@code IChatService} 的启动方法产出，
 * 由触发器层（SSE 控制器、同步对话接口）消费。</p>
 *
 * <p>它不负责什么：不做订阅、不做取消、不管流的生命周期。谁订阅谁负责在结束或取消时释放句柄。</p>
 *
 * @param <T> 流里元素的类型：工作流分支是文本片段，Agent 分支是 ADK 事件对象
 */
@Data
@Builder
public class RunStreamEntity<T> {

    /** 流开始之前就已经写入数据库的运行记录；上层从这里取 runId、状态和上下文版本去登记取消并告知前端。 */
    private ChatRunEntity run;
    /** 与上面这条运行绑定的事件流；订阅它才会真正开始产出内容，释放订阅即等于中断本次执行。 */
    private Flowable<T> stream;
}
