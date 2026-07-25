package cn.bugstack.ai.infrastructure.storage;

import cn.bugstack.ai.domain.storage.model.entity.ObjectStorageCommandEntity;
import cn.bugstack.ai.domain.storage.model.entity.ObjectStorageDownloadCommandEntity;
import cn.bugstack.ai.domain.storage.model.entity.ObjectStorageDownloadResultEntity;
import cn.bugstack.ai.domain.storage.model.entity.ObjectStorageFileCommandEntity;
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
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * MinIO 对象存储实现。
 * <p>本地开发未配置 MinIO 时可自动降级到本地目录，避免上传流程被远端中间件阻塞。</p>
 * <p>本类只校验存储位置与文件安全，不判断租户是否有权访问对象。</p>
 */
@Slf4j
@Service
public class MinioObjectStorageService implements ObjectStorageService {

    /** 默认整对象读取上限，优先使用流式下载处理大文件。 */
    private static final long DEFAULT_MAX_READ_BYTES = 64L * 1024 * 1024;

    private final ObjectStorageProperties properties;
    private final Supplier<MinioClient> minioClientFactory;
    /** 分离客户端和建桶锁，避免首次连接互相扩大临界区。 */
    private final Object minioClientMonitor = new Object();
    private final Object bucketMonitor = new Object();
    /** 仅缓存本进程已确认的桶，进程重启后重新探测。 */
    private final Set<String> readyBuckets = ConcurrentHashMap.newKeySet();
    private volatile MinioClient minioClient;

