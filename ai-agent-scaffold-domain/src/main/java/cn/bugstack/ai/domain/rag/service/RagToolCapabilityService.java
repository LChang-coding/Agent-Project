package cn.bugstack.ai.domain.rag.service;

import cn.bugstack.ai.domain.rag.adapter.repository.IRagRepository;
import cn.bugstack.ai.domain.rag.model.entity.RagAgentBindingEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/** 生成当前运行可见的 RAG 能力说明，不参与检索和权限决策。 */
@Service
public class RagToolCapabilityService {

    private final IRagRepository repository;
    private final boolean platformRagEnabled;

    public RagToolCapabilityService(IRagRepository repository) {
        this(repository, true);
    }

    @Autowired
    public RagToolCapabilityService(IRagRepository repository,
                                    @Value("${ai.tools.platform.rag-enabled:true}") boolean platformRagEnabled) {
        this.repository = repository;
        this.platformRagEnabled = platformRagEnabled;
    }

    /** 按服务端冻结的绑定生成模型可读的知识库和停止规则。 */
    public String guidance(String tenantId, String invocationMode, List<String> bindingIds,
                           Boolean nodeEnabled) {
        if (!platformRagEnabled || !"AGENT_TOOL".equalsIgnoreCase(invocationMode)
                || Boolean.FALSE.equals(nodeEnabled)) return "";
        List<String> ids = bindingIds == null ? List.of() : bindingIds;
        StringBuilder result = new StringBuilder();
        result.append("\n\n【当前 RAG 工具能力】\n")
                .append("当前模式为 AGENT_TOOL。需要回答企业内部事实时，优先调用 rag_retrieve；不要把外部网页搜索当作本地知识库检索。\n")
                .append("rag_retrieve 只接受 query 和 maxContextTokens；最多调用 3 次。连续检索仍无可信证据时，停止检索并明确说明知识库没有找到相关信息。\n");
        if (ids.isEmpty()) {
            result.append("当前没有冻结的知识库绑定，禁止声称已经查到企业内部资料。\n");
            return result.toString();
        }
        result.append("当前可用知识库：\n");
        int validBindings = 0;
        for (String bindingId : ids) {
            if (bindingId == null || bindingId.isBlank()) continue;
            var binding = repository.findBinding(tenantId, bindingId);
            if (binding.isPresent()) {
                appendBinding(result, binding.get());
                validBindings++;
            }
        }
        if (validBindings == 0) {
            result.append("当前冻结绑定均已失效，禁止声称已经查到企业内部资料。\n");
        }
        return result.toString();
    }

    private void appendBinding(StringBuilder result, RagAgentBindingEntity binding) {
        String name = repository.findKnowledgeBase(binding.tenantId(), binding.knowledgeBaseId())
                .map(RagKnowledgeBaseEntity::name).orElse(binding.knowledgeBaseId());
        result.append("- ").append(name).append("（绑定ID：").append(binding.bindingId())
                .append("，最大上下文 Token：").append(binding.maxTokens()).append("）\n");
    }
}
