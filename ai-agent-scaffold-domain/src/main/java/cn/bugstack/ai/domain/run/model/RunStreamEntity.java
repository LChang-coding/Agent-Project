package cn.bugstack.ai.domain.run.model;

import io.reactivex.rxjava3.core.Flowable;
import lombok.Builder;
import lombok.Data;

/**
 * 带运行身份的流式结果。
 *
 * @param <T> 流事件类型
 */
@Data
@Builder
public class RunStreamEntity<T> {

    /** 流开始前已经持久化的运行身份。 */
    private ChatRunEntity run;
    /** 与该运行绑定、可被取消的事件流。 */
    private Flowable<T> stream;
}
