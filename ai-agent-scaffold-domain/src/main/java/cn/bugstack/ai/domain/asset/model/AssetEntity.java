package cn.bugstack.ai.domain.asset.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一份用户上传的附件在系统里的完整档案：谁的、存在哪、叫什么、解析成什么样、被哪条消息用了。
 *
 * <p>所属层次：领域层（domain）asset 子域的实体，对应数据库表 {@code artifact_asset}。</p>
 *
 * <p>谁会用它：{@code AssetService} 负责创建和查询它，{@code AssetContextContributor} 读它的
 * extractedText 拼进模型上下文，控制器层把它转成 DTO 返回前端。</p>
 *
 * <p>为什么要把「文件内容」和「这份档案」分开：真正的字节存在对象存储里，这里只留位置（桶 + 对象键）
 * 和指纹（sha256）。这样一份内容被同一个用户重复上传时可以复用同一个对象，数据库里也能记录多条引用。</p>
 *
 * <p>它不负责什么：不含文件字节本身、不含权限规则（规则在领域服务和 SQL 条件里）、
 * 不做状态流转（改状态一律走仓储的 UPDATE 语句，保证并发下的原子性）。</p>
 */
@Data
@Builder
public class AssetEntity {

 /** 数据库自增主键；只用于列表分页的游标（按 id 倒序翻页），绝不对外暴露，也不作为业务标识。 */
    private Long id;
    /** 所属租户；是数据隔离的第一道条件，个人模式下为 null，SQL 用「都为空或相等」匹配，所以不能塞空串。 */
    private String tenantId;
    /** 唯一拥有者的用户编号；查询、下载、删除、绑定消息全部以它为准，来源必须是认证上下文而不是请求体。 */
    private String ownerUserId;
    /** 可见范围；聊天附件固定写 private，表示只有拥有者能看，目前没有公开分享附件的入口。 */
    private String visibility;
    /** 附件归属的会话；上传时可以先声明，也可以留空等绑定消息时再写入，用于「本会话附件」过滤和上下文读取。 */
    private String sessionId;
 /** 绑定到的用户消息编号；为空表示还是一个游离附件。一旦写入就不能再改绑，保证一个附件只属于一条消息。 */
    private String messageId;
    /** 对外的资产标识（asset_ 前缀 + UUID）；前端和接口只认这个，不暴露自增主键以免被人枚举别人的附件。 */
    private String assetId;
    /** 资产用途分类；聊天附件写 chat_attachment，列表查询可按它过滤，便于以后接入其他用途的资产。 */
    private String assetKind;
    /** 前端展示用的类型标签：image、pdf、word、text 或 file，由 MIME 和扩展名共同推断，只影响图标显示。 */
    private String assetType;
    /** 原始文件所在的对象存储桶名；下载时必须「桶 + 键」一起用，只存键的话换桶配置后文件就找不回来了。 */
    private String bucket;
    /** 原始文件在桶内的完整路径；键里带内容哈希，因此不同内容不会互相覆盖，同一内容天然复用。 */
    private String objectKey;
  /** 展示文件名；已经去掉路径分隔符和 NUL 并限长，防止用它拼路径时发生目录穿越或注入。 */
    private String fileName;
    /** 内容类型（MIME）；缺失时兜底为 application/octet-stream，影响下载时浏览器是内联打开还是当附件保存。 */
    private String mimeType;
    /** 原始文件字节数；用于前端展示大小和用量统计，值来自实际读到的字节长度而非前端声明。 */
    private Long sizeBytes;
  /** 内容 SHA-256；同一用户再次上传相同内容时靠它命中复用，跳过一次上传和一次解析。 */
    private String sha256;
    /** 资产生命周期状态：active 可用、deleted 已软删；所有查询都要求 active，删除后从上下文里静默消失。 */
    private String status;
    /** 文本解析状态：ready 可注入模型、unsupported 格式不支持、failed 解析出错；只有 ready 才允许绑定到消息。 */
    private String parseStatus;
    /** 从附件里提取出的纯文本，已被截断；这是附件唯一能进入模型上下文的内容，模型看不到原始文件。 */
    private String extractedText;
/** 解析失败的简短原因，已压平换行并限长；只用于排查和提示，刻意不包含文件正文以免泄露内容。 */
    private String parseError;
    /** 预留的扩展元数据 JSON，供以后追加字段而不改表结构；当前聊天附件流程不写它。 */
    private String metadata;
    /** 记录创建时间，由数据库写入；用于审计和按时间排查。 */
    private LocalDateTime createTime;
    /** 记录最后更新时间，由数据库维护；绑定消息或软删除都会刷新它，可据此确认改动是否真的生效。 */
    private LocalDateTime updateTime;
}
