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

    private ChatRunEntity run;
    private ChatMessageEntity message;
}