    /**
     * 创建对象存储服务；参数是对象存储配置；返回服务实例。
     */
    @Autowired
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
     * 从暂存文件流式写入对象；参数是文件写入命令；返回对象摘要和位置。
     */
    @Override
    public ObjectStorageResultEntity putFile(ObjectStorageFileCommandEntity command) {
        long start = System.currentTimeMillis();
        checkFileCommand(command);
        MessageDigest digest = sha256Digest();
        try {
            if (useMinio()) {
                putMinioFile(command, digest);
            } else {
                putLocalFile(command, digest);
            }
            long costMs = System.currentTimeMillis() - start;
            AiLog.info(AiLog.oss().upload(command.getBucket(), command.getObjectKey(), command.getSizeBytes(), costMs, true));
            return ObjectStorageResultEntity.builder()
                    .bucket(command.getBucket())
                    .objectKey(command.getObjectKey())
                    .sha256(HexFormat.of().formatHex(digest.digest()))
                    .sizeBytes(command.getSizeBytes())
                    .build();
        } catch (Exception e) {
            long costMs = System.currentTimeMillis() - start;
            AiLog.error(AiLog.oss().error("upload-file", command.getBucket(), command.getObjectKey(), costMs, e));
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
     * 将对象流式下载到受控路径；参数包含根目录、相对目标和字节上限；返回落盘摘要。
     */
    @Override
    public ObjectStorageDownloadResultEntity downloadToFile(ObjectStorageDownloadCommandEntity command) {
        long start = System.currentTimeMillis();
        checkDownloadCommand(command);
        Path target = controlledTarget(command.getTargetRoot(), command.getRelativeTargetPath());
        try {
            prepareControlledParent(command.getTargetRoot(), target);
            if (Files.isSymbolicLink(target)) {
                throw new AppException("OBJECT_STORAGE_PATH_INVALID", "对象下载目标不能是符号链接");
            }
            StreamCopyResult result;
            try (InputStream inputStream = useMinio()
                    ? minioObjectStream(command.getBucket(), command.getObjectKey())
                    : localObjectStream(command.getBucket(), command.getObjectKey())) {
                result = copyAtomically(inputStream, target, command.getMaxBytes());
            }
            long costMs = System.currentTimeMillis() - start;
            AiLog.info(AiLog.oss().download(command.getBucket(), command.getObjectKey(), result.sizeBytes(), costMs, true));
            return ObjectStorageDownloadResultEntity.builder()
                    .bucket(command.getBucket())
                    .objectKey(command.getObjectKey())
                    .targetPath(target)
                    .sha256(result.sha256())
                    .sizeBytes(result.sizeBytes())
                    .build();
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            long costMs = System.currentTimeMillis() - start;
            AiLog.error(AiLog.oss().error("download-file", command.getBucket(), command.getObjectKey(), costMs, e));
            throw new AppException("OBJECT_STORAGE_DOWNLOAD_FAILED", "对象流式下载失败：" + e.getMessage(), e);
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

    @Override
    public boolean objectExists(String bucket, String objectKey) {
        checkLocation(bucket, objectKey);
        try {
            if (useMinio()) {
                minioClient().statObject(StatObjectArgs.builder().bucket(bucket).object(objectKey).build());
                return true;
            }
            return Files.exists(localPath(bucket, objectKey), LinkOption.NOFOLLOW_LINKS);
        } catch (ErrorResponseException e) {
            String code = e.errorResponse() == null ? null : e.errorResponse().code();
            // 只有明确的不存在响应可折叠为 false，其余服务端错误必须暴露。
            if ("NoSuchKey".equals(code) || "NoSuchObject".equals(code) || "NoSuchBucket".equals(code)) {
                return false;
            }
            throw new AppException("OBJECT_STORAGE_STAT_FAILED", "对象存在性检查失败：" + e.getMessage(), e);
        } catch (Exception e) {
            throw new AppException("OBJECT_STORAGE_STAT_FAILED", "对象存在性检查失败：" + e.getMessage(), e);
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

    @Override
    public String ragBucket() {
        return properties.getMinio().getRagBucket();
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

    private void putMinioFile(ObjectStorageFileCommandEntity command, MessageDigest digest) throws Exception {
        MinioClient client = minioClient();
        ensureBucket(client, command.getBucket());
        try (InputStream source = Files.newInputStream(command.getSourcePath());
             DigestInputStream inputStream = new DigestInputStream(source, digest)) {
            client.putObject(PutObjectArgs.builder()
                    .bucket(command.getBucket())
                    .object(command.getObjectKey())
                    .stream(inputStream, command.getSizeBytes(), -1)
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

    /** 打开 MinIO 对象流；参数是桶和对象 Key；返回由调用方关闭的响应流。 */
    private InputStream minioObjectStream(String bucket, String objectKey) throws Exception {
        return minioClient().getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey).build());
    }

    /** 打开本地对象流；参数是桶和对象 Key；返回由调用方关闭的文件流。 */
    private InputStream localObjectStream(String bucket, String objectKey) throws Exception {
        return Files.newInputStream(localPath(bucket, objectKey), StandardOpenOption.READ);
    }

    /**
     * 上传到本地目录；参数是对象命令；无返回值。
     */
    private void putLocal(ObjectStorageCommandEntity command) throws Exception {
        Path target = localPath(command.getBucket(), command.getObjectKey());
        Files.createDirectories(target.getParent());
        Files.write(target, command.getBytes());
    }

    private void putLocalFile(ObjectStorageFileCommandEntity command, MessageDigest digest) throws Exception {
        Path target = localPath(command.getBucket(), command.getObjectKey());
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
        boolean moved = false;
        try (InputStream source = Files.newInputStream(command.getSourcePath());
             DigestInputStream inputStream = new DigestInputStream(source, digest);
             OutputStream outputStream = Files.newOutputStream(temporary, StandardOpenOption.TRUNCATE_EXISTING)) {
            inputStream.transferTo(outputStream);
            outputStream.flush();
            if (Files.size(temporary) != command.getSizeBytes()) {
                throw new AppException("OBJECT_STORAGE_SIZE_MISMATCH", "暂存文件长度在上传期间发生变化");
            }
            try {
                // 同文件系统优先原子替换，避免读方观察到半文件。
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
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
     * 流式复制并原子发布目标；参数是输入流、目标和上限；返回字节数与摘要。
     */
    private StreamCopyResult copyAtomically(InputStream inputStream, Path target, long maxBytes) throws Exception {
        MessageDigest digest = sha256Digest();
        Path temporary = Files.createTempFile(target.getParent(), ".download-", ".tmp");
        boolean moved = false;
        long sizeBytes = 0L;
        try (OutputStream outputStream = Files.newOutputStream(temporary,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                if (read == 0) {
                    continue;
                }
                if (read > maxBytes - sizeBytes) {
                    // 使用减法比较，避免 sizeBytes + read 溢出。
                    throw new AppException("OBJECT_STORAGE_TOO_LARGE", "对象大小超过流式下载上限");
                }
                outputStream.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                sizeBytes += read;
            }
            outputStream.flush();
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            moved = true;
            return new StreamCopyResult(sizeBytes, HexFormat.of().formatHex(digest.digest()));
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    /**
     * 构造受控目标；参数是根目录和相对路径；返回规范化的绝对目标。
     */
    private Path controlledTarget(Path targetRoot, Path relativeTargetPath) {
        if (relativeTargetPath.isAbsolute()) {
            throw new AppException("OBJECT_STORAGE_PATH_INVALID", "对象下载目标必须使用相对路径");
        }
        Path root = targetRoot.toAbsolutePath().normalize();
        Path target = root.resolve(relativeTargetPath).normalize();
        if (target.equals(root) || !target.startsWith(root)) {
            throw new AppException("OBJECT_STORAGE_PATH_INVALID", "对象下载目标越出受控根目录");
        }
        return target;
    }

    /**
     * 校验目标父目录的真实路径；参数是受控根目录和目标；无返回值。
     */
    private void prepareControlledParent(Path targetRoot, Path target) throws Exception {
        Path root = targetRoot.toAbsolutePath().normalize();
        Path parent = target.getParent().toAbsolutePath().normalize();
        if (!parent.startsWith(root)) {
            throw new AppException("OBJECT_STORAGE_PATH_INVALID", "对象下载父目录越出受控根目录");
        }
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new AppException("OBJECT_STORAGE_PATH_INVALID", "对象下载受控根目录不存在或不是目录");
        }
        Path cursor = root;
        if (Files.isSymbolicLink(cursor)) {
            throw new AppException("OBJECT_STORAGE_PATH_INVALID", "对象下载受控根目录不能是符号链接");
        }
        for (Path part : root.relativize(parent)) {
            cursor = cursor.resolve(part);
            if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectory(cursor);
            }
            if (Files.isSymbolicLink(cursor)) {
                throw new AppException("OBJECT_STORAGE_PATH_INVALID", "对象下载父目录不能包含符号链接");
            }
            if (!Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)) {
                throw new AppException("OBJECT_STORAGE_PATH_INVALID", "对象下载父路径不是目录");
            }
        }
        Path realRoot = root.toRealPath();
        Path realParent = parent.toRealPath();
        if (!realParent.startsWith(realRoot)) {
            throw new AppException("OBJECT_STORAGE_PATH_INVALID", "对象下载父目录越出受控根目录");
        }
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
            // 双重检查保证延迟创建且全进程复用同一客户端。
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
            // 建桶操作串行化，避免并发首次上传重复创建。
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

    /** 校验暂存文件位置与调用方声明长度，防止上传期间文件被替换。 */
    private void checkFileCommand(ObjectStorageFileCommandEntity command) {
        if (command == null || command.getBucket() == null || command.getBucket().isBlank()
                || command.getObjectKey() == null || command.getObjectKey().isBlank()
                || command.getSourcePath() == null || command.getSizeBytes() < 0) {
            throw new AppException("OBJECT_STORAGE_PARAM_INVALID", "对象存储文件参数不完整");
        }
        checkLocation(command.getBucket(), command.getObjectKey());
        Path source = command.getSourcePath().toAbsolutePath().normalize();
        try {
            if (!Files.isRegularFile(source) || Files.size(source) != command.getSizeBytes()) {
                throw new AppException("OBJECT_STORAGE_SOURCE_INVALID", "暂存文件不存在或长度不一致");
            }
        } catch (java.io.IOException e) {
            throw new AppException("OBJECT_STORAGE_SOURCE_INVALID", "无法读取暂存文件", e);
        }
    }

    /** 校验流式下载命令；参数是下载命令；无返回值。 */
    private void checkDownloadCommand(ObjectStorageDownloadCommandEntity command) {
        if (command == null || command.getBucket() == null || command.getBucket().isBlank()
                || command.getObjectKey() == null || command.getObjectKey().isBlank()
                || command.getTargetRoot() == null || command.getRelativeTargetPath() == null
                || command.getRelativeTargetPath().toString().isBlank() || command.getMaxBytes() <= 0) {
            throw new AppException("OBJECT_STORAGE_PARAM_INVALID", "对象流式下载参数不完整");
        }
        checkLocation(command.getBucket(), command.getObjectKey());
    }

    /** 创建 SHA-256 摘要器；JVM 缺失标准算法视为不可恢复配置错误。 */
    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    /** 拒绝非法桶名片段和 NUL；本地模式另做规范化路径越界校验。 */
    private void checkLocation(String bucket, String objectKey) {
        if (bucket == null || bucket.isBlank() || objectKey == null || objectKey.isBlank()
                || bucket.contains("/") || bucket.contains("\\") || objectKey.indexOf('\0') >= 0) {
            throw new AppException("OBJECT_STORAGE_PARAM_INVALID", "对象存储位置不合法");
        }
    }

    /** 在分配整对象字节数组前执行大小门禁。 */
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

    /** 流式复制摘要。 */
    private record StreamCopyResult(long sizeBytes, String sha256) {
    }
}
