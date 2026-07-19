package cn.bugstack.ai.domain.rag.service;

import cn.bugstack.ai.domain.context.model.RagContextEvidence;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 工作流 RAG 证据沿 DAG 祖先链的确定性传播规则。 */
public final class RagWorkflowEvidenceLineage {

    /** 合并直接上游祖先证据与本节点证据。 */
    public List<RagContextEvidence> merge(List<String> upstreamNodeIds,
                                          Map<String, List<RagContextEvidence>> provenance,
                                          List<RagContextEvidence> localEvidence) {
        List<RagContextEvidence> result = new ArrayList<>();
        if (upstreamNodeIds != null) {
            upstreamNodeIds.forEach(nodeId -> result.addAll(provenance.getOrDefault(nodeId, List.of())));
        }
        if (localEvidence != null) result.addAll(localEvidence);
        return List.copyOf(result);
    }

    /** 仅汇总终点节点及其已传播祖先证据，排除无关兄弟分支。 */
    public List<RagContextEvidence> terminal(List<String> terminalNodeIds,
                                             Map<String, List<RagContextEvidence>> provenance) {
        if (terminalNodeIds == null) return List.of();
        return terminalNodeIds.stream().flatMap(nodeId -> provenance.getOrDefault(nodeId, List.of()).stream())
                .toList();
    }
}
