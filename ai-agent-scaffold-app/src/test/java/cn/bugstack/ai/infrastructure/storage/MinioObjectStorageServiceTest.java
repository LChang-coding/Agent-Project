package cn.bugstack.ai.infrastructure.storage;

import cn.bugstack.ai.domain.storage.model.entity.ObjectStorageCommandEntity;
import cn.bugstack.ai.domain.storage.model.entity.ObjectStorageDownloadCommandEntity;
import cn.bugstack.ai.domain.storage.model.entity.ObjectStorageDownloadResultEntity;
import cn.bugstack.ai.domain.storage.model.entity.ObjectStorageFileCommandEntity;
import cn.bugstack.ai.domain.storage.model.entity.ObjectStorageResultEntity;
import cn.bugstack.ai.types.exception.AppException;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import okhttp3.Headers;
import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对象存储边界与资源复用测试。
 */
public class MinioObjectStorageServiceTest {

    @Test
    public void shouldStreamStagedFileToLocalStorageAndComputeDigest() throws Exception {
        Path root = Files.createTempDirectory("object-storage-root-");
        Path source = Files.createTempFile("object-storage-source-", ".md");
        byte[] content = "# RAG 流式上传".getBytes(StandardCharsets.UTF_8);
        Files.write(source, content);
        ObjectStorageProperties properties = new ObjectStorageProperties();
        properties.setType("local");
        properties.setLocalRoot(root.toString());
        MinioObjectStorageService service = new MinioObjectStorageService(properties);

        ObjectStorageResultEntity result = service.putFile(ObjectStorageFileCommandEntity.builder()
                .bucket("rag-documents")
                .objectKey("tenant/kb/document.md")
                .sourcePath(source)
                .sizeBytes(content.length)
                .contentType("text/markdown")
                .build());

        Assert.assertArrayEquals(content, Files.readAllBytes(root.resolve("rag-documents/tenant/kb/document.md")));
        Assert.assertEquals((long) content.length, result.getSizeBytes().longValue());
        Assert.assertEquals("84ae09c78e91026567cdfec38ee51f975844b1ac5851dfff5fe50b9826b84dc0", result.getSha256());
    }

    @Test
    public void shouldRejectChangedStagedFileLength() throws Exception {
        Path source = Files.createTempFile("object-storage-source-", ".pdf");
        Files.write(source, "%PDF-1.7".getBytes(StandardCharsets.UTF_8));
        ObjectStorageProperties properties = new ObjectStorageProperties();
        properties.setType("local");
        properties.setLocalRoot(Files.createTempDirectory("object-storage-root-").toString());
        MinioObjectStorageService service = new MinioObjectStorageService(properties);

        try {
            service.putFile(ObjectStorageFileCommandEntity.builder()
                    .bucket("rag-documents")
                    .objectKey("document.pdf")
                    .sourcePath(source)
                    .sizeBytes(1)
                    .contentType("application/pdf")
                    .build());
            Assert.fail("长度发生变化的暂存文件必须被拒绝");
        } catch (AppException e) {
            Assert.assertEquals("OBJECT_STORAGE_SOURCE_INVALID", e.getCode());
        }
    }

    @Test
    public void shouldRejectLocalPathTraversal() {
        ObjectStorageProperties properties = new ObjectStorageProperties();
        properties.setType("local");
        properties.setLocalRoot(System.getProperty("java.io.tmpdir") + "/object-storage-test");
        MinioObjectStorageService service = new MinioObjectStorageService(properties);
        try {
            service.getObject("assets", "../../outside.txt", 1024);
            Assert.fail("路径越界必须被拒绝");
        } catch (AppException e) {
            Assert.assertTrue(e.getInfo().contains("对象"));
        }
    }

    @Test
    public void shouldVerifyLocalObjectDeletion() throws Exception {
        Path root = Files.createTempDirectory("object-storage-root-");
        Path object = root.resolve("rag-documents/tenant/document.md");
        Files.createDirectories(object.getParent());
        Files.writeString(object, "sensitive-content", StandardCharsets.UTF_8);
        ObjectStorageProperties properties = new ObjectStorageProperties();
        properties.setType("local");
        properties.setLocalRoot(root.toString());
        MinioObjectStorageService service = new MinioObjectStorageService(properties);

        Assert.assertTrue(service.objectExists("rag-documents", "tenant/document.md"));
        service.deleteObject("rag-documents", "tenant/document.md");

        Assert.assertFalse(service.objectExists("rag-documents", "tenant/document.md"));
        Assert.assertFalse(Files.exists(object));
    }

