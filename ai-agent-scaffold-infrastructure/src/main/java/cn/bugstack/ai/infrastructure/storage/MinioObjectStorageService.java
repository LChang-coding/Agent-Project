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
import io.minio.RemoveObjectArgs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * MinIO 对象存储实现。
 * <p>本地开发未配置 MinIO 时可自动降级到本地目录，避免上传流程被远端中间件阻塞。</p>
 */
@Slf4j
@Service
public class MinioObjectStorageService implements ObjectStorageService {

    private static final long DEFAULT_MAX_READ_BYTES = 64L * 1024 * 1024;

    private final ObjectStorageProperties properties;
    private final Supplier<MinioClient> minioClientFactory;
    private final Object minioClientMonitor = new Object();
    private final Object bucketMonitor = new Object();
    private final Set<String> readyBuckets = ConcurrentHashMap.newKeySet();
    private volatile MinioClient minioClient;

    /**
     * 创建对象存储服务；参数是对象存储配置；返回服务实例。
     */
    public MinioObjectStorageService(ObjectStorageProperties properties) {
        this(properties, () -> buildMinioClient(properties));
    }

    /**
     * 创建可测试的对象存储服务；参数是对象存储配置和客户端工厂；返回服务实例。
     */
    MinioObjectStorageService(ObjectStorageProperties properties, Supplier<MinioClient> minioClientFactory) {
        this.properties = properties;
        this.minioClientFactory = minioClientFactory;
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
        return getObject(bucket, objectKey, DEFAULT_MAX_READ_BYTES);
    }

    /**
     * 限量读取对象；参数是桶、对象键和最大字节数；返回文件字节内容。
     */
    @Override
    public byte[] getObject(String bucket, String objectKey, long maxBytes) {
        long start = System.currentTimeMillis();
        checkLocation(bucket, objectKey);
        if (maxBytes <= 0 || maxBytes > Integer.MAX_VALUE - 1L) {
            throw new AppException("OBJECT_STORAGE_LIMIT_INVALID", "对象读取上限不合法");
        }
        try {
            byte[] bytes = useMinio() ? getMinio(bucket, objectKey, maxBytes) : getLocal(bucket, objectKey, maxBytes);
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
     * 删除对象；参数是桶和对象键；无返回值。
     */
    @Override
    public void deleteObject(String bucket, String objectKey) {
        checkLocation(bucket, objectKey);
        try {
            if (useMinio()) {
                minioClient().removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
            } else {
                Files.deleteIfExists(localPath(bucket, objectKey));
            }
        } catch (Exception e) {
            throw new AppException("OBJECT_STORAGE_DELETE_FAILED", "对象删除失败：" + e.getMessage());
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
    private byte[] getMinio(String bucket, String objectKey, long maxBytes) throws Exception {
        try (InputStream inputStream = minioClient().getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey).build())) {
            byte[] bytes = inputStream.readNBytes((int) maxBytes + 1);
            checkReadSize(bytes.length, maxBytes);
            return bytes;
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
    private byte[] getLocal(String bucket, String objectKey, long maxBytes) throws Exception {
        Path path = localPath(bucket, objectKey);
        checkReadSize(Files.size(path), maxBytes);
        return Files.readAllBytes(path);
    }

    /**
     * 构造本地路径；参数是存储桶和对象 Key；返回本地路径。
     */
    private Path localPath(String bucket, String objectKey) {
        checkLocation(bucket, objectKey);
        Path root = Path.of(properties.getLocalRoot()).toAbsolutePath().normalize();
        Path bucketRoot = root.resolve(bucket).normalize();
        Path target = bucketRoot.resolve(objectKey).normalize();
        if (!target.startsWith(bucketRoot)) {
            throw new AppException("OBJECT_STORAGE_PATH_INVALID", "对象键越出存储根目录");
        }
        return target;
    }

    /**
     * 创建 MinIO 客户端；无参数；返回客户端。
     */
    private MinioClient minioClient() {
        MinioClient current = minioClient;
        if (current != null) {
            return current;
        }
        synchronized (minioClientMonitor) {
            if (minioClient == null) {
                minioClient = minioClientFactory.get();
            }
            return minioClient;
        }
    }

    /**
     * 根据对象存储配置创建 MinIO 客户端；参数是对象存储配置；返回可复用客户端。
     */
    private static MinioClient buildMinioClient(ObjectStorageProperties properties) {
        return MinioClient.builder()
                .endpoint(properties.getMinio().getEndpoint())
                .credentials(properties.getMinio().getAccessKey(), properties.getMinio().getSecretKey())
                .build();
    }

    /**
     * 确保存储桶存在；参数是客户端和桶名；无返回值。
     */
    private void ensureBucket(MinioClient client, String bucket) throws Exception {
        if (readyBuckets.contains(bucket)) {
            return;
        }
        synchronized (bucketMonitor) {
            if (readyBuckets.contains(bucket)) {
                return;
            }
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
            readyBuckets.add(bucket);
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
        checkLocation(command.getBucket(), command.getObjectKey());
    }

    private void checkLocation(String bucket, String objectKey) {
        if (bucket == null || bucket.isBlank() || objectKey == null || objectKey.isBlank()
                || bucket.contains("/") || bucket.contains("\\") || objectKey.indexOf('\0') >= 0) {
            throw new AppException("OBJECT_STORAGE_PARAM_INVALID", "对象存储位置不合法");
        }
    }

    private void checkReadSize(long actualBytes, long maxBytes) {
        if (actualBytes > maxBytes) {
            throw new AppException("OBJECT_STORAGE_TOO_LARGE", "对象大小超过读取上限");
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
