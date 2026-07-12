package cn.bugstack.ai.domain.storage.service;

import cn.bugstack.ai.domain.storage.model.entity.ObjectStorageCommandEntity;
import cn.bugstack.ai.domain.storage.model.entity.ObjectStorageResultEntity;

/**
 * 对象存储服务接口。 minIO
 */
public interface ObjectStorageService {

    /**
     * 写入对象；参数是存储桶、对象 Key 和文件内容；返回写入结果。
     */
    ObjectStorageResultEntity putObject(ObjectStorageCommandEntity command);

    /**
     * 读取对象；参数是存储桶和对象 Key；返回文件字节内容。
     */
    byte[] getObject(String bucket, String objectKey);

    /**
     * 获取 Skill 包默认桶；无参数；返回桶名称。
     */
    String skillBucket();

    /**
     * 获取资产默认桶；无参数；返回桶名称。
     */
    String assetBucket();
}