    @Test
    public void shouldStreamLocalObjectToControlledTargetAndReturnDigest() throws Exception {
        Path storageRoot = Files.createTempDirectory("object-storage-root-");
        Path targetRoot = Files.createTempDirectory("object-download-root-");
        byte[] content = "streamed-object".getBytes(StandardCharsets.UTF_8);
        Path storedObject = storageRoot.resolve("rag-documents/tenant/document.md");
        Files.createDirectories(storedObject.getParent());
        Files.write(storedObject, content);
        ObjectStorageProperties properties = new ObjectStorageProperties();
        properties.setType("local");
        properties.setLocalRoot(storageRoot.toString());
        MinioObjectStorageService service = new MinioObjectStorageService(properties);

        ObjectStorageDownloadResultEntity result = service.downloadToFile(downloadCommand(
                targetRoot, Path.of("tenant/document.md"), content.length));

        Path expectedTarget = targetRoot.resolve("tenant/document.md").toAbsolutePath().normalize();
        Assert.assertEquals(expectedTarget, result.getTargetPath());
        Assert.assertEquals((long) content.length, result.getSizeBytes());
        Assert.assertEquals(sha256(content), result.getSha256());
        Assert.assertArrayEquals(content, Files.readAllBytes(expectedTarget));
        assertNoDownloadTemporaryFiles(expectedTarget.getParent());
    }

    @Test
    public void shouldKeepOldTargetAndCleanTemporaryFileWhenLocalObjectExceedsLimit() throws Exception {
        Path storageRoot = Files.createTempDirectory("object-storage-root-");
        Path targetRoot = Files.createTempDirectory("object-download-root-");
        byte[] content = "content-over-limit".getBytes(StandardCharsets.UTF_8);
        Path storedObject = storageRoot.resolve("rag-documents/tenant/document.md");
        Files.createDirectories(storedObject.getParent());
        Files.write(storedObject, content);
        Path target = targetRoot.resolve("tenant/document.md");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "old-content", StandardCharsets.UTF_8);
        ObjectStorageProperties properties = new ObjectStorageProperties();
        properties.setType("local");
        properties.setLocalRoot(storageRoot.toString());
        MinioObjectStorageService service = new MinioObjectStorageService(properties);

        try {
            service.downloadToFile(downloadCommand(targetRoot, Path.of("tenant/document.md"), 4));
            Assert.fail("超出字节上限的对象必须被拒绝");
        } catch (AppException e) {
            Assert.assertEquals("OBJECT_STORAGE_TOO_LARGE", e.getCode());
        }

