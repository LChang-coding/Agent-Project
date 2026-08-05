package cn.bugstack.ai.domain.storage.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.file.Path;

/**
 * 一次流式下载成功后的回执：告诉调用方「文件落在本地哪个位置、内容指纹是多少、实际多大」。
 *
 * <p>所属层次：领域层（domain）storage 子域的出参模型。</p>
 *
 * <p>谁会创建它：基础设施层实现的 {@code downloadToFile}。谁会消费它：发起下载的领域服务，
 * 拿到本地路径后继续做解压、解析等后续处理。</p>
 *
 * <p>只有下载并成功原子发布之后才会返回这个对象；中途失败一律抛 AppException 且临时文件已被清理，
 * 所以不会出现「拿到了结果但文件只写了一半」的情况。</p>
 *
 * <p>它不负责什么：不接管这个本地文件的生命周期——用完之后由调用方自己删除，
 * 否则本地目录会随着每次下载不断变大。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ObjectStorageDownloadResultEntity {

    /** 源对象所在的存储桶名，原样回显自下载命令，便于日志和排查时定位来源。 */
    private String bucket;

    /** 源对象在桶内的完整路径，原样回显自下载命令。 */
    private String objectKey;

 /** 文件在本地的最终绝对路径；到这一步它已经通过原子改名发布完成，读方看到的一定是完整文件。 */
    private Path targetPath;

    /** 下载内容的 SHA-256 摘要，边下边算出来，可用来核对文件与存储端一致、传输过程没有损坏。 */
    private String sha256;

    /** 实际写入本地的字节数，是真实统计值而非命令里声明的上限，可用于校验和用量统计。 */
    private long sizeBytes;
}
