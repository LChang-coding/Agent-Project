package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.api.dto.session.SessionDeleteResponseDTO;
import cn.bugstack.ai.api.dto.session.SessionListResponseDTO;
import cn.bugstack.ai.api.dto.session.SessionMessagePageResponseDTO;
import cn.bugstack.ai.api.dto.session.SessionMessageResponseDTO;
import cn.bugstack.ai.api.dto.session.SessionSummaryResponseDTO;
import cn.bugstack.ai.api.response.Response;
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

    /**
     * 创建会话接口；参数是会话领域服务；返回接口实例。
     */
    public SessionController(SessionDomain sessionDomain, SessionLifecycleService lifecycleService) {
        this.sessionDomain = sessionDomain;
        this.lifecycleService = lifecycleService;
    }

    /**
     * 查询当前用户会话；参数是可选游标和数量；返回数据库会话摘要。
     */
    @GetMapping
    public Response<SessionListResponseDTO> list(@RequestParam(required = false) String cursor,
                                                 @RequestParam(defaultValue = "30") int limit) {
        try {
            int pageSize = normalizeLimit(limit);
            SessionCursor decoded = decodeCursor(cursor);
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
     * 查询会话有效消息；参数是会话、前序号和数量；返回按时间正序的消息页。
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
     * 软删除会话；参数是会话ID；返回删除时的上下文版本。
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

    private SessionSummaryResponseDTO toSummary(ChatSessionEntity session) {
        return SessionSummaryResponseDTO.builder().sessionId(session.getSessionId()).agentId(session.getAgentId())
                .agentName(session.getAgentName()).appName(session.getAppName()).title(session.getTitle())
                .sourceType(session.getSourceType()).workflowVersion(session.getWorkflowVersion()).modelCode(session.getModelCode())
                .status(session.getStatus()).lastMessageTime(session.getLastMessageTime())
                .contextRevision(session.getContextRevision()).build();
    }

    private SessionMessageResponseDTO toMessage(ChatMessageEntity message) {
        return SessionMessageResponseDTO.builder().messageId(message.getMessageId()).runId(message.getRunId())
                .role(message.getRole()).contentType(message.getContentType()).content(message.getContent())
                .estimatedTokenCount(message.getEstimatedTokenCount()).sequenceNo(message.getSequenceNo())
                .createTime(message.getCreateTime()).build();
    }

    private int normalizeLimit(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "limit 必须在 1 到 100 之间");
        }
        return limit == 0 ? DEFAULT_LIMIT : limit;
    }

    private String requireUserId() {
        String userId = TenantContextHolder.getUserId();
        if (userId == null || userId.isBlank()) {
            throw new AppException("AUTH_UNAUTHORIZED", "登录身份已失效");
        }
        return userId;
    }

    private String encodeCursor(ChatSessionEntity session) {
        String raw = session.getLastMessageTime() + "|" + session.getSessionId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

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

    private <T> Response<T> success(T data) {
        return Response.<T>builder().code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo()).data(data).build();
    }

    private <T> Response<T> fail(AppException e) {
        return Response.<T>builder().code(e.getCode()).info(e.getInfo()).build();
    }

    private <T> Response<T> systemFail() {
        return Response.<T>builder().code(ResponseCode.UN_ERROR.getCode()).info(ResponseCode.UN_ERROR.getInfo()).build();
    }

    private record SessionCursor(LocalDateTime time, String sessionId) {
    }
}
