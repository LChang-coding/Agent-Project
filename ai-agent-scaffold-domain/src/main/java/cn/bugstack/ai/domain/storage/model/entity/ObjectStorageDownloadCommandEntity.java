package cn.bugstack.ai.domain.storage.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.file.Path;

/**
 * 「把存储里的一个对象流式下载到本地磁盘」这件事的全部入参，核心作用是把落盘位置死死限制在一个安全目录内。
 *
 * <p>所属层次：领域层（domain）storage 子域的入参模型，只装数据不含逻辑。</p>
 *
 * <p>为什么不直接给一个目标文件路径：对象键是可能被外部影响的数据，如果直接拿它拼路径，
 * 一个形如 {@code ../../etc/xxx} 的键就能把文件写到程序目录之外。所以这里强制拆成
 * 「受控根目录 + 相对路径」两段，实现端规范化后必须确认最终路径仍在根目录之内，
 * 并逐级检查途经的每一层都不是符号链接，否则一律拒绝。</p>
 *
 * <p>谁会创建它：需要把远端文件拉到本地再处理的领域服务（例如把 Skill 包下载下来解压执行）。
 * 谁会消费它：基础设施层实现的 {@code downloadToFile}。</p>
 *
 * <p>它不负责什么：不判断调用方有没有权限读这个对象，也不管下载下来的文件之后谁负责删除。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ObjectStorageDownloadCommandEntity {

    /** 源对象所在的存储桶名，取自 ObjectStorageService 的 xxxBucket() 而非手写字符串。 */
    private String bucket;

    /** 源对象在桶内的完整路径；它可能来自数据库里的业务记录，所以绝不能直接拿它拼本地落盘路径。 */
    private String objectKey;

    /** 允许写入的本地根目录，是这次下载的安全边界；最终路径若不在它之下，实现端直接拒绝下载。 */
    private Path targetRoot;

/** 相对于根目录的落盘位置；必须是相对路径，实现端会规范化后再确认没有越出根目录。 */
    private Path relativeTargetPath;

    /** 本次允许下载的最大字节数；边下边计数，超了立刻中止，防止一个超大对象把本地磁盘写满。 */
    private long maxBytes;
}