        Assert.assertEquals("old-content", Files.readString(target, StandardCharsets.UTF_8));
        assertNoDownloadTemporaryFiles(target.getParent());
    }

    @Test
    public void shouldRejectDownloadTargetTraversalAndAbsolutePath() throws Exception {
        Path targetRoot = Files.createTempDirectory("object-download-root-");
        ObjectStorageProperties properties = new ObjectStorageProperties();
        properties.setType("local");
        properties.setLocalRoot(Files.createTempDirectory("object-storage-root-").toString());
        MinioObjectStorageService service = new MinioObjectStorageService(properties);

        assertInvalidDownloadPath(service, downloadCommand(targetRoot, Path.of("../../outside.txt"), 1024));
        assertInvalidDownloadPath(service, downloadCommand(targetRoot, targetRoot.resolve("absolute.txt"), 1024));
        Assert.assertFalse(Files.exists(targetRoot.getParent().resolve("outside.txt")));
    }

    @Test
    public void shouldRejectSymbolicLinkInDownloadParent() throws Exception {
        Path storageRoot = Files.createTempDirectory("object-storage-root-");
        Path targetRoot = Files.createTempDirectory("object-download-root-");
        Path outside = Files.createTempDirectory("object-download-outside-");
        Files.createSymbolicLink(targetRoot.resolve("linked"), outside);
        ObjectStorageProperties properties = new ObjectStorageProperties();
        properties.setType("local");
        properties.setLocalRoot(storageRoot.toString());
        MinioObjectStorageService service = new MinioObjectStorageService(properties);

        assertInvalidDownloadPath(service, downloadCommand(targetRoot, Path.of("linked/document.md"), 1024));
        Assert.assertFalse(Files.exists(outside.resolve("document.md")));
    }

    @Test
    public void shouldStreamMinioResponseDirectlyToTarget() throws Exception {
        byte[] content = "minio-response-stream".getBytes(StandardCharsets.UTF_8);
        MinioClient client = mock(MinioClient.class);
        when(client.getObject(any(GetObjectArgs.class))).thenReturn(new GetObjectResponse(
                Headers.of(), "rag-documents", "cn-test", "tenant/document.md",
                new ByteArrayInputStream(content)));
        MinioObjectStorageService service = new MinioObjectStorageService(minioProperties(), () -> client);
        Path targetRoot = Files.createTempDirectory("object-download-root-");

        ObjectStorageDownloadResultEntity result = service.downloadToFile(downloadCommand(
                targetRoot, Path.of("tenant/document.md"), content.length));

        Assert.assertArrayEquals(content, Files.readAllBytes(result.getTargetPath()));
        Assert.assertEquals(sha256(content), result.getSha256());
        Assert.assertEquals((long) content.length, result.getSizeBytes());
        verify(client, times(1)).getObject(any(GetObjectArgs.class));
        assertNoDownloadTemporaryFiles(result.getTargetPath().getParent());
    }

    @Test
    public void shouldReuseClientAndExistingBucketCheckUnderConcurrency() throws Exception {
        MinioClient client = mock(MinioClient.class);
        when(client.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
        AtomicInteger clientBuilds = new AtomicInteger();
        MinioObjectStorageService service = new MinioObjectStorageService(minioProperties(), () -> {
            clientBuilds.incrementAndGet();
            return client;
        });

        int uploadCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(uploadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < uploadCount; i++) {
                String objectKey = "concurrent-" + i + ".txt";
                futures.add(executor.submit(() -> {
                    start.await();
                    service.putObject(command(objectKey));
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }

        Assert.assertEquals(1, clientBuilds.get());
        verify(client, times(1)).bucketExists(any(BucketExistsArgs.class));
        verify(client, times(uploadCount)).putObject(any(PutObjectArgs.class));
    }

    @Test
    public void shouldCacheBucketOnlyAfterSuccessfulCreation() throws Exception {
        MinioClient client = mock(MinioClient.class);
        when(client.bucketExists(any(BucketExistsArgs.class))).thenReturn(false);
        MinioObjectStorageService service = new MinioObjectStorageService(minioProperties(), () -> client);

        service.putObject(command("first.txt"));
        service.putObject(command("second.txt"));

        verify(client, times(1)).bucketExists(any(BucketExistsArgs.class));
        verify(client, times(1)).makeBucket(any(MakeBucketArgs.class));
    }

    @Test
    public void shouldRetryBucketCheckAfterFailure() throws Exception {
        MinioClient client = mock(MinioClient.class);
        when(client.bucketExists(any(BucketExistsArgs.class)))
                .thenThrow(new IOException("temporary failure"))
                .thenReturn(true);
        MinioObjectStorageService service = new MinioObjectStorageService(minioProperties(), () -> client);

        try {
            service.putObject(command("first.txt"));
            Assert.fail("失败的桶检查必须向上报错");
        } catch (AppException expected) {
            Assert.assertTrue(expected.getInfo().contains("对象上传失败"));
        }
        service.putObject(command("second.txt"));

        verify(client, times(2)).bucketExists(any(BucketExistsArgs.class));
        verify(client, times(1)).putObject(any(PutObjectArgs.class));
    }

    private ObjectStorageProperties minioProperties() {
        ObjectStorageProperties properties = new ObjectStorageProperties();
        properties.setType("minio");
        return properties;
    }

    private ObjectStorageCommandEntity command(String objectKey) {
        return ObjectStorageCommandEntity.builder()
                .bucket("assets")
                .objectKey(objectKey)
                .contentType("text/plain")
                .bytes("content".getBytes(StandardCharsets.UTF_8))
                .build();
    }

    private ObjectStorageDownloadCommandEntity downloadCommand(Path targetRoot, Path relativeTarget, long maxBytes) {
        return ObjectStorageDownloadCommandEntity.builder()
                .bucket("rag-documents")
                .objectKey("tenant/document.md")
                .targetRoot(targetRoot)
                .relativeTargetPath(relativeTarget)
                .maxBytes(maxBytes)
                .build();
    }

    private void assertInvalidDownloadPath(MinioObjectStorageService service,
                                           ObjectStorageDownloadCommandEntity command) {
        try {
            service.downloadToFile(command);
            Assert.fail("非受控下载路径必须被拒绝");
        } catch (AppException e) {
            Assert.assertEquals("OBJECT_STORAGE_PATH_INVALID", e.getCode());
        }
    }

    private String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    private void assertNoDownloadTemporaryFiles(Path directory) throws Exception {
        try (java.util.stream.Stream<Path> paths = Files.list(directory)) {
            Assert.assertFalse(paths.anyMatch(path -> path.getFileName().toString().startsWith(".download-")));
        }
    }
}
