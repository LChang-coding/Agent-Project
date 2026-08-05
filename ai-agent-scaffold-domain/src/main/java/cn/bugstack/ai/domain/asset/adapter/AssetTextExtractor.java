package cn.bugstack.ai.domain.asset.adapter;

import cn.bugstack.ai.domain.asset.model.AssetParseResultEntity;

/**
 * 「把用户上传的附件读成一段纯文本」这件事的领域出口，让资产服务不必知道 PDF、Word 到底怎么解析。
 *
 * <p>所属层次：领域层（domain）asset 子域的适配器出口。实现在基础设施层
 * （{@code DefaultAssetTextExtractor}，内部用 PDFBox 解析 PDF、POI 解析 DOCX），由 Spring 注入。</p>
 *
 * <p>谁会调用它：{@code AssetService#uploadChatAttachment}，在文件写入对象存储之后、写数据库之前调用一次，
 * 把提取结果连同资产记录一起落库。之后 {@code AssetContextContributor} 组装上下文时直接读库里的文本，
 * 不会再解析一次，所以解析只发生在上传那一刻。</p>
 *
 * <p>它不负责什么：不上传文件、不写数据库、不判断权限，也不做 OCR——图片一律只保存不识别。</p>
 */
public interface AssetTextExtractor {

    /**
     * 尝试把附件内容提取成可以喂给大模型的纯文本。
     *
     * <p>输入是展示文件名（用来兜底判断格式）、浏览器声明的 MIME 类型和文件全部字节。
     * 文件名和 MIME 任一命中白名单就按对应格式解析，这样浏览器 MIME 缺失或写成通用二进制时也能正确处理。</p>
     *
     * <p>返回值里的 parseStatus 是三态而不是布尔：ready 表示拿到文本可以注入上下文，
     * unsupported 表示格式不支持（图片、未知二进制）或没提取到文字，failed 表示解析过程报错。
     * 三种情况都会正常返回，绝不抛异常——因为附件解析失败不应该让整次上传或整轮对话失败，
     * 文件本身仍然要保存下来供用户下载。</p>
     *
     * <p>提取出的文本会被实现端硬截断（当前 6 万字符），防止一个超大文档把模型上下文窗口挤爆。</p>
     */
    AssetParseResultEntity extract(String fileName, String mimeType, byte[] bytes);
}
