package cn.bugstack.ai.domain.rag.service;

import cn.bugstack.ai.domain.context.model.RagContextEvidence;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 在工作流 DAG 里决定「一个节点能看到哪些 RAG 证据」的传播规则。
 *
 * <p>解决什么问题：工作流是一张有向图，某个节点可能自己做过检索，它的上游节点也可能做过检索。
 * 等到最后要校验回答里的引用是否合法时，必须知道「这条路径上一共合法注入过哪些资料」。
 * 如果图上所有节点的证据都算进来，那些根本没走到的兄弟分支的资料也会被当成合法出处，
 * 模型就能借此编造一个「看起来有据可依」的引用。本类的职责就是只沿祖先链传播证据，做到不多也不少。</p>
 *
 * <p>属于哪一层：领域层（domain）里的纯计算规则。没有 Spring 注解、不注入依赖、不读写数据库，
 * 同样的输入永远得到同样的输出，所以叫「确定性」传播。</p>
 *
 * <p>谁会调用它：工作流执行编排。跑完一个节点后调用 merge，把该节点可见的证据登记进 provenance 表；
 * 全图跑完后调用 terminal，取出终点节点的证据，交给引用校验器当白名单。</p>
 *
 * <p>它向下调用什么：什么都不调用，只对传进来的集合做合并与摊平。</p>
 *
 * <p>它不负责什么：不做检索、不判断知识库可见性、不校验文档版本与 generation、不判断引用真假，
 * 也不维护 provenance 这张表（表由调用方持有并逐节点写入）。</p>
 */
public final class RagWorkflowEvidenceLineage {

    /**
     * 算出「本节点执行完之后，它自己以及它的下游能看到的全部证据」。
     *
     * <p>业务职责：把直接上游各节点已经积累好的证据，和本节点这一次检索到的证据拼在一起，
     * 形成本节点的可见证据集合，供后续节点继续往下传播。</p>
     *
     * <p>关键输入：upstreamNodeIds 是本节点的直接前驱节点编号；provenance 是已经算好的「节点 → 该节点可见证据」表；
     * localEvidence 是本节点这次检索真正注入模型的证据。</p>
     *
     * <p>返回结果：不可修改的证据列表，顺序稳定（先按上游顺序，再放本节点），
     * 所以同一张图重复执行会得到完全一致的结果，方便复现和比对。</p>
     *
     * <p>不写库、不改状态、不发事件、不调外部服务。上游为 null（起始节点）和本地证据为 null（该节点没做检索）
     * 都不算失败，只是少拼一部分。这里刻意不做去重：同一份资料从两条上游汇聚过来会出现两次，
     * 但它只用于「白名单是否包含」的判断，重复不影响正确性。</p>
     */
    public List<RagContextEvidence> merge(List<String> upstreamNodeIds,
                                          Map<String, List<RagContextEvidence>> provenance,
                                          List<RagContextEvidence> localEvidence) {
        // 先开一个可写容器承接拼接过程，最后再冻结成只读列表对外发布。
        List<RagContextEvidence> result = new ArrayList<>();
        // 第一层：只有确实存在上游节点时才继承祖先证据；起始节点没有上游，整段跳过。
        if (upstreamNodeIds != null) {
            // 第二层：按上游顺序逐个取它已登记的可见证据。上游还没登记过（例如这条分支本次没执行）就当成空，
            // 这样没走到的分支不会把资料带进白名单，模型也就无法引用它其实没读过的文档。
            upstreamNodeIds.forEach(nodeId -> result.addAll(provenance.getOrDefault(nodeId, List.of())));
        }
        // 再追加本节点这一次实际注入模型的证据；节点没做检索时为 null，跳过即可，不影响祖先部分。
        if (localEvidence != null) result.addAll(localEvidence);
        // 冻结成不可变列表返回：证据一旦登记进 provenance，就不允许被后续节点悄悄改写。
        return List.copyOf(result);
    }

    /**
     * 取出整张图跑完后，终点节点这条路径上合法注入过的全部证据。
     *
     * <p>业务职责：工作流的最终回答由终点节点产出，所以只有终点节点及其祖先读过的资料才算合法出处。
     * 这里把若干个终点节点的可见证据摊平成一个列表，作为引用校验白名单的来源。</p>
     *
     * <p>关键输入：terminalNodeIds 是本次执行真正抵达的终点节点；provenance 是逐节点累积好的可见证据表。</p>
     *
     * <p>返回结果：摊平后的证据列表。终点为 null 时返回空列表，表示这次执行没有任何合法出处，
     * 后续引用校验就会把回答里出现的任何引用都判成编造。</p>
     *
     * <p>不写库、不改状态、不调外部服务。</p>
     */
    public List<RagContextEvidence> terminal(List<String> terminalNodeIds,
                                             Map<String, List<RagContextEvidence>> provenance) {
        // 没有终点节点说明这次执行没产出任何可信资料，直接给空白名单；宁可把引用判为无效，也不放宽出处校验。
        if (terminalNodeIds == null) return List.of();
        // 流式摊平：终点节点编号流 → 每个编号换成它的可见证据列表（缺失按空处理，避免空指针）
        // → flatMap 把多个列表拉平成一条证据流 → 收成不可变列表，交给引用校验器当白名单。
        return terminalNodeIds.stream().flatMap(nodeId -> provenance.getOrDefault(nodeId, List.of()).stream())
                .toList();
    }
}
