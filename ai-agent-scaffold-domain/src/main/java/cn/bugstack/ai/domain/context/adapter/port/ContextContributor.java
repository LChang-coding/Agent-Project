package cn.bugstack.ai.domain.context.adapter.port;

import cn.bugstack.ai.domain.context.model.ContextAssembleRequest;
import cn.bugstack.ai.domain.context.model.ContextContribution;
import cn.bugstack.ai.domain.context.model.ContextPolicyProperties;

import java.util.List;

/**
 * 上下文片段贡献接口。
 * <p>RAG 后续实现该接口即可参与上下文组装。</p>
 */
public interface ContextContributor {

    /**
     * 贡献上下文片段；参数是组装请求和策略；返回可注入片段。
     */
    List<ContextContribution> contribute(ContextAssembleRequest request, ContextPolicyProperties properties);
}
