package cn.bugstack.ai.domain.rag.service;

import cn.bugstack.ai.domain.context.adapter.port.ContextContributor;
import cn.bugstack.ai.domain.context.model.ContextAssembleRequest;
import cn.bugstack.ai.domain.context.model.ContextContribution;
import cn.bugstack.ai.domain.context.model.ContextFragmentType;
import cn.bugstack.ai.domain.context.model.ContextPolicyProperties;
import cn.bugstack.ai.domain.rag.model.entity.RagRetrievalRequest;
import cn.bugstack.ai.domain.rag.model.entity.RagRetrievalResult;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 把 RAG 检索到的资料，拼成一段带引用编号、且明确声明「无指令权限」的文本，交给统一上下文组装器。
 *
 * <p>解决什么问题：上下文里同时有系统提示、历史对话、工具结果和外部资料，谁占多少 Token 必须统一调度。
 * 本类是 RAG 这一路的「投稿人」：只有当预算允许、运行目标可信、问题非空时才投稿；
 * 投稿内容还必须做两层防护——用 XML 标签把资料围起来并转义元字符（防止文档正文伪造标签逃逸出资料区），
 * 以及显式写明资料里的命令、角色设定、工具调用要求都无效（防止提示注入把资料变成新的系统指令）。</p>
 *
 * <p>属于哪一层：领域层（domain），实现上下文层定义的投稿端口 ContextContributor。</p>
 *
 * <p>谁会调用它：统一上下文组装器（Context Manager）在每轮对话拼上下文时，会遍历所有投稿人并调到这里。</p>
 *
 * <p>它向下调用什么：唯一依赖是线上检索服务，由它完成绑定解析、可见性判断、召回、融合、重排和引用组装。</p>
 *
 * <p>它不负责什么：不实现检索算法、不做知识库权限判断、不裁剪 Token（预算由上下文策略给定，超额裁剪在检索服务内完成）、
 * 不调用大模型、不校验模型回答里的引用真假（那是引用校验器的事）。</p>
 */
@Service
public class RagContextContributor implements ContextContributor {

    /**
     * 线上检索服务，是本类唯一的能力来源。
     *
     * <p>刻意复用聊天主链路的同一个实现，不为上下文组装另造一套检索口径；否则调试、评测、线上三处结果会互相对不上。</p>
     */
    private final RagRetrievalService retrievalService;
    private final RagRetrievalPresentationService presentationService;

    /**
     * 由 Spring 注入线上检索服务；没有它本类无法产出任何上下文片段。
     */
    public RagContextContributor(RagRetrievalService retrievalService,
                                 RagRetrievalPresentationService presentationService) {
        // 保存检索服务引用，每次组装上下文都用它跑一次真实召回。
        this.retrievalService = retrievalService;
        this.presentationService = presentationService;
    }

