package cn.bugstack.ai.domain.rag.service;

import cn.bugstack.ai.domain.rag.model.entity.RagAnswerCitationValidation;
import cn.bugstack.ai.domain.context.model.RagContextEvidence;
import cn.bugstack.ai.domain.rag.adapter.repository.IRagRepository;
import cn.bugstack.ai.domain.rag.model.entity.RagChunkEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagDocumentEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagDocumentVersionEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseEntity;
import cn.bugstack.ai.domain.rag.model.valobj.RagDocumentStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagDocumentVersionStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagVisibility;
import cn.bugstack.ai.domain.session.model.entity.ChatMessageEntity;
import cn.bugstack.ai.domain.session.service.SessionDomain;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import cn.bugstack.ai.types.exception.AppException;

/**
 * 读取一次回答的引用信息，并在用户点开某条引用时把原文片段安全地取出来。
 *
 * <p>解决什么问题：回答里的引用标记只是一串编号，前端要展示「这句话出自哪份文档的哪一段」，
 * 就必须回到服务端取原文。这条路径极其敏感——如果只凭引用编号就返回正文，
 * 那么任何人拿到别人的编号都能读到别人的私有资料，或者读到一份早就被删掉、改掉的旧内容。
 * 所以这里把「当时记录了什么」和「现在还能不能读」拆成两件事：引用快照来自可信的数据库消息，
 * 而原文必须在当前权限、当前活跃版本、当前索引代次下重新验证一遍才允许返回。</p>
 *
 * <p>属于哪一层：领域层（domain）的查询服务。只读，不写任何数据。</p>
 *
 * <p>谁会调用它：对话控制器（回答结束后取引用列表）和引用详情接口（用户点开某条引用看原文）。</p>
 *
 * <p>它向下调用什么：会话域（校验会话归属、取运行内的消息、取合法消息）、
 * JSON 反序列化器（解析消息元数据里的引用快照）、RAG 仓储（复核知识库、文档、版本、分块的当前状态）。</p>
 *
 * <p>它不负责什么：不产生引用（引用由检索链路生成、由对话服务落库）、不校验模型有没有编造引用
 * （那是 RagAnswerCitationValidator 在回答产出时做的）、不修改消息、不做检索。</p>
 */
@Service
public class RagAnswerCitationMetadataService {
    /**
     * 引用快照在消息元数据里的结构版本号。
     *
     * <p>消息元数据是一个自由格式的 JSON，里面可能存各种东西。只有 schema 字段等于这个值时，
     * 才认为里面的 validation 是平台写的引用快照。这样做的目的是避免把任意元数据误解析成引用，
     * 也为将来结构升级留下并存空间：新版本换新的 schema 值，老消息按老版本解析或直接跳过。</p>
     */
    private static final String SCHEMA = "rag-citations/v1";
    /**
     * 会话域，是「这条消息是不是真的、这个用户能不能看」的唯一可信来源。
     *
     * <p>所有消息都必须经它按租户和用户取出来，绝不允许直接按 messageId 查库，
     * 否则拿到别人的消息编号就能读到别人的对话和引用。</p>
     */
    private final SessionDomain sessionDomain;
    /**
     * JSON 反序列化器，用来把消息元数据里的引用快照还原成对象。
     *
     * <p>解析失败一律当成「这条消息没有引用」，因为元数据可能是旧版本写的、也可能被历史 Bug 写坏，
     * 不能因为一条脏数据让整个查询接口报错。</p>
     */
    private final ObjectMapper objectMapper;
    /**
     * RAG 仓储，用来在返回原文之前实时复核知识库、文档、版本、分块的当前状态。
     *
     * <p>为什么必须实时查而不是信快照：快照记录的是回答产生那一刻的情况。之后文档可能被重新上传（版本变了）、
     * 被删除、知识库可能被改成私有或停用。若直接用快照返回正文，就会把已经无权访问或已经作废的内容发出去。
     * 所有查询都带租户号，跨租户读不到任何东西。</p>
     */
    private final IRagRepository ragRepository;

