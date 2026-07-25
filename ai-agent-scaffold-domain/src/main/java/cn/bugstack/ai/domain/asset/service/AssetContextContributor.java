package cn.bugstack.ai.domain.asset.service;

import cn.bugstack.ai.domain.asset.adapter.IAssetRepository;
import cn.bugstack.ai.domain.asset.model.AssetEntity;
import cn.bugstack.ai.domain.context.adapter.port.ContextContributor;
import cn.bugstack.ai.domain.context.model.ContextAssembleRequest;
import cn.bugstack.ai.domain.context.model.ContextContribution;
import cn.bugstack.ai.domain.context.model.ContextFragmentType;
import cn.bugstack.ai.domain.context.model.ContextPolicyProperties;
import cn.bugstack.ai.domain.context.service.CharacterTokenCounter;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 聊天附件上下文贡献器。
 * <p>只读取 active 用户消息已绑定的 ready 附件，避免取消或引导失效消息污染上下文。</p>
 */
@Service
public class AssetContextContributor implements ContextContributor {

    /** 仓储已统一过滤失效消息、未就绪和越权附件。 */
    private final IAssetRepository repository;

    /** 创建附件贡献器；参数是资产仓储；返回贡献器实例。 */
    public AssetContextContributor(IAssetRepository repository) {
        this.repository = repository;
    }

    /** 贡献附件文本；参数是可信上下文切面和预算；返回附件片段。 */
    @Override
    public List<ContextContribution> contribute(ContextAssembleRequest request, ContextPolicyProperties properties) {
        if (request == null || isBlank(request.getUserId()) || isBlank(request.getSessionId())
                || properties == null || properties.getAttachmentTokens() <= 0
                || properties.getAttachmentCandidateLimit() <= 0
                || properties.getAttachmentMaxContentChars() <= 0) {
            return List.of();
        }
        Integer attachmentVisibleThrough = request.getAttachmentVisibleThroughSequence() == null
                ? request.getVisibleThroughSequence() : request.getAttachmentVisibleThroughSequence();
        List<AssetEntity> assets = repository.queryContextAssets(request.getTenantId(), request.getUserId(),
                request.getSessionId(), request.getCoveredToSequence(), attachmentVisibleThrough,
                properties.getAttachmentCandidateLimit(), properties.getAttachmentMaxContentChars());
        List<String> sections = new ArrayList<>();
        Set<String> contentHashes = new HashSet<>();
        CharacterTokenCounter tokenCounter = new CharacterTokenCounter();
        String containerPrefix = "<attachments>\n";
        String containerSuffix = "\n</attachments>";
        int remainingTokens = properties.getAttachmentTokens()
                - tokenCounter.estimate(containerPrefix + containerSuffix);
        int remainingContentChars = properties.getAttachmentMaxContentChars();
        if (remainingTokens <= 0) return List.of();
        for (AssetEntity asset : assets) {
            if (asset.getExtractedText() == null || asset.getExtractedText().isBlank()) continue;
            String contentKey = isBlank(asset.getSha256()) ? "asset:" + safe(asset.getAssetId()) : "sha256:" + asset.getSha256();
            // 查询按最近消息优先；相同内容只保留最近一次引用，避免重复注入和计费。
            if (!contentHashes.add(contentKey)) continue;
            String prefix = "<attachment asset_id=\"" + safe(asset.getAssetId()) + "\" file_name=\""
                    + safe(asset.getFileName()) + "\">\n";
            String suffix = "\n</attachment>";
            int wrapperTokens = tokenCounter.estimate(prefix + suffix);
            if (remainingTokens <= wrapperTokens) break;
            String boundedText = asset.getExtractedText().substring(0,
                    Math.min(asset.getExtractedText().length(), remainingContentChars));
            String text = truncateToTokens(boundedText, remainingTokens - wrapperTokens, tokenCounter);
            if (text.isBlank()) continue;
            String section = prefix + text + suffix;
            sections.add(section);
            remainingContentChars -= boundedText.length();
            remainingTokens -= tokenCounter.estimate(section);
            if (remainingTokens <= 0 || remainingContentChars <= 0) break;
        }
        if (sections.isEmpty()) return List.of();
        // 仓储按最近消息优先返回，渲染时恢复时间正序，兼顾预算与可读性。
        java.util.Collections.reverse(sections);
        return List.of(ContextContribution.builder().type(ContextFragmentType.ATTACHMENT)
                .content(containerPrefix + String.join("\n", sections) + containerSuffix)
                .source("chat_attachment").build());
    }

    /** 二分截取满足 Token 预算的最长前缀。 */
    private String truncateToTokens(String value, int maxTokens, CharacterTokenCounter tokenCounter) {
        if (maxTokens <= 0 || value == null || value.isBlank()) return "";
        if (tokenCounter.estimate(value) <= maxTokens) return value;
        int low = 0;
        int high = value.length();
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (tokenCounter.estimate(value.substring(0, mid)) <= maxTokens) low = mid;
            else high = mid - 1;
        }
        return value.substring(0, low);
    }

    /** 转义 XML 属性与正文边界字符。 */
    private String safe(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("\"", "&quot;")
                .replace("<", "&lt;").replace(">", "&gt;");
    }

    /** 判断可选上下文字段是否缺失。 */
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
