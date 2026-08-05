package cn.bugstack.ai.domain.agent.model.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 租户对某个静态 Agent 的启停覆盖记录，对应租户覆盖库表的一行。
 *
 * <p>解决什么问题：Agent 的定义写在静态配置里，全部租户共享，但不同租户希望关掉不同的 Agent。
 * 与其为每个租户复制一份配置，不如只把「谁关了哪个 Agent」这一点差异存进数据库，
 * 查询时再和静态配置合并出最终状态。</p>
 *
 * <p>所属层次：领域层的实体，与库表一对一。</p>
 *
 * <p>谁会调用它：{@code AgentAvailabilityService} 读它判断 Agent 能不能跑，写它执行管理端的启停操作；
 * 读写都通过 {@code IAgentTenantOverrideRepository}。</p>
 *
 * <p>它不负责什么：不保存 Agent 的名称、描述、模型、提示词等定义信息，那些只存在于静态配置里。</p>
 */
@Data
@Builder
public class AgentTenantOverrideEntity {
    /**
     * 这条覆盖属于哪个租户，是租户隔离的关键。
     *
     * <p>所有查询和更新都必须带上它，否则会读到或改掉别人租户的开关。
     * 上层拿不到可信租户身份时会直接抛 TENANT_CONTEXT_MISSING，不允许用空值兜底。</p>
     */
    private String tenantId;
    /**
     * 被覆盖的静态 Agent 编号，与静态配置里的 agentId 对应。
     *
     * <p>写入前会先校验静态配置里确实存在这个 Agent，避免在库里留下指向不存在 Agent 的孤儿记录。</p>
     */
    private String agentId;
    /**
     * 覆盖后的状态，只允许 active 或 disabled 两个值。
     *
     * <p>写入前会把外部传来的 enabled 归一成 active，保证库里不出现第三种写法；
     * 值为 disabled 时对话请求会被拒绝并抛 AGENT_DISABLED。</p>
     */
    private String status;
    /**
     * 管理员填写的变更原因，纯说明性质，用于事后追查「这个 Agent 为什么被关了」。
     *
     * <p>入库前会去掉首尾空白并截断到 256 字符，防止超长文本把字段撑爆。</p>
     */
    private String reason;
    /** 执行这次启停的用户编号，取自可信身份而不是请求参数，用于审计追责。 */
    private String updatedBy;
    /**
     * 乐观锁版本号，每成功更新一次加一；首次插入为 0。
     *
     * <p>更新时会拿调用方给的预期版本和库里的实际版本比对，不一致就拒绝并提示刷新，
     * 这样两个管理员同时改同一个 Agent 时不会互相把对方的修改悄悄覆盖掉。</p>
     */
    private Long revision;
    /**
     * 最近一次被禁用的时间点；状态切回 active 时会被清成空值。
     *
     * <p>只用于展示和审计，不参与「能不能跑」的判断，判断只看 status。</p>
     */
    private LocalDateTime disabledAt;
}
