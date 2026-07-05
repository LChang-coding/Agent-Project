package cn.bugstack.ai.infrastructure.storage;

import cn.bugstack.ai.domain.storage.model.entity.ObjectStorageCommandEntity;
import cn.bugstack.ai.domain.storage.model.entity.ObjectStorageResultEntity;
import cn.bugstack.ai.domain.storage.service.ObjectStorageService;
import cn.bugstack.ai.types.exception.AppException;
import cn.bugstack.ai.types.observability.AiLog;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * MinIO 对象存储实现。
 * <p>本地开发未配置 MinIO 时可自动降级到本地目录，避免上传流程被远端中间件阻塞。</p>
 */
@Slf4j
@Service
public class MinioObjectStorageService implements ObjectStorageService {

    private final ObjectStorageProperties properties;

    /**
     * 创建对象存储服务；参数是对象存储配置；返回服务实例。
     */
    public MinioObjectStorageService(ObjectStorageProperties properties) {
        this.properties = properties;
    }

    /**
     * 写入对象；参数是对象写入命令；返回对象摘要和位置。
     */
    @Override
    public ObjectStorageResultEntity putObject(ObjectStorageCommandEntity command) {
        long start = System.currentTimeMillis();
        checkCommand(command);
        try {
            if (useMinio()) {
                putMinio(command);
            } else {
                putLocal(command);
            }
            long costMs = System.currentTimeMillis() - start;
            AiLog.info(AiLog.oss().upload(command.getBucket(), command.getObjectKey(), (long) command.getBytes().length, costMs, true));
            return ObjectStorageResultEntity.builder()
                    .bucket(command.getBucket())
                    .objectKey(command.getObjectKey())
                    .sha256(sha256(command.getBytes()))
                    .sizeBytes((long) command.getBytes().length)
                    .build();
        } catch (Exception e) {
            long costMs = System.currentTimeMillis() - start;
            AiLog.error(AiLog.oss().error("upload", command.getBucket(), command.getObjectKey(), costMs, e));
            throw new AppException("OBJECT_STORAGE_UPLOAD_FAILED", "对象上传失败：" + e.getMessage());
        }
    }

    /**
     * 读取对象；参数是存储桶和对象 Key；返回文件字节内容。
     */
    @Override
    public byte[] getObject(String bucket, String objectKey) {
        long start = System.currentTimeMillis();
        try {
            byte[] bytes = useMinio() ? getMinio(bucket, objectKey) : getLocal(bucket, objectKey);
            long costMs = System.currentTimeMillis() - start;
            AiLog.info(AiLog.oss().download(bucket, objectKey, (long) bytes.length, costMs, true));
            return bytes;
        } catch (Exception e) {
            long costMs = System.currentTimeMillis() - start;
            AiLog.error(AiLog.oss().error("download", bucket, objectKey, costMs, e));
            throw new AppException("OBJECT_STORAGE_DOWNLOAD_FAILED", "对象下载失败：" + e.getMessage());
        }
    }

    /**
     * 获取 Skill 包默认桶；无参数；返回桶名称。
     */
    @Override
    public String skillBucket() {
        return properties.getMinio().getSkillBucket();
    }

    /**
     * 获取资产默认桶；无参数；返回桶名称。
     */
    @Override
    public String assetBucket() {
        return properties.getMinio().getAssetBucket();
    }

    /**
     * 判断是否使用 MinIO；无参数；返回是否使用远端对象存储。
     */
    private boolean useMinio() {
        return "minio".equalsIgnoreCase(properties.getType());
    }

    /**
     * 上传到 MinIO；参数是对象命令；无返回值。
     */
    private void putMinio(ObjectStorageCommandEntity command) throws Exception {
        MinioClient client = minioClient();
        ensureBucket(client, command.getBucket());
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(command.getBytes())) {
            client.putObject(PutObjectArgs.builder()
                    .bucket(command.getBucket())
                    .object(command.getObjectKey())
                    .stream(inputStream, command.getBytes().length, -1)
                    .contentType(command.getContentType())
                    .build());
        }
    }

    /**
     * 从 MinIO 下载；参数是存储桶和对象 Key；返回字节内容。
     */
    private byte[] getMinio(String bucket, String objectKey) throws Exception {
        try (InputStream inputStream = minioClient().getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey).build())) {
            return inputStream.readAllBytes();
        }
    }

    /**
     * 上传到本地目录；参数是对象命令；无返回值。
     */
    private void putLocal(ObjectStorageCommandEntity command) throws Exception {
        Path target = localPath(command.getBucket(), command.getObjectKey());
        Files.createDirectories(target.getParent());
        Files.write(target, command.getBytes());
    }

    /**
     * 从本地目录下载；参数是存储桶和对象 Key；返回字节内容。
     */
    private byte[] getLocal(String bucket, String objectKey) throws Exception {
        return Files.readAllBytes(localPath(bucket, objectKey));
    }

    /**
     * 构造本地路径；参数是存储桶和对象 Key；返回本地路径。
     */
    private Path localPath(String bucket, String objectKey) {
        return Path.of(properties.getLocalRoot()).resolve(bucket).resolve(objectKey).normalize();
    }

    /**
     * 创建 MinIO 客户端；无参数；返回客户端。
     */
    private MinioClient minioClient() {
        return MinioClient.builder()
                .endpoint(properties.getMinio().getEndpoint())
                .credentials(properties.getMinio().getAccessKey(), properties.getMinio().getSecretKey())
                .build();
    }

    /**
     * 确保存储桶存在；参数是客户端和桶名；无返回值。
     */
    private void ensureBucket(MinioClient client, String bucket) throws Exception {
        boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        if (!exists) {
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }

    /**
     * 校验上传命令；参数是上传命令；无返回值。
     */
    private void checkCommand(ObjectStorageCommandEntity command) {
        if (command == null || command.getBucket() == null || command.getBucket().isBlank()
                || command.getObjectKey() == null || command.getObjectKey().isBlank()
                || command.getBytes() == null) {
            throw new AppException("OBJECT_STORAGE_PARAM_INVALID", "对象存储参数不完整");
        }
    }

    /**
     * 计算 SHA-256；参数是字节内容；返回十六进制摘要。
     */
    private String sha256(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(bytes));
    }
}
