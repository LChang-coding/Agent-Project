package cn.bugstack.ai.domain.tool.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 「这次请求有没有抢到调用外部工具的资格」这一判定结果，是幂等门禁的返回值。
 *
 * <p>所属层次：工具领域的实体（一次性判定结果对象），不落库。</p>
 *
 * <p>谁产出它：{@code ToolDispatchAuthorizationService#claim} 在短事务里尝试用幂等键插入一条 started 日志，
 * 插入成功就产出 claimed=true，插入冲突就把已存在的那条日志装进来产出 claimed=false。</p>
 *
 * <p>谁消费它：{@code ToolGateway}。它只有拿到 claimed=true 才允许真正调用外部接口；
 * 拿到 false 就改成重放历史结果，绝不重复执行。</p>
 *
 * <p>为什么必须有这个对象：大模型重试、网络抖动、用户重发都会让同一个函数调用打进来多次。
 * 如果每次都真执行，用户可能被重复下单、重复扣费。这个对象就是「同一件事只做一次」的凭据。</p>
 *
 * <p>它不负责什么：不执行工具、不写结果、不判断权限（运行状态授权在同一个事务里由运行控制服务完成）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolDispatchClaimEntity {

    /** true 表示这次请求是第一个抢到执行权的，允许真的去调外部工具；false 表示别人已经抢到过，本次只能重放历史结果。 */
    private boolean claimed;
/** 与这次幂等键对应的权威调用日志：claimed=true 时是刚插入的 started 记录，用于事后回填结果；false 时是别人留下的旧记录，用于重放。 */
    private ToolCallLogEntity callLog;
}
