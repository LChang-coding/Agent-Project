package cn.bugstack.ai.infrastructure.storage;

import cn.bugstack.ai.domain.storage.model.entity.ObjectStorageCommandEntity;
import cn.bugstack.ai.domain.storage.model.entity.ObjectStorageFileCommandEntity;
import cn.bugstack.ai.domain.storage.model.entity.ObjectStorageResultEntity;
import cn.bugstack.ai.types.exception.AppException;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
        Assert.assertEquals(Long.valueOf(content.length), result.getSizeBytes());
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
}
