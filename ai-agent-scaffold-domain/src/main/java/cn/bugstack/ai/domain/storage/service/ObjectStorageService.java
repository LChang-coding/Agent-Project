package cn.bugstack.ai.domain.storage.service;

import cn.bugstack.ai.domain.storage.model.entity.ObjectStorageCommandEntity;
import cn.bugstack.ai.domain.storage.model.entity.ObjectStorageDownloadCommandEntity;
import cn.bugstack.ai.domain.storage.model.entity.ObjectStorageDownloadResultEntity;
import cn.bugstack.ai.domain.storage.model.entity.ObjectStorageFileCommandEntity;
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
     * 从受控暂存文件流式写入对象；参数包含已知长度；返回写入摘要和位置。
     */
    ObjectStorageResultEntity putFile(ObjectStorageFileCommandEntity command);

    /**
     * 读取对象；参数是存储桶和对象 Key；返回文件字节内容。
     */
    byte[] getObject(String bucket, String objectKey);

    /**
     * 限量读取对象；参数是桶、对象键和最大字节数；返回对象内容。
     */
    byte[] getObject(String bucket, String objectKey, long maxBytes);

    /**
     * 将对象流式下载到受控路径；参数包含根目录、相对目标和字节上限；返回落盘摘要。
     */
    ObjectStorageDownloadResultEntity downloadToFile(ObjectStorageDownloadCommandEntity command);

    /**
     * 删除对象；参数是桶和对象键；无返回值。
     */
    void deleteObject(String bucket, String objectKey);

    /**
     * 获取 Skill 包默认桶；无参数；返回桶名称。
     */
    String skillBucket();

    /**
     * 获取资产默认桶；无参数；返回桶名称。
     */
    String assetBucket();

    /**
     * 获取 RAG 原始文档默认桶；无参数；返回桶名称。
     */
    String ragBucket();
}
