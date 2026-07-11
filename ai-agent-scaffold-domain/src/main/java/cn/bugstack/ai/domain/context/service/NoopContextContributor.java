package cn.bugstack.ai.domain.context.service;

import cn.bugstack.ai.domain.context.adapter.port.ContextContributor;
import cn.bugstack.ai.domain.context.model.ContextAssembleRequest;
import cn.bugstack.ai.domain.context.model.ContextContribution;
import cn.bugstack.ai.domain.context.model.ContextPolicyProperties;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 空上下文贡献者。
 * <p>为 RAG 预留扩展点，当前阶段不返回召回内容。</p>
 */
@Service
public class NoopContextContributor implements ContextContributor {

    /**
     * 贡献上下文片段；当前无 RAG 实现，返回空集合。
     */
    @Override
    public List<ContextContribution> contribute(ContextAssembleRequest request, ContextPolicyProperties properties) {
        return List.of();
    }
}
