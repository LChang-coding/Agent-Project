package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.api.dto.session.SessionDeleteResponseDTO;
import cn.bugstack.ai.api.dto.session.SessionListResponseDTO;
import cn.bugstack.ai.api.dto.session.SessionMessagePageResponseDTO;
import cn.bugstack.ai.api.dto.session.SessionMessageResponseDTO;
import cn.bugstack.ai.api.dto.session.SessionSummaryResponseDTO;
import cn.bugstack.ai.api.dto.session.SessionRagSettingRequestDTO;
import cn.bugstack.ai.api.dto.session.SessionRagSettingResponseDTO;
import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.api.dto.RagCitationValidationDTO;
import cn.bugstack.ai.api.dto.RagCitationSourceDTO;
import cn.bugstack.ai.domain.rag.model.entity.RagAnswerCitationValidation;
import cn.bugstack.ai.domain.rag.service.RagAnswerCitationMetadataService;
import cn.bugstack.ai.domain.rag.model.entity.SessionRagSettingEntity;
import cn.bugstack.ai.domain.rag.service.SessionRagSettingService;
import cn.bugstack.ai.domain.session.model.entity.ChatMessageEntity;
import cn.bugstack.ai.domain.session.model.entity.ChatSessionEntity;
import cn.bugstack.ai.domain.session.service.SessionDomain;
import cn.bugstack.ai.domain.session.service.SessionLifecycleService;
import cn.bugstack.ai.types.context.TenantContextHolder;
import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/**
 * 会话历史管理接口。
 * <p>提供数据库会话读取、消息分页和会话软删除。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/sessions")
public class SessionController {

    private static final int DEFAULT_LIMIT = 30;
    private static final int MAX_LIMIT = 100;
    private final SessionDomain sessionDomain;
    private final SessionLifecycleService lifecycleService;
    private final RagAnswerCitationMetadataService citationMetadataService;
    private final SessionRagSettingService ragSettingService;

    /**
     * @param sessionDomain 会话和有效消息查询服务
     * @param lifecycleService 会话软删除服务
     * @param citationMetadataService RAG 引用解析服务
     * @param ragSettingService 会话 RAG 模式和绑定选择服务
     */
    public SessionController(SessionDomain sessionDomain, SessionLifecycleService lifecycleService,
                             RagAnswerCitationMetadataService citationMetadataService,
                             SessionRagSettingService ragSettingService) {
        this.sessionDomain = sessionDomain;
        this.lifecycleService = lifecycleService;
        this.citationMetadataService = citationMetadataService;
        this.ragSettingService = ragSettingService;
    }

    /**
     * 游标分页查询数据库中的当前用户会话。
     *
     * @param cursor 上一页末尾会话时间和ID的编码
     * @param limit 页大小
     * @return 会话摘要、下一游标和是否还有数据
     */
    @GetMapping
    public Response<SessionListResponseDTO> list(@RequestParam(required = false) String cursor,
                                                 @RequestParam(defaultValue = "30") int limit) {
        try {
            int pageSize = normalizeLimit(limit);
            SessionCursor decoded = decodeCursor(cursor);
            // 多取一条判断后续页，避免对高增长会话表执行 count。
            List<ChatSessionEntity> rows = sessionDomain.querySessions(TenantContextHolder.getTenantId(),
                    requireUserId(), decoded.time(), decoded.sessionId(), pageSize + 1);
            boolean hasMore = rows.size() > pageSize;
            List<ChatSessionEntity> page = hasMore ? rows.subList(0, pageSize) : rows;
            String nextCursor = hasMore && !page.isEmpty() ? encodeCursor(page.get(page.size() - 1)) : null;
            return success(SessionListResponseDTO.builder().items(page.stream().map(this::toSummary).toList())
                    .nextCursor(nextCursor).hasMore(hasMore).build());
        } catch (AppException e) {
            return fail(e);
        } catch (Exception e) {
            log.error("查询会话列表失败", e);
            return systemFail();
        }
    }

    /**
     * 查询会话有效消息。
     *
     * @param sessionId 会话ID
     * @param beforeSequence 可选上界序号
     * @param limit 页大小
     * @return 按时间正序返回的数据库消息页
     */
    @GetMapping("/{sessionId}/messages")
    public Response<SessionMessagePageResponseDTO> messages(@PathVariable String sessionId,
                                                            @RequestParam(required = false) Integer beforeSequence,
                                                            @RequestParam(defaultValue = "50") int limit) {
        try {
            int pageSize = normalizeLimit(limit);
            List<ChatMessageEntity> rows = sessionDomain.queryValidMessagesBefore(TenantContextHolder.getTenantId(),
                    requireUserId(), sessionId, beforeSequence, pageSize + 1);
            boolean hasMore = rows.size() > pageSize;
            List<ChatMessageEntity> descending = new ArrayList<>(hasMore ? rows.subList(0, pageSize) : rows);
            Integer nextBefore = hasMore && !descending.isEmpty()
                    ? descending.get(descending.size() - 1).getSequenceNo() : null;
            Collections.reverse(descending);
            return success(SessionMessagePageResponseDTO.builder().sessionId(sessionId)
                    .items(descending.stream().map(this::toMessage).toList())
                    .nextBeforeSequence(nextBefore).hasMore(hasMore).build());
        } catch (AppException e) {
            return fail(e);
        } catch (Exception e) {
            log.error("查询会话消息失败 sessionId:{}", sessionId, e);
            return systemFail();
        }
    }

    /**
     * 软删除会话并停止它继续参与对话。
     *
     * @param sessionId 会话ID
     * @return 删除时间和删除时上下文版本
     */
    @DeleteMapping("/{sessionId}")
    public Response<SessionDeleteResponseDTO> delete(@PathVariable String sessionId) {
        try {
            long revision = lifecycleService.delete(TenantContextHolder.getTenantId(), requireUserId(), sessionId);
            return success(new SessionDeleteResponseDTO(sessionId, revision));
        } catch (AppException e) {
            return fail(e);
        } catch (Exception e) {
            log.error("删除会话失败 sessionId:{}", sessionId, e);
            return systemFail();
        }
    }

    /** 查询会话 RAG 模式、手动选择和当前目标可用绑定。 */
    @GetMapping("/{sessionId}/rag-setting")
    public Response<SessionRagSettingResponseDTO> ragSetting(@PathVariable String sessionId) {
        try {
            return success(toRagSetting(ragSettingService.query(TenantContextHolder.getTenantId(),
                    requireUserId(), sessionId)));
        } catch (AppException exception) {
            return fail(exception);
        } catch (Exception exception) {
            log.error("查询会话RAG设置失败 sessionId:{}", sessionId, exception);
            return systemFail();
        }
    }

    /**
     * 更新会话 RAG 设置。
     * <p>只影响后续新运行；已经创建的 Run 继续使用自身保存的 RAG 快照。</p>
     */
    @PatchMapping("/{sessionId}/rag-setting")
    public Response<SessionRagSettingResponseDTO> updateRagSetting(@PathVariable String sessionId,
                                                                    @RequestBody SessionRagSettingRequestDTO request) {
        try {
            if (request == null) {
                throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "RAG设置不能为空");
            }
            return success(toRagSetting(ragSettingService.update(TenantContextHolder.getTenantId(),
                    requireUserId(), sessionId, request.getMode(), request.getEnabled(),
                    request.getSelectedBindingIds(), request.getExpectedRevision())));
        } catch (AppException exception) {
            return fail(exception);
        } catch (Exception exception) {
            log.error("更新会话RAG设置失败 sessionId:{}", sessionId, exception);
            return systemFail();
        }
    }

    /** 查询一条回答引用当前仍可见的原文；失效文档不会绕过权限重新暴露。 */
    @GetMapping("/{sessionId}/messages/{messageId}/citations/{citationId}")
    public Response<RagCitationSourceDTO> citation(@PathVariable String sessionId,
                                                   @PathVariable String messageId,
                                                   @PathVariable String citationId) {
        try {
            RagAnswerCitationMetadataService.CitationSource source = citationMetadataService.resolveSource(
                    TenantContextHolder.getTenantId(), requireUserId(), sessionId, messageId, citationId);
            return success(RagCitationSourceDTO.builder().citationId(source.citationId())
                    .documentId(source.documentId()).documentName(source.documentName())
                    .documentVersion(source.documentVersion()).pageNumber(source.pageNumber())
                    .headingPath(source.headingPath()).excerpt(source.excerpt()).build());
        } catch (AppException exception) {
            return fail(exception);
        } catch (Exception exception) {
            log.error("查询RAG引用失败 sessionId:{} messageId:{}", sessionId, messageId, exception);
            return systemFail();
        }
    }

    /** 将会话事实转换为列表摘要。 */
    private SessionSummaryResponseDTO toSummary(ChatSessionEntity session) {
        return SessionSummaryResponseDTO.builder().sessionId(session.getSessionId()).agentId(session.getAgentId())
                .agentName(session.getAgentName()).appName(session.getAppName()).title(session.getTitle())
                .sourceType(session.getSourceType()).workflowVersion(session.getWorkflowVersion()).modelCode(session.getModelCode())
                .status(session.getStatus()).ragEnabled(Boolean.TRUE.equals(session.getRagEnabled()))
                .lastMessageTime(session.getLastMessageTime())
                .contextRevision(session.getContextRevision()).build();
    }

    /** 根据模式和绑定可用性生成用户可执行的 RAG 状态说明。 */
    private SessionRagSettingResponseDTO toRagSetting(SessionRagSettingEntity setting) {
        String message;
        if ("OFF".equals(setting.mode().name())) {
            message = "RAG已关闭，本会话不会检索企业知识库";
        } else if ("MANUAL".equals(setting.mode().name()) && !setting.bindingConfigured()) {
            message = "RAG处于手动模式，但已选择的绑定当前不可用";
        } else if (!setting.bindingConfigured()) {
            message = "RAG已开启，但当前运行目标尚未绑定知识库";
        } else if ("MANUAL".equals(setting.mode().name())) {
            message = "RAG已开启，后续新运行将使用本会话手动选择的知识库绑定";
        } else {
            message = "RAG已开启，后续新运行将检索当前目标绑定的知识库";
        }
        List<SessionRagSettingResponseDTO.EligibleBindingDTO> eligibleBindings = setting.eligibleBindings().stream()
                .map(binding -> new SessionRagSettingResponseDTO.EligibleBindingDTO(
                        binding.bindingId(), binding.knowledgeBaseId(), binding.knowledgeBaseName(),
                        binding.retrievalProfileId(), binding.retrievalProfileName(), binding.status(),
                        binding.required(), binding.maxTokens(), binding.priority(), binding.revision(),
                        binding.selected()))
                .toList();
        return new SessionRagSettingResponseDTO(setting.sessionId(), setting.enabled(), setting.mode().name(),
                setting.revision(), setting.bindingConfigured(), setting.targetType().name(), setting.targetId(),
                setting.selectedBindingIds(), eligibleBindings, message);
    }

    /** 转换消息并解析随最终回答保存的引用校验快照。 */
    private SessionMessageResponseDTO toMessage(ChatMessageEntity message) {
        return SessionMessageResponseDTO.builder().messageId(message.getMessageId()).runId(message.getRunId())
                .traceId(message.getTraceId())
                .role(message.getRole()).contentType(message.getContentType()).content(message.getContent())
                .estimatedTokenCount(message.getEstimatedTokenCount()).sequenceNo(message.getSequenceNo())
                .createTime(message.getCreateTime()).citationValidation(toCitationDTO(citationMetadataService.parse(message))).build();
    }

    /** 将领域引用校验结果转换为前端可展示的引用清单。 */
    private RagCitationValidationDTO toCitationDTO(RagAnswerCitationValidation value) {
        if (value == null) return null;
        return RagCitationValidationDTO.builder().status(value.status().name())
                .retrievalIds(value.retrievalIds()).allowedCitationIds(value.allowedCitationIds())
                .usedCitationIds(value.usedCitationIds()).invalidCitationIds(value.invalidCitationIds())
                .citations(value.usedCitations().stream().map(citation -> RagCitationValidationDTO.CitationDTO.builder()
                        .citationId(citation.citationId()).knowledgeBaseId(citation.knowledgeBaseId())
                        .documentId(citation.documentId()).documentName(citation.documentName())
                        .versionId(citation.versionId()).documentVersion(citation.documentVersion())
                        .generation(citation.generation()).chunkId(citation.chunkId())
                        .pageNumber(citation.pageNumber()).headingPath(citation.headingPath()).build()).toList())
                .build();
    }

    /** 将页大小限制在公开接口允许范围内。 */
    private int normalizeLimit(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "limit 必须在 1 到 100 之间");
        }
        return limit == 0 ? DEFAULT_LIMIT : limit;
    }

    /** 读取可信用户身份，认证上下文失效时拒绝所有会话访问。 */
    private String requireUserId() {
        String userId = TenantContextHolder.getUserId();
        if (userId == null || userId.isBlank()) {
            throw new AppException("AUTH_UNAUTHORIZED", "登录身份已失效");
        }
        return userId;
    }

    /** 将稳定排序键编码为不透明 URL 安全游标。 */
    private String encodeCursor(ChatSessionEntity session) {
        String raw = session.getLastMessageTime() + "|" + session.getSessionId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** 解码并校验客户端游标，禁止畸形值进入数据库条件。 */
    private SessionCursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return new SessionCursor(null, null);
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int separator = raw.lastIndexOf('|');
            if (separator < 1 || separator == raw.length() - 1) {
                throw new IllegalArgumentException("cursor");
            }
            return new SessionCursor(LocalDateTime.parse(raw.substring(0, separator)), raw.substring(separator + 1));
        } catch (Exception e) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "cursor 格式不合法");
        }
    }

    /** 构造统一成功响应。 */
    private <T> Response<T> success(T data) {
        return Response.<T>builder().code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo()).data(data).build();
    }

    /** 保留领域业务错误码。 */
    private <T> Response<T> fail(AppException e) {
        return Response.<T>builder().code(e.getCode()).info(e.getInfo()).build();
    }

    /** 将未知异常收敛为统一系统错误。 */
    private <T> Response<T> systemFail() {
        return Response.<T>builder().code(ResponseCode.UN_ERROR.getCode()).info(ResponseCode.UN_ERROR.getInfo()).build();
    }

    /** 会话分页使用最后消息时间和会话ID组成稳定复合游标。 */
    private record SessionCursor(LocalDateTime time, String sessionId) {
    }
}