    /**
     * 由 Spring 注入可信消息源、JSON 反序列化器和 RAG 仓储；三者都是必需依赖。
     */
    public RagAnswerCitationMetadataService(SessionDomain sessionDomain, ObjectMapper objectMapper,
                                            IRagRepository ragRepository) {
        // 保存会话域引用，所有消息读取都必须经它做归属校验。
        this.sessionDomain = sessionDomain;
        // 保存反序列化器引用，用于解析消息元数据里的引用快照。
        this.objectMapper = objectMapper;
        // 保存 RAG 仓储引用，用于返回原文前复核资源的当前状态。
        this.ragRepository = ragRepository;
    }

    /**
     * 取出某一次运行里，助手回答落库时一起保存的引用快照。
     *
     * <p>各层职责：
     * 第一层：先校验这个用户有权访问该会话，越权请求在读到任何消息之前就被挡住；
     * 第二层：取出这次运行产生的全部消息；
     * 第三层：只看助手角色的消息（用户提问和系统消息不会有引用）；
     * 第四层：逐条解析元数据里的引用快照，解析不出来的丢掉；
     * 第五层：返回第一条带引用的助手消息，没有就返回 null。</p>
     *
     * <p>数据流：
     * 可信租户 + 用户 + 会话 + 运行编号
     * → 会话访问权校验
     * → 查这次运行的消息列表
     * → 过滤出助手消息
     * → 解析每条消息的引用快照
     * → 丢掉没有快照的
     * → 返回第一条（消息编号 + 引用快照）</p>
     *
     * <p>返回 null 是正常结果，表示这次回答没走 RAG 或引用快照还没落库；调用方应按「没有引用」处理，而不是报错。</p>
     *
     * <p>只读，不写库、不改状态。</p>
     */
    public AnswerSnapshot queryRunAnswer(String tenantId, String userId, String sessionId, String runId) {
        // 第一层：先确认这个用户确实有权访问该会话。放在最前面，是为了让越权请求在读到任何消息内容之前就失败。
        sessionDomain.assertSessionAccess(tenantId, userId, sessionId, null);
        // 第二层到第五层串在一条流上：按可信身份取出这次运行的全部消息
        // → 只留助手角色的消息（引用只可能挂在回答上）
        // → 把每条消息映射成「消息编号 + 解析出的引用快照」
        // → 丢掉解析不出快照的（老消息、没走 RAG 的回答、元数据被写坏的）
        // → 取第一条作为这次运行的回答快照；一条都没有就返回 null，表示这次回答没有引用。
        return sessionDomain.queryRunMessages(tenantId, userId, sessionId, runId).stream()
                .filter(message -> SessionDomain.ROLE_ASSISTANT.equals(message.getRole()))
                .map(message -> new AnswerSnapshot(message.getMessageId(), parse(message)))
                .filter(snapshot -> snapshot.validation() != null)
                .findFirst().orElse(null);
    }

