
package cn.bugstack.ai.domain.storage.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 「把一份内存里的字节写成一个存储对象」这件事所需的全部入参，打包成一个命令对象往下传。
 *
 * <p>所属层次：领域层（domain）storage 子域的入参模型，只装数据不含逻辑。</p>
 *
 * <p>谁会创建它：需要存文件的领域服务，例如资产服务上传聊天附件、会话分享服务上传导出的 Markdown、
 * 工具发布服务上传 Skill 包。谁会消费它：基础设施层的 MinIO 实现 {@code putObject}。</p>
 *
 * <p>为什么要有这个命令对象：写对象需要四个参数，直接摊在方法签名上后续加字段就要改所有调用方；
 * 打包成命令后，实现端还能在一处集中做参数完整性和路径合法性校验。</p>
 *
 * <p>它不负责什么：不生成对象键（键的命名规则属于各业务领域服务）、不校验权限、不做去重判断。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ObjectStorageCommandEntity {

    /**
     * 目标存储桶名，决定文件最终落在哪一个业务分区里（附件桶、RAG 桶、Skill 桶各自独立）。
     *
     * <p>取值来自 {@code ObjectStorageService} 的 assetBucket()/ragBucket()/skillBucket()，
     * 不允许自己拼字符串。实现端会拒绝含 {@code /} 或 {@code \} 的桶名，防止用桶名穿越目录。</p>
     */
    private String bucket;

    /**
     * 对象键，也就是文件在桶内的完整相对路径，是这份文件唯一的定位坐标。
     *
     * <p>由调用方按业务规则生成，通常形如 {@code assets/租户/用户/哈希前两位/哈希.扩展名}。
     * 键里带内容哈希是刻意设计：同一份内容重复上传会命中同一个键从而天然去重，
     * 不同内容永远不会撞键，也就不会互相覆盖。</p>
     *
     * <p>租户隔离依赖键前缀，因此这里如果漏了租户段，不同租户的文件就会混在同一层目录下。</p>
     */
    private String objectKey;

    /**
     * 要写入的完整文件内容，直接持有在 JVM 堆里。
     *
     * <p>正因为整份内容都在内存里，这个命令只适合小文件；大文件必须改用
     * {@code ObjectStorageFileCommandEntity} 走暂存文件流式上传，否则并发上传会把堆内存打满。</p>
     *
     * <p>实现端会用它算 SHA-256 并回填到写入结果里，业务侧再拿这个摘要做内容去重。</p>
     */
    private byte[] bytes;

    /**
     * 内容类型（MIME），会作为对象元数据一起存进对象存储，影响下载时浏览器怎么处理这个文件。
     *
     * <p>为空时实现端不会报错，但对象就没有类型信息；业务侧通常在上传前兜底成
     * {@code application/octet-stream}，让浏览器按下载而不是按内联展示处理，避免脚本类文件被直接执行。</p>
     */
    private String contentType;
}
