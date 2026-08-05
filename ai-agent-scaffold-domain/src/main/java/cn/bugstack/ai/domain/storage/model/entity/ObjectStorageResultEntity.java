package cn.bugstack.ai.domain.storage.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一次对象写入成功后的回执：告诉调用方「东西存到哪儿了、内容指纹是多少、有多大」。
 *
 * <p>所属层次：领域层（domain）storage 子域的出参模型。</p>
 *
 * <p>谁会创建它：基础设施层的 MinIO 实现，在 putObject/putFile 成功后组装。
 * 谁会消费它：资产服务、RAG 文档上传服务、工具发布服务——它们把这里的字段原样落进各自的数据库表，
 * 后续下载、去重、校验完整性都以库里这份记录为准。</p>
 *
 * <p>只有写入成功才会返回这个对象；失败一律抛 AppException，所以拿到它就代表对象已经在存储里了。
 * 但要注意：拿到它只说明「文件写成了」，数据库记录还没写，两者之间失败仍需调用方补偿删除。</p>
 *
 * <p>它不负责什么：不含权限信息、不含业务状态（是否已被引用、是否已删除），那些在各自业务表里维护。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ObjectStorageResultEntity {

    /**
     * 文件实际落在哪个桶，原样回显自写入命令。
     *
     * <p>必须持久化到业务表里：下载时要用「桶 + 键」两个值一起定位，
     * 只存键的话一旦桶名配置变更就再也找不回文件。</p>
     */
    private String bucket;

    /**
     * 文件在桶内的完整路径，原样回显自写入命令，是后续下载和删除的唯一凭据。
     */
    private String objectKey;

    /**
     * 写入内容的 SHA-256 十六进制摘要，由实现端在写入过程中真实算出，不是调用方传进来的。
     *
     * <p>两个用途：一是内容去重——同一用户再次上传同样内容时，业务侧按摘要查到已有记录就直接复用，
     * 不再重复占用存储；二是完整性校验，下载后可以重算摘要确认文件没被损坏或篡改。</p>
     */
    private String sha256;

    /**
     * 实际写入的字节数，会持久化到业务表用于展示文件大小和统计用量。
     *
     * <p>流式上传时这个值等于调用方声明并被实现端核对过的长度，
     * 所以它可以被当作可信的真实大小，而不只是一个前端传上来的数字。</p>
     */
    private Long sizeBytes;
}
