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
import java.util.List;

/**
 * 聊天附件上下文贡献器。
 * <p>只读取 active 用户消息已绑定的 ready 附件，避免取消或引导失效消息污染上下文。</p>
 */
@Service
public class AssetContextContributor implements ContextContributor {

    private final IAssetRepository repository;

    /** 创建附件贡献器；参数是资产仓储；返回贡献器实例。 */
    public AssetContextContributor(IAssetRepository repository) {
        this.repository = repository;
    }

    /** 贡献附件文本；参数是可信上下文切面和预算；返回附件片段。 */
    @Override
    public List<ContextContribution> contribute(ContextAssembleRequest request, ContextPolicyProperties properties) {
        if (request == null || isBlank(request.getUserId()) || isBlank(request.getSessionId())
                || properties == null || properties.getAttachmentTokens() <= 0) {
            return List.of();
        }
        List<AssetEntity> assets = repository.queryContextAssets(request.getTenantId(), request.getUserId(),
                request.getSessionId(), request.getVisibleThroughSequence());
        List<String> sections = new ArrayList<>();
        CharacterTokenCounter tokenCounter = new CharacterTokenCounter();
        int remainingTokens = properties.getAttachmentTokens();
        for (AssetEntity asset : assets) {
            if (asset.getExtractedText() == null || asset.getExtractedText().isBlank()) continue;
            String prefix = "<attachment asset_id=\"" + safe(asset.getAssetId()) + "\" file_name=\""
                    + safe(asset.getFileName()) + "\">\n";
            String suffix = "\n</attachment>";
            int wrapperTokens = tokenCounter.estimate(prefix + suffix);
            if (remainingTokens <= wrapperTokens) break;
            String text = truncateToTokens(asset.getExtractedText(), remainingTokens - wrapperTokens, tokenCounter);
            if (text.isBlank()) continue;
            String section = prefix + text + suffix;
            sections.add(section);
            remainingTokens -= tokenCounter.estimate(section);
            if (remainingTokens <= 0) break;
        }
        if (sections.isEmpty()) return List.of();
        // 仓储按最近消息优先返回，渲染时恢复时间正序，兼顾预算与可读性。
        java.util.Collections.reverse(sections);
        return List.of(ContextContribution.builder().type(ContextFragmentType.ATTACHMENT)
                .content("<attachments>\n" + String.join("\n", sections) + "\n</attachments>")
                .source("chat_attachment").build());
    }

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

    private String safe(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("\"", "&quot;")
                .replace("<", "&lt;").replace(">", "&gt;");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