    /**
     * 只有在预算、身份、目标和问题都齐全时，才贡献一个 RAG 上下文片段。
     *
     * <p>各层职责：
     * 第一层：入口守卫，任何一项前置条件缺失就直接不投稿，不浪费一次向量化和检索开销；
     * 第二层：带着可信身份和 Token 预算跑一次真实检索；
     * 第三层：一条都没命中就同样不投稿，避免往上下文里塞一段空壳资料区，白占 Token 还干扰模型；
     * 第四层：把命中结果渲染成带引用编号的受限资料文本；
     * 第五层：同时把「实际注入了哪些引用」压缩成结构化证据一起交出去，作为后续引用校验的白名单。</p>
     *
     * <p>数据流：
     * 上下文组装请求 + 上下文策略（含 RAG Token 预算）
     * → 前置条件校验（预算 / 目标类型 / 目标 ID / 问题 / 租户 / 用户）
     * → 组装检索请求（诊断关闭，带上绑定快照）
     * → 检索服务执行召回并产出引用列表
     * → 无命中则返回空投稿
     * → 渲染成 XML 包裹的受限资料文本
     * → 连同证据白名单、预估 Token 数、检索批次编号打包成一个上下文片段
     * → 返回给上下文组装器参与最终拼装</p>
     *
     * <p>关键输入：properties.getRagTokens() 是这一路允许占用的 Token 预算；request.getRagBindingIds() 是
     * 本轮会话已经确定下来的绑定快照（保证同一轮对话里不会因为管理员中途改绑定而读到不同资料）。</p>
     *
     * <p>返回结果：0 个或 1 个上下文片段。片段里带 source（检索批次编号）和 ragEvidence（引用白名单），
     * 落库和引用校验都以它们为准。</p>
     *
     * <p>会真实读取文档正文，但不写库、不改状态、不发事件。</p>
     */
    @Override
    public List<ContextContribution> contribute(ContextAssembleRequest request,
                                                ContextPolicyProperties properties) {
        // 第一层：入口守卫，六个条件必须同时成立，缺一个就直接放弃投稿——
        // 请求或策略为空：调用方用法有问题，没有任何可靠输入；
        // RAG 预算小于 1：这一轮明确不给 RAG 留 Token，检索出来也塞不进去；
        // 目标类型为空或目标 ID 为空：不知道该按哪个 Agent / 工作流的绑定去找知识库；
        // 问题为空：没有检索意图，向量化只会召回噪声；
        // 租户号或用户号为空：没有可信身份就不能做租户隔离和可见性判断，绝不能放行。
        if (request == null || properties == null || properties.getRagTokens() < 1
                || request.getRagTargetType() == null || blank(request.getRagTargetId())
                || blank(request.getRagQuery()) || blank(request.getTenantId()) || blank(request.getUserId())) {
            // 返回空列表表示「这一路不投稿」。注意这不是异常：没有 RAG 的对话是完全正常的，
            // 上下文组装器会把这份预算让给其他投稿人。
            return List.of();
        }
        // 第二层：把可信身份、会话与运行编号、目标、问题、链路号、Token 预算打包成检索请求跑一次召回。
        // 倒数第二个参数 false 表示关闭诊断（线上不采集候选轨迹，省内存也避免泄露内部细节）；
        // 最后一个参数是本轮已确定的绑定快照，保证同一轮对话内读到的知识库范围稳定不漂移。
        RagRetrievalResult result = retrievalService.retrieve(new RagRetrievalRequest(request.getTenantId(),
                request.getUserId(), request.getSessionId(), request.getRunId(), request.getRagTargetType(),
                request.getRagTargetId(), request.getRagQuery(), request.getTraceId(), properties.getRagTokens(),
                false, request.getRagBindingIds()));
        // 第三层：一条都没召回（没配绑定、知识库不可见、索引为空、全部被过滤掉）就同样不投稿，
        // 免得往上下文里塞一段空的资料区，既浪费 Token 又会让模型以为「有资料但没内容」。
        if (result.citations().isEmpty()) return List.of();
        // 第四层：把命中结果渲染成带引用编号的受限资料文本，这段文本就是最终喂给模型的内容。
        RagRetrievalPresentationService.Presentation presentation = presentationService.present(result);
        // 第五层：打包成一个上下文片段——类型标成 RAG（便于上下文层按类型排序和裁剪）、
        // 带上渲染后的正文、预估 Token 数（供上下文层核算总预算）、检索批次编号作为来源标识，
        // 以及压缩后的证据白名单（后续校验模型引用真假时唯一可信的依据）。
        return List.of(ContextContribution.builder().type(ContextFragmentType.RAG).content(presentation.content())
                .estimatedTokenCount(result.estimatedTokenCount()).source(result.retrievalId())
                .ragEvidence(presentation.evidence()).build());
    }

    /**
     * 统一判断一个运行输入是不是「等于没传」。
     *
     * <p>null 和纯空白都算缺失，防止空格字符串通过入口守卫，导致后面用空租户号或空问题去跑检索。</p>
     */
    private boolean blank(String value) {
        // null 和空白串都判为缺失：这些字段要么用于租户隔离，要么是检索意图本身，含糊的值一律不放行。
        return value == null || value.isBlank();
    }
}
