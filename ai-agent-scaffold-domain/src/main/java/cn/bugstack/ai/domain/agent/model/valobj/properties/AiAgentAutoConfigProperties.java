package cn.bugstack.ai.domain.agent.model.valobj.properties;

import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * 承载 application.yml 中 {@code ai.agent.config} 这一段的静态 Agent 定义。
 *
 * <p>解决什么问题：把「系统里有哪些 Agent、每个 Agent 用什么模型和工具」写在配置文件里，
 * 启动时一次读入内存，对话时不再查库，既省了一次 IO 也避免了配置表被误改导致线上行为突变。</p>
 *
 * <p>所属层次：领域层的值对象（配置绑定对象），由 Spring Boot 在启动阶段完成绑定。</p>
 *
 * <p>谁会调用它：启动装配入口按 {@code enabled} 决定是否装配；{@code AgentAvailabilityService}
 * 把它当作 Agent 身份和元数据的唯一事实源，判断某个 agentId 是不是「静态 Agent」。</p>
 *
 * <p>它不负责什么：不保存租户级的启停差异（那在数据库的租户覆盖表里），也不保存会话和消息。
 * {@code ignoreInvalidFields = true} 意味着写错的字段会被静默忽略，不会让应用起不来，
 * 代价是配置写错时表现为「Agent 少了某项能力」而不是启动报错，排查时要留意这一点。</p>
 */
@Data
@ConfigurationProperties(prefix = "ai.agent.config", ignoreInvalidFields = true)
public class AiAgentAutoConfigProperties {

    /**
     * 是否在应用启动阶段就把配置表装配成可执行的 Agent。
     *
     * <p>默认关闭，这样本地起服务或跑单测时不会去连模型服务和 MCP 工具进程。
     * 打开后启动会变慢，但对话请求进来时就能直接拿到 Runner；关闭时对话会因为找不到
     * 已注册的 Agent 而失败。</p>
     */
    private boolean enabled = false;

    /**
     * 配置表名称到整棵 Agent 配置树的映射，key 只是 YAML 里的分组名，没有业务含义。
     *
     * <p>装配时会遍历所有 value 逐张表装配，因此这里放几张表就会注册几套 Agent；
     * 可用性服务也遍历它取出每张表的根 Agent，作为「系统里到底有哪些 Agent」的答案。
     * 为空表示没有任何静态 Agent，此时 Agent 列表接口会返回空数组。</p>
     */
    private Map<String, AiAgentConfigTableVO> tables;

}
