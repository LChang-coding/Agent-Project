package cn.bugstack.ai.domain.tool.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一个 Skill 的某一版冻结快照：这一版用的是哪个包、包的指纹是什么、从包里解出了什么清单。
 *
 * <p>所属层次：工具领域的实体，对应 Skill 版本表。</p>
 *
 * <p>为什么要冻结：模型这一轮是按当时那份 SKILL.md 的指令在办事。如果包能被悄悄替换，
 * 同一个工具在两次对话里就会有两套行为，出了问题也无法复现。把包位置和指纹钉死在版本里才能保证可追溯。</p>
 *
 * <p>谁读写它：{@code ToolPublishService} 创建版本、发布时置为激活；运行时通过工具目录间接读到它的桶和对象键。</p>
 *
 * <p>它不负责什么：不保存 SKILL.md 全文（每次调用现读现取，避免库里存一大坨文本）、不保存调用记录。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillVersionEntity {

    /** 数据库自增主键；仅库内使用。 */
    private Long id;
    /** 版本所属租户；查询时必须带上，防止跨租户读到别人的包位置。 */
    private String tenantId;
    /** 创建这一版时的所有者快照；即使定义后来转手，也能追溯这一版是谁传的。 */
    private String ownerUserId;
    /** 所属 Skill 的业务编号；和版本号一起构成业务上的唯一定位。 */
    private String skillId;
    /** 版本记录自身的稳定业务编号；发布时写进定义的激活版本指针。 */
    private String versionId;
    /** 用户可见的版本号；同一个 Skill 下不允许重复，重复创建会直接报错以保证版本不可变。 */
    private String version;
    /** 这一版使用的上传资产编号；串起「上传的那个文件」和「发布的这个版本」。 */
    private String assetId;
    /** 包所在的对象存储桶；调用时凭它 + 对象键把 ZIP 取回来解析。 */
    private String bucket;
    /** 包在桶里的对象键；路径含租户与随机目录，不同上传不会互相覆盖。 */
    private String objectKey;
    /** 上传时的原始文件名（已清洗）；仅用于界面展示和留档。 */
    private String fileName;
  /** 包内容的 SHA-256 指纹；用来确认线上跑的包和当初上传的是同一个文件，缺失时创建版本前会现算补齐。 */
    private String sha256;
    /** 包字节数；用于展示和容量统计。 */
    private Long sizeBytes;
    /**
     * 从 SKILL.md 头部（front matter）解析出的清单 JSON。
     * 只取一级键值对，不解释正文里的指令——正文是给模型看的自然语言，服务端故意不去理解它，
     * 避免把用户可控的文本当成配置来执行。
     */
    private String manifestJson;
    /** 版本生命周期状态（draft/active）；发布时置为 active，运行目录只关联激活版本。 */
    private String status;
    /** 预留扩展元数据 JSON；当前流程不写。 */
    private String metadata;
}