    /**
     * 从一条数据库消息的元数据里解析出引用快照。
     *
     * <p>业务职责：只认平台自己写的固定结构（schema 版本匹配且带 validation 字段），其余一律当成没有引用。</p>
     *
     * <p>返回 null 的情况：消息为空、元数据为空、结构版本不匹配、缺少 validation 字段、JSON 解析失败。
     * 这些都是正常业务情形（老消息、没走 RAG 的回答），所以刻意不抛异常——否则历史数据会让查询接口整体不可用。</p>
     *
     * <p>只读，不写库。注意这里只解析「当时记录了什么」，完全不做权限和有效性判断；
     * 要拿原文必须再走 resolveSource。</p>
     */
    public RagAnswerCitationValidation parse(ChatMessageEntity message) {
        // 消息为空或没有元数据，说明这条消息压根不可能带引用，直接返回空，省掉一次 JSON 解析。
        if (message == null || message.getMetadata() == null || message.getMetadata().isBlank()) return null;
        // 元数据是历史遗留的自由格式 JSON，可能是任意版本、也可能被写坏；整段包进 try，
        // 保证一条脏数据只影响它自己，不会让整个查询接口报错。
        try {
            // 把元数据解析成 JSON 树，先看结构再决定要不要反序列化成对象，避免结构不符时抛一堆映射异常。
            JsonNode root = objectMapper.readTree(message.getMetadata());
            // 两个条件必须同时成立才认为这是平台写的引用快照：结构版本号完全匹配，且确实带 validation 字段。
            // 用 path 而不是 get 取 schema，是为了字段缺失时也能安全地拿到空串而不是空指针。
            if (!SCHEMA.equals(root.path("schema").asText()) || !root.has("validation")) return null;
            // 结构确认无误，才把 validation 子树反序列化成引用校验结果对象。
            return objectMapper.treeToValue(root.get("validation"), RagAnswerCitationValidation.class);
        // 任何解析异常都吞掉：老版本结构、被截断的 JSON、字段类型变更都会走到这里。
        } catch (Exception ignored) {
            // 统一按「这条消息没有引用」返回，让调用方走无引用分支，而不是把内部解析细节暴露成接口错误。
            return null;
        }
    }

