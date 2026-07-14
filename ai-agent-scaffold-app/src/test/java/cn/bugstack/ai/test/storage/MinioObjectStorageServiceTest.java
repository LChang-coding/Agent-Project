package cn.bugstack.ai.test.storage;

import cn.bugstack.ai.infrastructure.storage.MinioObjectStorageService;
import cn.bugstack.ai.infrastructure.storage.ObjectStorageProperties;
import cn.bugstack.ai.types.exception.AppException;
import org.junit.Assert;
import org.junit.Test;

/**
 * 对象存储边界测试。
 */
public class MinioObjectStorageServiceTest {

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
}
