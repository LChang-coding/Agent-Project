package cn.bugstack.ai.domain.asset.model;

import lombok.Builder;
import lombok.Data;

/**
 * 一次「上传聊天附件」请求的全部入参。
 *
 * <p>所属层次：领域层（domain）asset 子域的入参模型。</p>
 *
 * <p>谁会创建它：触发器层的附件上传控制器。谁会消费它：{@code AssetService#uploadChatAttachment}。</p>
 *
 * <p>最关键的约定：tenantId 和 ownerUserId 只能由服务端从认证上下文里取出来填进这里，
 * 绝不能让前端在表单里传。否则任何人都能填上别人的用户编号，把文件挂到别人名下，
 * 甚至借此往别人的会话里塞附件。</p>
 *
 * <p>它不负责什么：不做校验（合法性由领域服务的 validateUpload 统一判断）、
 * 不生成对象键、不决定文件存到哪个桶。</p>
 */
@Data
@Builder
public class AssetUploadCommandEntity {
    /** 所属租户，取自认证上下文；决定这份附件被隔离在哪个租户的数据范围内，个人模式为空。 */
    private String tenantId;
    /** 拥有者用户编号，取自认证上下文；缺失时领域服务直接抛「缺少可信用户身份」，不允许匿名上传。 */
    private String ownerUserId;
    /** 可选的预关联会话；填了就会先校验这个会话确实属于当前用户，防止把附件预挂到别人的会话上。 */
    private String sessionId;
    /** 浏览器传来的原始文件名；不可信，落库前会被清洗掉路径分隔符与 NUL 并限长后才作为展示名。 */
    private String fileName;
    /** 浏览器声明的内容类型；不可信，只作为解析格式的参考之一，为空时兜底成通用二进制类型。 */
    private String mimeType;
    /** 文件全部字节，直接放在内存里；领域服务会先校验非空且不超过 20 MiB，再算哈希和上传。 */
    private byte[] bytes;
}
