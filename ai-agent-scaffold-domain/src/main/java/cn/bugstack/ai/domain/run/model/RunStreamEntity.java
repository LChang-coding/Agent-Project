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

    private ChatRunEntity run;
    private Flowable<T> stream;
}
