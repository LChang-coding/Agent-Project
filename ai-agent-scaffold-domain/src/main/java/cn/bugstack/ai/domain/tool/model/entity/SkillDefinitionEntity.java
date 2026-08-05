package cn.bugstack.ai.domain.tool.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一个 Skill 工具的「主记录」：保存名称、说明、可见范围，以及指向当前已发布版本的那几个指针。
 *
 * <p>所属层次：工具领域的实体，对应 Skill 定义表。</p>
 *
 * <p>Skill 是什么：一个 ZIP 包，里面必须有 SKILL.md。调用它的效果是把这份 Markdown 指令读出来交给大模型，
 * 让模型按里面写的步骤办事。注意包里的代码不会被执行，所以 Skill 本身不产生外部副作用。</p>
 *
 * <p>谁读写它：{@code ToolPublishService} 负责创建、加版本、发布、停用；运行时读的是仓储关联出的工具目录。</p>
 *
 * <p>它不负责什么：不保存包内容和包指纹（在版本记录里）、不保存调用日志、不做权限判断。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillDefinitionEntity {

    /** 数据库自增主键；仅库内使用，对外一律用业务编号。 */
    private Long id;
  /** 所属租户；所有查询和更新都靠它隔离，缺失或写错会造成跨租户读写。 */
    private String tenantId;
  /** 所有者用户编号；私有 Skill 只有他本人或租户管理员能改，是权限校验的核心依据。 */
    private String ownerUserId;
    /** 可见范围（private 或 tenant_public）；决定同租户其他成员的对话里能不能出现这个工具。 */
    private String visibility;
    /** Skill 稳定业务编号；对外唯一标识，也是调用审计里的工具编号。 */
    private String skillId;
    /** 展示名称；会进入模型能看到的函数描述。 */
    private String skillName;
    /** 租户内可引用的编码；生成模型函数名时优先用它，落库前已清洗过非法字符并截断长度。 */
    private String skillCode;
    /** 用途说明；模型靠它判断该不该用这个 Skill，含糊的描述会导致模型乱用或不用。 */
    private String description;
    /** 包的来源类型；当前固定是对象存储，预留将来支持其他来源时分流读取。 */
    private String sourceType;
    /** 当前源包的定位串（桶 + 对象键）；便于人工排查这个 Skill 的包到底存在哪。 */
    private String sourceUri;
    /** 兼容字段，保存当前版本号；历史接口读它，新逻辑一律看已发布版本，两者含义容易混淆需留意。 */
    private String version;
    /** 最近编辑的版本号；加新版本时推进它，但它不代表线上正在跑的版本。 */
    private String currentVersion;
    /** 已发布、对运行时可见的版本号；模型实际读到的 SKILL.md 就来自这一版。 */
    private String publishedVersion;
    /** 当前激活版本的记录编号；发布时一起写入，用于直接定位那条冻结的版本记录。 */
    private String activeVersionId;
    /** 定义的生命周期状态（draft/active/disabled）；只有 active 才会被查进运行目录，停用后模型下一轮就看不到它。 */
    private String status;
    /** 预留扩展元数据 JSON；当前流程不写。 */
    private String metadata;
}
