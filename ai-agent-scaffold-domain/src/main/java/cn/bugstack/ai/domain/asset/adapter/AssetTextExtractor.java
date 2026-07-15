package cn.bugstack.ai.domain.asset.adapter;

import cn.bugstack.ai.domain.asset.model.AssetParseResultEntity;

/**
 * 附件文本提取端口。
 */
public interface AssetTextExtractor {

    /** 解析附件；参数是名称、MIME 和受限字节；返回安全截断后的文本结果。 */
    AssetParseResultEntity extract(String fileName, String mimeType, byte[] bytes);
}
