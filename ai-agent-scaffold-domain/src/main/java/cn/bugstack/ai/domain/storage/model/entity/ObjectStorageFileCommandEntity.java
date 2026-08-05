package cn.bugstack.ai.domain.storage.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.file.Path;

/**
 * 「把一个已经落在本地磁盘的暂存文件上传成存储对象」这件事的全部入参。
 *
 * <p>所属层次：领域层（domain）storage 子域的入参模型，只装数据不含逻辑。</p>
 *
 * <p>为什么要和 {@code ObjectStorageCommandEntity} 分成两个命令：那个命令把整份内容放在字节数组里，
 * 几个人同时上传大文件就会把 JVM 堆吃光。这里改成只传路径，实现端边读边往存储里推，
 * 内存占用固定在一个小缓冲区，与文件大小无关。</p>
 *
 * <p>谁会创建它：需要上传大文件的领域服务（例如 RAG 原始文档、Skill 工具包）。
 * 谁会消费它：基础设施层实现的 {@code putFile}。</p>
 *
 * <p>它不负责什么：不生成对象键、不负责创建和删除这个暂存文件（暂存文件的生命周期由调用方管理，
 * 上传成功后调用方要自己清掉，否则临时目录会越积越大）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ObjectStorageFileCommandEntity {

    /** 目标存储桶名，决定文件落进哪个业务分区；实现端会拒绝含路径分隔符的桶名，防止用桶名穿越目录。 */
    private String bucket;
    /** 文件在桶内的完整路径，是后续下载与删除的唯一凭据；键里通常带内容哈希，避免不同上传互相覆盖。 */
    private String objectKey;
    /** 待上传的本地暂存文件路径；实现端只读不删，上传完由调用方负责清理，否则临时文件会一直堆积。 */
    private Path sourcePath;
    /** 调用方声明的文件字节数；实现端会拿它和真实长度反复比对，一旦不符就拒绝上传，防止文件在上传途中被替换。 */
    private long sizeBytes;
    /** 内容类型（MIME），作为对象元数据一起存下，影响下载时浏览器是内联展示还是当附件下载。 */
    private String contentType;
}
