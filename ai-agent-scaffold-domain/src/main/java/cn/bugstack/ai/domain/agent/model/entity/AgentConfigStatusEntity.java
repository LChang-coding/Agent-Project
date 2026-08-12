package cn.bugstack.ai.domain.agent.model.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 一个 Agent 在当前租户下的「能不能用」视图：静态配置里的身份信息 + 数据库里的租户启停结果。
 *
 * <p>所属层次：领域层的实体，是一个只读的合并结果，不对应单独一张库表。</p>
 *
 * <p>谁会调用它：{@code AgentAvailabilityService#queryConfigs} 产出它，管理端和前端的
 * Agent 列表接口直接展示；列表里的启停开关和乐观锁版本都来自这里。</p>
 *
 * <p>它向下依赖什么：静态配置（{@code AiAgentAutoConfigProperties}）提供 agentId/名称/描述，
 * 租户覆盖表提供 status/revision/disabledAt。两边缺一，展示出来的状态就是错的。</p>
 *
 * <p>它不负责什么：不判断权限、不写库、不参与对话执行。</p>
 */
@Data
@Builder
public class AgentConfigStatusEntity {
    /**
     * Agent 的对外编号，来自静态配置文件而非数据库。
     *
     * <p>前端建会话、发消息都要原样带回这个值；它同时也是租户覆盖表的查询键，
     * 静态配置里没有的 agentId 一律被视为「不存在的静态 Agent」，无法被启停。</p>
     */
    private String agentId;
    /** 界面上展示给用户的 Agent 名称，取自静态配置；只用于显示，不参与任何判断。 */
    private String agentName;
    /** Agent 的能力说明，取自静态配置；帮助用户在列表里选对智能体，不参与任何判断。 */
    private String agentDesc;
    /** 运行时编排角色，默认 NORMAL。 */
    private String orchestrationRole;
    /** Agent 目录分类。 */
    private String category;
    /** 推荐使用场景。 */
    private List<String> bestFor;
    /** 不推荐使用场景。 */
    private List<String> notFor;
    /** 结构化能力标签。 */
    private List<String> capabilities;
    /** 主 Agent 可委派的子 Agent 白名单。 */
    private List<String> allowedSubAgentIds;
    /**
     * 合并租户覆盖后的状态文本，只有 enabled / disabled 两种取值。
     *
     * <p>没有覆盖记录时按 enabled 处理，也就是「默认全部可用，只有被显式禁用才不可用」。
     * 这个字段给前端展示用，程序判断请用 {@code enabled}，避免大小写和文案变化带来的坑。</p>
     */
    private String status;
    /**
     * 供程序直接判断的可运行标记，等价于 status 是否为 enabled。
     *
     * <p>列表查询在 includeDisabled 为 false 时会用它过滤掉禁用项，
     * 因此它一旦算错，被禁用的 Agent 就会重新出现在用户的下拉框里。</p>
     */
    private Boolean enabled;
    /**
     * 租户覆盖记录的乐观锁版本；没有覆盖记录时为 0。
     *
     * <p>管理端修改启停状态时必须把它原样回传，服务端据此判断状态有没有被别人并发改过，
     * 版本不一致会直接拒绝更新并要求刷新页面。</p>
     */
    private Long revision;
    /** 最近一次被禁用的时间；当前处于启用状态时为空。仅用于管理端展示「什么时候被关掉的」。 */
    private LocalDateTime disabledAt;
}