    /**
     * 用户点开某条引用时，在当前权限和当前版本下取出对应的原文片段。
     *
     * <p>各层职责：
     * 第一层：按可信身份取消息，并确认它是一条带合法引用快照的助手消息；引用曾被判定伪造的回答直接拒绝；
     * 第二层：在快照的「实际使用过的引用」里找到这个引用编号；找不到说明编号不属于这条回答；
     * 第三层：逐级实时校验可见性——知识库私有则必须是本人，文档私有则必须是本人；
     * 第四层：把版本和分块实体都查出来；
     * 第五层：用一条长布尔链核对知识库、文档、版本、分块四层的状态与代次是否仍与快照完全一致；
     * 第六层：再核对内容哈希，确认这段文字本身没被改过；
     * 第七层：截断到 1200 字返回，只给定位所需的片段而不是整块原文。</p>
     *
     * <p>数据流：
     * 可信租户 + 用户 + 会话 + 消息编号 + 引用编号
     * → 按可信身份取消息（越权与不存在都在这一步失败）
     * → 解析引用快照并检查回答本身是否可信
     * → 在快照里定位该引用，拿到知识库 / 文档 / 版本 / 分块 / 代次 / 内容哈希
     * → 查知识库并判可见性
     * → 查文档并判可见性
     * → 查版本、查分块
     * → 四层状态与代次一致性校验
     * → 内容哈希校验
     * → 截断正文
     * → 返回引用编号 + 文档信息 + 页码 + 标题路径 + 原文片段</p>
     *
     * <p>为什么所有失败都返回同一个「不可用」错误：不存在、越权、已删除、版本漂移在这里被刻意合并。
     * 若分开返回，攻击者就能靠错误码差异探测出别人有哪些文档、哪些引用编号是真的。</p>
     *
     * <p>只读，不写库、不改状态。会读取分块正文，因此权限校验必须一步不漏。</p>
     */
    public CitationSource resolveSource(String tenantId, String userId, String sessionId,
                                        String messageId, String citationId) {
        // 第一层：按「租户 + 用户 + 会话 + 消息」取消息，归属校验由会话域完成；
        // 绝不允许只凭消息编号查库，否则拿到别人的编号就能读别人的对话。
        ChatMessageEntity message = sessionDomain.queryValidMessage(tenantId, userId, sessionId, messageId);
        // 顺手解析这条消息的引用快照；解析不出来会得到 null，下一步统一判掉。
        RagAnswerCitationValidation validation = parse(message);
        // 四个条件任一成立就拒绝：消息不存在（编号错或不属于本会话）、不是助手回答（用户消息不会有引用）、
        // 没有引用快照（没走 RAG 或元数据不可解析）、快照状态是「存在伪造引用」。
        // 最后一条尤其重要：既然那次回答已经被判定编造过出处，它列出的引用整体都不能再作为可信入口。
        if (message == null || !SessionDomain.ROLE_ASSISTANT.equals(message.getRole()) || validation == null
                || validation.status() == RagAnswerCitationValidation.Status.INVALID_CITATIONS) {
            // 统一按「引用不存在、已失效或无权访问」中断，不透露到底是哪一种原因。
            throw unavailable();
        }
        // 第二层：只在「实际被回答使用过的引用」里找。用可用白名单之外的编号一律找不到，
        // 找不到就直接抛不可用——这样即使有人猜到了真实的分块编号，也没法凭它读到内容。
        RagContextEvidence.CitationReference reference = validation.usedCitations().stream()
                .filter(value -> value.citationId().equals(citationId)).findFirst().orElseThrow(this::unavailable);
        // 第四层的第一步：按租户查知识库。查询带租户号，跨租户查不到；查不到直接抛不可用。
        RagKnowledgeBaseEntity knowledgeBase = ragRepository.findKnowledgeBase(tenantId, reference.knowledgeBaseId())
                .orElseThrow(this::unavailable);
        // 第三层：私有知识库只有拥有者本人能读。这里不能信快照，因为库的可见性随时可能被改成私有，
        // 一旦改了，之前能看到引用的其他人必须立刻读不到原文。
        if (knowledgeBase.visibility() == RagVisibility.PRIVATE && !knowledgeBase.ownerUserId().equals(userId)) {
            // 越权访问按不可用中断，和「不存在」用同一个错误码，避免暴露这个知识库确实存在。
            throw unavailable();
        }
        // 再按租户查文档；同样带租户号，查不到即抛不可用。
        RagDocumentEntity document = ragRepository.findDocument(tenantId, reference.documentId())
                .orElseThrow(this::unavailable);
        // 文档级可见性单独判一次：知识库共享但某份文档设为私有的情况完全可能存在，
        // 所以两级可见性必须各判一次，不能只看知识库。
        if (document.visibility() == RagVisibility.PRIVATE && !document.ownerUserId().equals(userId)) {
            // 文档越权同样按不可用中断。
            throw unavailable();
        }
        // 查快照记录的那个文档版本实体，后面用它核对版本号与代次是否还是当时那一版。
        RagDocumentVersionEntity version = ragRepository.findDocumentVersion(tenantId, reference.versionId())
                .orElseThrow(this::unavailable);
        // 按分块编号批量接口查单个分块，再在返回结果里按编号精确定位。
        // 走批量接口是为了复用仓储的租户过滤与批次限制逻辑；定位不到就抛不可用。
        RagChunkEntity chunk = ragRepository.listChunksByIds(tenantId, java.util.List.of(reference.chunkId())).stream()
                .filter(value -> value.chunkId().equals(reference.chunkId())).findFirst().orElseThrow(this::unavailable);
        // 第五层：一条长布尔链，把四个层级的状态和版本代次逐项核对，任何一项不满足都会让 current 变成 false，
        // 最终统一按「不可用」拒绝。分组来看这条链在校验什么：
        // 【知识库组】库当前状态必须允许检索（删除中、停用、正在建索引都不行），
        //   且库的当前可见代次必须仍等于快照记录的代次——代次一变说明索引已经重建过，旧引用指向的内容已经作废；
        // 【文档组】文档必须是就绪状态、必须仍属于快照里那个知识库、快照里的版本必须仍是文档当前的活跃版本、
        //   文档的活跃代次也要对得上——文档被重新上传后活跃版本会切走，旧版本的引用就不该再能读；
        // 【版本组】版本必须就绪，且它记录的知识库、文档、版本号、代次都要与快照完全一致，
        //   防止版本记录被改挂到别的文档下面；
        // 【分块组】分块记录的知识库、文档、版本、版本号、代次都要一致，
        //   并且分块的可见性和拥有者必须与文档一致——否则一份被改成私有的文档，它的分块若还留着旧的共享标记，
        //   就会成为绕过可见性判断的漏洞。
        // 任一项不满足的后果都一样：判定为版本漂移或越权，统一返回不可用，绝不返回可能已经过期或不该看到的正文。
        boolean current = knowledgeBase.status().searchable()
                && knowledgeBase.currentGeneration() == reference.generation()
                && document.status() == RagDocumentStatus.READY
                && document.knowledgeBaseId().equals(reference.knowledgeBaseId())
                && reference.versionId().equals(document.activeVersionId())
                && document.activeGeneration() == reference.generation()
                && version.status() == RagDocumentVersionStatus.READY
                && version.knowledgeBaseId().equals(reference.knowledgeBaseId())
                && version.documentId().equals(reference.documentId())
                && version.versionNumber() == reference.documentVersion()
                && version.generation() == reference.generation()
                && chunk.knowledgeBaseId().equals(reference.knowledgeBaseId())
                && chunk.documentId().equals(reference.documentId())
                && chunk.versionId().equals(reference.versionId())
                && chunk.versionNumber() == reference.documentVersion()
                && chunk.generation() == reference.generation()
                && chunk.visibility() == document.visibility()
                && chunk.ownerUserId().equals(document.ownerUserId());
        // 第六层：最后再比一次内容哈希。前面所有校验都通过，只能证明「还是这一块」，
        // 而哈希证明的是「这一块的文字一个字都没变」。分块正文若被任何补偿、迁移或修复流程改动过，
        // 哈希就对不上；这时返回原文会让用户看到与当初回答依据不同的内容，属于必须拒绝的情形。
        current = current && chunk.contentHash().equals(reference.contentHash());
        // 只要有一项没对上就统一按不可用返回，不区分是失效、漂移还是越权。
        if (!current) throw unavailable();
        // 第七层：正文最多返回 1200 字。引用面板只需要够用户确认出处的片段，
        // 全量返回既浪费带宽，也等于把整块资料原文开放出去。
        String excerpt = chunk.content().length() <= 1_200 ? chunk.content() : chunk.content().substring(0, 1_200);
        // 组装对外结果：引用编号、文档编号与文档名、版本号、页码、标题路径都取自快照（它们是当时的定位信息），
        // 正文取自刚刚校验过的当前分块。这样前端既能显示准确出处，也不会拿到超出权限的内容。
        return new CitationSource(reference.citationId(), reference.documentId(), reference.documentName(),
                reference.documentVersion(), reference.pageNumber(), reference.headingPath(), excerpt);
    }

