package cn.bugstack.ai.domain.run.model;

import cn.bugstack.ai.domain.session.model.entity.ChatMessageEntity;
import lombok.Builder;
import lombok.Data;

/**
 * 「写入用户消息 + 绑定到运行」这一步的原子结果。
 *
 * <p>解决什么问题：一条用户消息必须和一次运行严格配对。先写消息再绑运行，中间失败就会留下没人认领的孤儿消息；
 * 先绑运行再写消息，中间失败就会出现指向空消息的运行。所以这两步放在同一个事务里做，
 * 做完把「最新的运行快照」和「刚建好的消息」一起返回，调用方不必再回查一次数据库。</p>
 *
 * <p>所属层次：领域层运行模型，由 {@code RunControlService#appendUserMessage} 产出，
 * 供对话领域服务继续拿去组装提示词、绑定附件、启动执行。</p>
 *
 * <p>它不负责什么：不做校验、不做落库，只是把一次事务的两个产物打包带走。</p>
 */
@Data
@Builder
public class RunMessageBindingEntity {

    /** 绑定完成后重新读出的运行快照；里面的乐观锁版本已经是最新的，后续状态迁移必须基于它，否则会撞并发修改错误。 */
    private ChatRunEntity run;
    /** 本次新写入并已挂到运行上的用户消息；后续附件绑定、提示词拼装、引用落库都要用它的消息编号。 */
    private ChatMessageEntity message;
}
