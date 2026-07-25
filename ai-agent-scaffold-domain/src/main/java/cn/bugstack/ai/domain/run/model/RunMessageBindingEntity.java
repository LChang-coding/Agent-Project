package cn.bugstack.ai.domain.run.model;

import cn.bugstack.ai.domain.session.model.entity.ChatMessageEntity;
import lombok.Builder;
import lombok.Data;

/**
 * 运行与消息的原子绑定结果。
 */
@Data
@Builder
public class RunMessageBindingEntity {

    /** 完成消息绑定后的最新运行快照。 */
    private ChatRunEntity run;
    /** 本次新建且已绑定的用户消息。 */
    private ChatMessageEntity message;
}
