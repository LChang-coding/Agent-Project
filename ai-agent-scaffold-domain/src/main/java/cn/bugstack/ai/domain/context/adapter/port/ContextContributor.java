package cn.bugstack.ai.domain.context.adapter.port;

import cn.bugstack.ai.domain.context.model.ContextAssembleRequest;
import cn.bugstack.ai.domain.context.model.ContextContribution;
import cn.bugstack.ai.domain.context.model.ContextPolicyProperties;

import java.util.List;

/**
 * 上下文片段贡献接口。
 * <p>附件与 RAG 实现该接口，并由统一预算组装器决定是否注入。</p>
 */
public interface ContextContributor {

    /**
     * 贡献上下文片段。
     */
    List<ContextContribution> contribute(ContextAssembleRequest request, ContextPolicyProperties properties);
}
