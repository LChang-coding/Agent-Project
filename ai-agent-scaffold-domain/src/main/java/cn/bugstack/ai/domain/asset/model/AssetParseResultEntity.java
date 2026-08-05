package cn.bugstack.ai.domain.asset.model;

import lombok.Builder;
import lombok.Data;

/**
 * 附件解析这一步的产出：要么拿到可注入模型的文本，要么说明为什么没拿到。
 *
 * <p>所属层次：领域层（domain）asset 子域的出参模型。</p>
 *
 * <p>谁会创建它：{@code AssetTextExtractor} 的实现（基础设施层）。谁会消费它：
 * {@code AssetService#uploadChatAttachment}，把三个字段直接落进资产记录的
 * parseStatus / extractedText / parseError。</p>
 *
 * <p>为什么解析失败不抛异常而是返回一个「失败态」对象：附件解析只是增值能力，
 * 解析不了也不该让上传失败——文件仍要保存下来供用户下载，只是这次对话模型看不到它的内容。</p>
 */
@Data
@Builder
public class AssetParseResultEntity {
    /**
     * 解析结论，三种取值：ready（拿到文本）、unsupported（格式不支持或没提取到字）、failed（解析报错）。
     *
     * <p>这个值会落库，并决定附件能不能绑定到消息——绑定 SQL 硬性要求 parse_status='ready'，
     * 所以非 ready 的附件用户能看到、能下载，但发消息时会被拒绝携带。</p>
     */
    private String parseStatus;
    /**
     * 提取出来的纯文本，已经去掉 NUL 并硬截断（当前上限 6 万字符）。
     *
     * <p>它是附件唯一能进入模型上下文的形态；组装上下文时还会按 token 预算再裁一次。
     * 截断是必须的，否则一个几百页的文档能把整个模型窗口占满，正常对话历史就全被挤掉了。</p>
     */
    private String extractedText;
    /**
     * 解析没成功时的简短原因，已压平换行并限长（240 字符）。
     *
     * <p>会落库到 parseError 并可能展示给用户，所以刻意只放诊断信息（异常类名或消息），
     * 不放文件正文片段，避免把用户文档内容通过错误提示泄露出去。</p>
     */
    private String errorSummary;
}