    /**
     * 生成统一的「引用不可用」异常。
     *
     * <p>不存在、已删除、版本漂移、内容被改、越权访问，全部收敛成同一个错误码。
     * 分开返回会让攻击者靠错误码差异逐个探测别人有哪些文档、哪些引用编号真实存在。</p>
     */
    private AppException unavailable() {
        // 只构造异常返回，由各个校验点自行抛出，保证所有拒绝路径对外表现完全一致。
        return new AppException("RAG_CITATION_UNAVAILABLE", "引用不存在、已失效或无权访问");
    }

    /**
     * 一次回答的引用快照：这条回答的消息编号，以及它落库时保存的引用校验结果。
     *
     * <p>消息编号必须一起带出来，因为用户点开某条引用查原文时要拿它回来定位消息；
     * 只给引用编号是无法安全定位的。</p>
     *
     * <p>不可变值对象，不涉及持久化。</p>
     */
    public record AnswerSnapshot(String messageId, RagAnswerCitationValidation validation) { }

    /**
     * 一条通过了实时权限与版本校验的引用来源，直接用于前端展示。
     *
     * <p>只包含可以给用户看的信息：引用编号、文档编号与名称、版本号、页码、标题路径、截断后的原文片段。
     * 刻意不包含知识库编号、分块编号、内容哈希、对象存储位置这些内部标识，避免把内部结构暴露给前端。</p>
     *
     * <p>不可变值对象，不涉及持久化。</p>
     */
    public record CitationSource(String citationId, String documentId, String documentName,
                                 int documentVersion, Integer pageNumber, String headingPath, String excerpt) { }
}
