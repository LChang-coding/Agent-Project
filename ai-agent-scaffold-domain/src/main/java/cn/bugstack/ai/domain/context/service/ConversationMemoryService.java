package cn.bugstack.ai.domain.context.service;

import cn.bugstack.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.bugstack.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.bugstack.ai.domain.context.adapter.port.ContextCompressionPort;
import cn.bugstack.ai.domain.context.adapter.port.ContextCompactionPublisher;
import cn.bugstack.ai.domain.context.adapter.port.ContextContributor;
import cn.bugstack.ai.domain.context.adapter.repository.IContextCacheRepository;
import cn.bugstack.ai.domain.context.adapter.repository.IContextCompactionTaskRepository;
import cn.bugstack.ai.domain.context.adapter.repository.IContextHistoryRepository;
import cn.bugstack.ai.domain.context.adapter.repository.IConversationMemoryRepository;
import cn.bugstack.ai.domain.context.model.ContextAssembleRequest;
import cn.bugstack.ai.domain.context.model.ContextAssemblyResult;
import cn.bugstack.ai.domain.context.model.ContextBudget;
import cn.bugstack.ai.domain.context.model.ContextCompactionTaskEntity;
import cn.bugstack.ai.domain.context.model.ContextCompactionTaskStatus;
import cn.bugstack.ai.domain.context.model.ContextContribution;
import cn.bugstack.ai.domain.context.model.ContextFragment;
import cn.bugstack.ai.domain.context.model.ContextFragmentType;
import cn.bugstack.ai.domain.context.model.ContextPolicyProperties;
import cn.bugstack.ai.domain.context.model.ContextTaskCreateCommand;
import cn.bugstack.ai.domain.context.model.ConversationMemorySnapshotEntity;
import cn.bugstack.ai.domain.session.model.entity.ChatMessageEntity;
import cn.bugstack.ai.domain.session.model.entity.ChatSessionEntity;
import cn.bugstack.ai.domain.session.service.SessionDomain;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 会话长期记忆服务。
 * <p>负责上下文组装、压缩任务创建、任务重投和摘要激活。</p>
 */
@Slf4j
@Service
public class ConversationMemoryService {

    private static final String STATUS_ACTIVE = "active";
    private static final String KEY_SUMMARY = "conversationSummary";
    private static final String KEY_DECISIONS = "confirmedDecisions";
    private static final String KEY_CONSTRAINTS = "constraints";
    private static final String KEY_OPEN_ITEMS = "openItems";
    private static final String KEY_ENTITIES = "keyEntities";
    private static final String KEY_SOURCE_RANGE = "sourceRange";
    private static final long COMPACTION_WAIT_INITIAL_INTERVAL_MS = 50L;
    private static final long COMPACTION_WAIT_MAX_INTERVAL_MS = 1_000L;

    private final IConversationMemoryRepository memoryRepository;
    private final IContextCompactionTaskRepository taskRepository;
    private final IContextHistoryRepository historyRepository;
    private final IContextCacheRepository cacheRepository;
    private final ContextCompactionPublisher publisher;
    private final ContextCompressionPort compressionPort;
    private final List<ContextContributor> contributors;
    private final ContextPolicyProperties properties;
    private final TokenCounter tokenCounter;
    private final ObjectMapper objectMapper;
    private final SessionDomain sessionDomain;
    private final ObjectProvider<DefaultArmoryFactory> defaultArmoryFactoryProvider;
    private final ObjectProvider<PlatformTransactionManager> transactionManagerProvider;

    /**
     * 创建会话记忆服务；参数是上下文依赖端口；返回服务实例。
     */
    public ConversationMemoryService(IConversationMemoryRepository memoryRepository,
                                     IContextCompactionTaskRepository taskRepository,
                                     IContextHistoryRepository historyRepository,
                                     IContextCacheRepository cacheRepository,
                                     ContextCompactionPublisher publisher,
                                     ContextCompressionPort compressionPort,
                                     List<ContextContributor> contributors,
                                     ContextPolicyProperties properties,
                                     ObjectMapper objectMapper,
                                     SessionDomain sessionDomain,
                                     ObjectProvider<DefaultArmoryFactory> defaultArmoryFactoryProvider,
                                     ObjectProvider<PlatformTransactionManager> transactionManagerProvider) {
        this.memoryRepository = memoryRepository;
        this.taskRepository = taskRepository;
        this.historyRepository = historyRepository;
        this.cacheRepository = cacheRepository;
        this.publisher = publisher;
        this.compressionPort = compressionPort;
        this.contributors = contributors == null ? List.of() : contributors;
        this.properties = properties;
        this.tokenCounter = new CharacterTokenCounter();
        this.objectMapper = objectMapper;
        this.sessionDomain = sessionDomain;
        this.defaultArmoryFactoryProvider = defaultArmoryFactoryProvider;
        this.transactionManagerProvider = transactionManagerProvider;
    }

    /**
     * 组装模型调用上下文；参数是组装请求；返回可注入系统指令。
     */
    public ContextAssemblyResult assemble(ContextAssembleRequest request) {
        return assembleInternal(request, false);
    }

    /**
     * 只读预览模型上下文；参数是组装请求；返回与真实组装同口径且不写缓存的结果。
     */
    public ContextAssemblyResult preview(ContextAssembleRequest request) {
        return assembleInternal(request, true);
    }

    private ContextAssemblyResult assembleInternal(ContextAssembleRequest request, boolean readOnly) {
        if (!properties.isEnabled() || request == null || isBlank(request.getUserId()) || isBlank(request.getSessionId())) {
            return emptyResult();
        }

        ContextBudget budget = properties.toBudget();
        ConversationMemorySnapshotEntity snapshot = readOnly
                ? memoryRepository.queryActive(request.getTenantId(), request.getUserId(), request.getSessionId())
                : queryActiveSnapshot(request.getTenantId(), request.getUserId(), request.getSessionId());
        int coveredToSequence = ConversationMemorySnapshotEntity.coveredSequenceOf(snapshot);
        int visibleThrough = request.getVisibleThroughSequence() == null ? coveredToSequence : request.getVisibleThroughSequence();
        List<ChatMessageEntity> messages = visibleThrough > coveredToSequence
                ? (readOnly ? historyRepository.queryMessages(request.getTenantId(), request.getUserId(),
                        request.getSessionId(), coveredToSequence, visibleThrough)
                        : queryMessages(request.getTenantId(), request.getUserId(), request.getSessionId(), coveredToSequence, visibleThrough))
                : List.of();

        List<ContextFragment> fragments = new ArrayList<>();
        if (snapshot != null && !isBlank(snapshot.getContent())) {
            fragments.add(ContextFragment.of(ContextFragmentType.LONG_TERM_MEMORY,
                    renderLongTermMemory(snapshot), properties.getLongTermMemoryTokens()));
        }

        List<ChatMessageEntity> recentMessages = selectRecentMessages(messages, properties.getRecentConversationTokens());
        if (!recentMessages.isEmpty()) {
            fragments.add(ContextFragment.of(ContextFragmentType.RECENT_CONVERSATION,
                    renderRecentMessages(recentMessages), properties.getRecentConversationTokens()));
        }

        if (!isBlank(request.getUpstreamOutput())) {
            fragments.add(ContextFragment.of(ContextFragmentType.WORKFLOW_UPSTREAM,
                    renderUpstreamOutput(request.getUpstreamOutput()), properties.getUpstreamTokens()));
        }

        ContextAssembleRequest contributorRequest = ContextAssembleRequest.builder()
                .tenantId(request.getTenantId()).userId(request.getUserId()).sessionId(request.getSessionId())
                .visibleThroughSequence(request.getVisibleThroughSequence())
                .attachmentVisibleThroughSequence(request.getAttachmentVisibleThroughSequence())
                .coveredToSequence(coveredToSequence).upstreamOutput(request.getUpstreamOutput())
                .traceId(request.getTraceId()).build();
        for (ContextContributor contributor : contributors) {
            List<ContextContribution> contributions = contributor.contribute(contributorRequest, properties);
            if (contributions == null) {
                continue;
            }
            for (ContextContribution contribution : contributions) {
                if (contribution == null || isBlank(contribution.getContent()) || contribution.getType() == null) {
                    continue;
                }
                fragments.add(ContextFragment.of(contribution.getType(), contribution.getContent(), maxTokensFor(contribution.getType())));
            }
        }

        List<ContextFragment> selected = new ContextAssembler(tokenCounter).assemble(budget, fragments);
        String instruction = renderInstruction(selected);
        int estimatedTokens = tokenCounter.estimate(instruction);
        int summaryTokens = selectedTokens(selected, ContextFragmentType.LONG_TERM_MEMORY);
        int historyTokens = selectedTokens(selected, ContextFragmentType.RECENT_CONVERSATION);
        int upstreamTokens = selectedTokens(selected, ContextFragmentType.WORKFLOW_UPSTREAM);
        int attachmentTokens = selectedTokens(selected, ContextFragmentType.ATTACHMENT);
        int ragTokens = selectedTokens(selected, ContextFragmentType.RAG);
        boolean historySelected = selected.stream().anyMatch(fragment -> fragment.getType() == ContextFragmentType.RECENT_CONVERSATION);
        boolean recentWindowTrimmed = messages.size() > recentMessages.size();
        boolean totalBudgetTrimmed = !recentMessages.isEmpty() && !historySelected;
        boolean trimmed = recentWindowTrimmed || totalBudgetTrimmed;
        log.info("上下文组装完成 tenantId:{} userId:{} sessionId:{} visibleThrough:{} memoryVersion:{} injectedTokens:{} trimmed:{}",
                request.getTenantId(), request.getUserId(), request.getSessionId(), visibleThrough,
                snapshot == null ? 0 : snapshot.getMemoryVersion(), estimatedTokens, trimmed);
        return ContextAssemblyResult.builder()
                .instruction(instruction)
                .estimatedTokenCount(estimatedTokens)
                .memoryVersion(snapshot == null ? 0 : snapshot.getMemoryVersion())
                .coveredToSequence(coveredToSequence)
                .cacheHit(false)
                .trimReason(totalBudgetTrimmed ? "total_context_budget"
                        : (recentWindowTrimmed ? "recent_window_budget" : null))
                .summaryTokens(summaryTokens).historyTokens(historyTokens).upstreamTokens(upstreamTokens)
                .attachmentTokens(attachmentTokens)
                .ragTokens(ragTokens)
                .effectiveFromSequence(!historySelected ? null : recentMessages.get(0).getSequenceNo())
                .effectiveToSequence(!historySelected ? coveredToSequence
                        : recentMessages.get(recentMessages.size() - 1).getSequenceNo())
                .build();
    }

    private int selectedTokens(List<ContextFragment> fragments, ContextFragmentType type) {
        return fragments.stream().filter(fragment -> fragment.getType() == type)
                .mapToInt(fragment -> tokenCounter.estimate(fragment.getContent())).sum();
    }

    /**
     * 会话激活时重投未完成任务；参数是会话身份；无返回值。
     */
    public void republishUnfinished(String tenantId, String userId, String sessionId) {
        if (!properties.isEnabled()) {
            return;
        }
        List<ContextCompactionTaskEntity> tasks = taskRepository.queryUnfinished(tenantId, userId, sessionId);
        for (ContextCompactionTaskEntity task : tasks) {
            publisher.publish(task.toCommand());
        }
        if (!tasks.isEmpty()) {
            log.info("上下文压缩任务已重投 tenantId:{} userId:{} sessionId:{} count:{}",
                    tenantId, userId, sessionId, tasks.size());
        }
    }

    /**
     * 助手消息保存后检查是否需要压缩；参数是助手消息；无返回值。
     */
    public void onAssistantMessageSaved(ChatMessageEntity assistantMessage) {
        if (!properties.isEnabled() || assistantMessage == null || assistantMessage.getSequenceNo() == null) {
            return;
        }
        onMessageSaved(assistantMessage);
        republishUnfinished(assistantMessage.getTenantId(), assistantMessage.getUserId(), assistantMessage.getSessionId());
        ConversationMemorySnapshotEntity snapshot = queryActiveSnapshot(assistantMessage.getTenantId(), assistantMessage.getUserId(), assistantMessage.getSessionId());
        int coveredToSequence = ConversationMemorySnapshotEntity.coveredSequenceOf(snapshot);
        int uncoveredTokens = historyRepository.sumEstimatedTokens(assistantMessage.getTenantId(), assistantMessage.getUserId(),
                assistantMessage.getSessionId(), coveredToSequence, assistantMessage.getSequenceNo());
        if (uncoveredTokens < properties.getCompactionMinUncoveredTokens()) {
            return;
        }

        List<ChatMessageEntity> uncoveredMessages = historyRepository.queryMessages(assistantMessage.getTenantId(), assistantMessage.getUserId(),
                assistantMessage.getSessionId(), coveredToSequence, assistantMessage.getSequenceNo());
        int toSequence = calculateCompactionToSequence(uncoveredMessages, properties.getCompactionRetainRecentTokens());
        if (toSequence <= coveredToSequence) {
            return;
        }

        ContextCompactionTaskEntity task = taskRepository.createIfAbsent(ContextTaskCreateCommand.builder()
                .tenantId(assistantMessage.getTenantId())
                .userId(assistantMessage.getUserId())
                .sessionId(assistantMessage.getSessionId())
                .runId(assistantMessage.getRunId())
                .fromSequence(coveredToSequence + 1)
                .toSequence(toSequence)
                .expectedMemoryVersion(ConversationMemorySnapshotEntity.versionOf(snapshot))
                .baseContextRevision(sessionRevision(assistantMessage.getTenantId(), assistantMessage.getUserId(), assistantMessage.getSessionId()))
                .coverageHash(coverageHash(uncoveredMessages, coveredToSequence + 1, toSequence,
                        attachmentContext(assistantMessage.getTenantId(), assistantMessage.getUserId(),
                                assistantMessage.getSessionId(), coveredToSequence, toSequence)))
                .policyVersion(properties.getPolicyVersion())
                .traceId(assistantMessage.getTraceId())
                .build());
        if (task != null && (task.getStatus() == ContextCompactionTaskStatus.PENDING || task.getStatus() == ContextCompactionTaskStatus.RETRYING)) {
            publisher.publish(task.toCommand());
            log.info("上下文压缩任务已创建 tenantId:{} userId:{} sessionId:{} taskId:{} range:{}-{} uncoveredTokens:{}",
                    task.getTenantId(), task.getUserId(), task.getSessionId(), task.getTaskId(),
                    task.getFromSequence(), task.getToSequence(), uncoveredTokens);
        }
    }

    /**
     * 消息持久化后刷新会话短期窗口；参数是已保存消息；无返回值。
     */
    public void onMessageSaved(ChatMessageEntity message) {
        if (!properties.isEnabled() || message == null || message.getSequenceNo() == null) {
            return;
        }
        cacheRepository.appendRecentMessage(message, properties.getRecentWindowMaxMessages(),
                Duration.ofSeconds(properties.getCacheTtlSeconds()));
    }

    /**
     * 执行压缩任务；参数是任务ID；成功后激活新摘要。
     */
    public void compactTask(String taskId) {
        ContextCompactionTaskEntity task = taskRepository.queryByTaskId(taskId);
        if (task == null) {
            log.warn("上下文压缩任务不存在 taskId:{}", taskId);
            return;
        }

        ConversationMemorySnapshotEntity active = memoryRepository.queryActive(task.getTenantId(), task.getUserId(), task.getSessionId());
        int activeVersion = ConversationMemorySnapshotEntity.versionOf(active);
        if (activeVersion != safe(task.getExpectedMemoryVersion())) {
            if (ConversationMemorySnapshotEntity.coveredSequenceOf(active) >= safe(task.getToSequence())) {
                taskRepository.complete(taskId);
                return;
            }
            throw new IllegalStateException("上下文摘要版本已变化，等待新任务覆盖");
        }

        List<ChatMessageEntity> messages = historyRepository.queryMessages(task.getTenantId(), task.getUserId(), task.getSessionId(),
                safe(task.getFromSequence()) - 1, task.getToSequence());
        if (messages.isEmpty()) {
            taskRepository.complete(taskId);
            return;
        }
        long currentRevision = sessionRevision(task.getTenantId(), task.getUserId(), task.getSessionId());
        if (task.getBaseContextRevision() != null && task.getBaseContextRevision() != currentRevision) {
            throw new IllegalStateException("上下文版本已变化，压缩结果禁止激活");
        }
        String attachmentContext = attachmentContext(task.getTenantId(), task.getUserId(), task.getSessionId(),
                safe(task.getFromSequence()) - 1, task.getToSequence());
        String currentHash = coverageHash(messages, task.getFromSequence(), task.getToSequence(), attachmentContext);
        if (!isBlank(task.getCoverageHash()) && !task.getCoverageHash().equals(currentHash)) {
            throw new IllegalStateException("有效消息集合已变化，压缩结果禁止激活");
        }

        ChatModel chatModel = resolveChatModel(task);
        String prompt = buildCompressionPrompt(active, messages, attachmentContext, task);
        String json = normalizeSummaryJson(compressionPort.compress(chatModel, prompt), task);
        ContextCompactionTaskEntity latestTask = taskRepository.queryByTaskId(taskId);
        if (latestTask == null || latestTask.getStatus() == ContextCompactionTaskStatus.STALE
                || latestTask.getStatus() == ContextCompactionTaskStatus.CANCEL_REQUESTED
                || !Objects.equals(latestTask.getFencingToken(), task.getFencingToken())) {
            throw new IllegalStateException("压缩任务已取消或陈旧，结果禁止激活");
        }
        if (sessionRevision(task.getTenantId(), task.getUserId(), task.getSessionId()) != currentRevision) {
            throw new IllegalStateException("压缩期间上下文版本已变化，结果禁止激活");
        }
        ConversationMemorySnapshotEntity snapshot = activateCompaction(taskId, task, json, currentHash,
                tokenCounter.estimate(json));
        refreshCacheAfterCommit(snapshot);
        log.info("上下文压缩任务完成 tenantId:{} userId:{} sessionId:{} taskId:{} memoryVersion:{} coveredTo:{} tokens:{}",
                task.getTenantId(), task.getUserId(), task.getSessionId(), taskId,
                snapshot.getMemoryVersion(), snapshot.getCoveredToSequence(), snapshot.getEstimatedTokenCount());
    }

    /**
     * 在短事务中激活压缩结果；参数是任务、摘要和覆盖指纹；返回新快照。
     */
    private ConversationMemorySnapshotEntity activateCompaction(String taskId, ContextCompactionTaskEntity task,
                                                                 String json, String expectedHash, int estimatedTokens) {
        PlatformTransactionManager transactionManager = transactionManagerProvider == null
                ? null : transactionManagerProvider.getIfAvailable();
        if (transactionManager == null) {
            return activateCompactionInTransaction(taskId, task, json, expectedHash, estimatedTokens, false);
        }
        ConversationMemorySnapshotEntity snapshot = new TransactionTemplate(transactionManager).execute(status ->
                activateCompactionInTransaction(taskId, task, json, expectedHash, estimatedTokens, true));
        if (snapshot == null) {
            throw new IllegalStateException("上下文摘要激活事务未返回结果");
        }
        return snapshot;
    }

    private ConversationMemorySnapshotEntity activateCompactionInTransaction(String taskId,
                                                                               ContextCompactionTaskEntity task,
                                                                               String json,
                                                                               String expectedHash,
                                                                               int estimatedTokens,
                                                                               boolean lockSession) {
        ChatSessionEntity lockedSession = lockSession
                ? sessionDomain.lockSessionAccess(task.getTenantId(), task.getUserId(), task.getSessionId(), null)
                : sessionDomain.assertSessionAccess(task.getTenantId(), task.getUserId(), task.getSessionId(), null);
        ContextCompactionTaskEntity latest = taskRepository.queryByTaskId(taskId);
        if (latest == null || latest.getStatus() == ContextCompactionTaskStatus.STALE
                || latest.getStatus() == ContextCompactionTaskStatus.CANCEL_REQUESTED
                || latest.getStatus() == ContextCompactionTaskStatus.DEAD
                || !Objects.equals(latest.getFencingToken(), task.getFencingToken())) {
            throw new IllegalStateException("压缩任务已失效，结果禁止激活");
        }
        long lockedRevision = lockedSession.getContextRevision() == null ? 0L : lockedSession.getContextRevision();
        if (task.getBaseContextRevision() != null && task.getBaseContextRevision() != lockedRevision) {
            throw new IllegalStateException("压缩激活前上下文版本已变化");
        }
        List<ChatMessageEntity> effectiveMessages = historyRepository.queryMessages(task.getTenantId(), task.getUserId(),
                task.getSessionId(), safe(task.getFromSequence()) - 1, task.getToSequence());
        String lockedAttachmentContext = attachmentContext(task.getTenantId(), task.getUserId(), task.getSessionId(),
                safe(task.getFromSequence()) - 1, task.getToSequence());
        String lockedHash = coverageHash(effectiveMessages, task.getFromSequence(), task.getToSequence(),
                lockedAttachmentContext);
        if (!expectedHash.equals(lockedHash)) {
            throw new IllegalStateException("压缩激活前有效消息集合已变化");
        }
        ConversationMemorySnapshotEntity active = memoryRepository.queryActive(task.getTenantId(), task.getUserId(),
                task.getSessionId());
        int activeVersion = ConversationMemorySnapshotEntity.versionOf(active);
        if (activeVersion != safe(task.getExpectedMemoryVersion())) {
            throw new IllegalStateException("压缩激活前摘要版本已变化");
        }
        ConversationMemorySnapshotEntity snapshot = ConversationMemorySnapshotEntity.builder()
                .tenantId(task.getTenantId()).userId(task.getUserId()).sessionId(task.getSessionId())
                .memoryVersion(activeVersion + 1).baseContextRevision(lockedRevision)
                .coveredToSequence(task.getToSequence()).coverageHash(lockedHash)
                .parentMemoryVersion(activeVersion == 0 ? null : activeVersion)
                .content(json).estimatedTokenCount(estimatedTokens).policyVersion(task.getPolicyVersion())
                .status(STATUS_ACTIVE).traceId(task.getTraceId()).build();
        if (!memoryRepository.activate(task.getTenantId(), task.getUserId(), task.getSessionId(), activeVersion, snapshot)) {
            throw new IllegalStateException("上下文摘要 CAS 激活失败");
        }
        if (taskRepository.complete(taskId) != 1) {
            throw new IllegalStateException("压缩任务状态已变化，摘要激活回滚");
        }
        sessionDomain.incrementContextRevision(task.getTenantId(), task.getUserId(), task.getSessionId());
        return snapshot;
    }

    /**
     * 同步压缩当前会话；参数是会话身份和可见序号；返回是否完成压缩。
     */
    public boolean compactSynchronously(String tenantId, String userId, String sessionId, Integer visibleThroughSequence, String traceId) {
        if (!properties.isEnabled() || visibleThroughSequence == null || visibleThroughSequence <= 0) {
            return false;
        }
        ConversationMemorySnapshotEntity snapshot = queryActiveSnapshot(tenantId, userId, sessionId);
        int coveredToSequence = ConversationMemorySnapshotEntity.coveredSequenceOf(snapshot);
        if (visibleThroughSequence <= coveredToSequence) {
            return false;
        }
        List<ChatMessageEntity> messages = historyRepository.queryMessages(tenantId, userId, sessionId,
                coveredToSequence, visibleThroughSequence);
        long contextRevision = sessionRevision(tenantId, userId, sessionId);
        ContextCompactionTaskEntity task = taskRepository.createIfAbsent(ContextTaskCreateCommand.builder()
                .tenantId(tenantId)
                .userId(userId)
                .sessionId(sessionId)
                .fromSequence(coveredToSequence + 1)
                .toSequence(visibleThroughSequence)
                .expectedMemoryVersion(ConversationMemorySnapshotEntity.versionOf(snapshot))
                .baseContextRevision(contextRevision)
                .coverageHash(coverageHash(messages, coveredToSequence + 1, visibleThroughSequence,
                        attachmentContext(tenantId, userId, sessionId, coveredToSequence, visibleThroughSequence)))
                .policyVersion(properties.getPolicyVersion())
                .traceId(traceId)
                .build());
        if (task == null || !taskRepository.claim(task.getTaskId())) {
            return false;
        }
        compactTask(task.getTaskId());
        return true;
    }

    /**
     * 工具调用前按阈值压缩；参数是运行身份、可见序号和链路ID；返回是否激活了新摘要。
     */
    public boolean compactBeforeTool(String tenantId, String userId, String sessionId, String runId,
                                     Integer visibleThroughSequence, String traceId) {
        if (!properties.isEnabled() || visibleThroughSequence == null || visibleThroughSequence <= 0) {
            return false;
        }
        ConversationMemorySnapshotEntity snapshot = queryActiveSnapshot(tenantId, userId, sessionId);
        int covered = ConversationMemorySnapshotEntity.coveredSequenceOf(snapshot);
        int tokens = historyRepository.sumEstimatedTokens(tenantId, userId, sessionId, covered, visibleThroughSequence);
        if (tokens < properties.getCompactionMinUncoveredTokens()) {
            return false;
        }
        List<ChatMessageEntity> messages = historyRepository.queryMessages(tenantId, userId, sessionId, covered, visibleThroughSequence);
        int toSequence = calculateCompactionToSequence(messages, properties.getCompactionRetainRecentTokens());
        if (toSequence <= covered) {
            return false;
        }
        long revision = sessionRevision(tenantId, userId, sessionId);
        ContextCompactionTaskEntity task = taskRepository.createIfAbsent(ContextTaskCreateCommand.builder()
                .tenantId(tenantId).userId(userId).sessionId(sessionId).runId(runId)
                .fromSequence(covered + 1).toSequence(toSequence)
                .expectedMemoryVersion(ConversationMemorySnapshotEntity.versionOf(snapshot))
                .baseContextRevision(revision)
                .coverageHash(coverageHash(messages, covered + 1, toSequence,
                        attachmentContext(tenantId, userId, sessionId, covered, toSequence)))
                .policyVersion(properties.getPolicyVersion()).traceId(traceId).build());
        if (task == null) {
            throw new IllegalStateException("工具调用前无法创建上下文压缩任务");
        }
        if (taskRepository.claim(task.getTaskId())) {
            compactTask(task.getTaskId());
            return true;
        }
        return waitForCompaction(task.getTaskId(), revision, 5_000L);
    }

    private boolean waitForCompaction(String taskId, long baseRevision, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        long waitIntervalMs = COMPACTION_WAIT_INITIAL_INTERVAL_MS;
        while (System.currentTimeMillis() < deadline) {
            ContextCompactionTaskEntity current = taskRepository.queryByTaskId(taskId);
            if (current == null || current.getStatus() == ContextCompactionTaskStatus.DEAD
                    || current.getStatus() == ContextCompactionTaskStatus.STALE
                    || current.getStatus() == ContextCompactionTaskStatus.CANCEL_REQUESTED) {
                throw new IllegalStateException("工具调用前上下文压缩失败或已失效");
            }
            if (current.getStatus() == ContextCompactionTaskStatus.SUCCEEDED) {
                return true;
            }
            long remainingMs = deadline - System.currentTimeMillis();
            if (remainingMs <= 0L) {
                break;
            }
            try {
                Thread.sleep(Math.min(waitIntervalMs, remainingMs));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待上下文压缩被中断", e);
            }
            waitIntervalMs = Math.min(waitIntervalMs * 2L, COMPACTION_WAIT_MAX_INTERVAL_MS);
        }
        throw new IllegalStateException("工具调用前上下文压缩超时");
    }

    private ConversationMemorySnapshotEntity queryActiveSnapshot(String tenantId, String userId, String sessionId) {
        ConversationMemorySnapshotEntity cached = cacheRepository.queryActiveSnapshot(tenantId, userId, sessionId);
        if (cached != null) {
            return cached;
        }
        ConversationMemorySnapshotEntity snapshot = memoryRepository.queryActive(tenantId, userId, sessionId);
        if (snapshot != null) {
            cacheRepository.cacheActiveSnapshot(snapshot, Duration.ofSeconds(properties.getCacheTtlSeconds()));
        }
        return snapshot;
    }

    /**
     * 在数据库提交后切换缓存摘要和短期窗口；参数是新摘要；无返回值。
     */
    private void refreshCacheAfterCommit(ConversationMemorySnapshotEntity snapshot) {
        Runnable refresh = () -> {
            cacheRepository.cacheActiveSnapshot(snapshot, Duration.ofSeconds(properties.getCacheTtlSeconds()));
            cacheRepository.removeRecentMessagesThrough(snapshot.getTenantId(), snapshot.getUserId(), snapshot.getSessionId(),
                    snapshot.getCoveredToSequence());
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            refresh.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                refresh.run();
            }
        });
    }

    private List<ChatMessageEntity> queryMessages(String tenantId, String userId, String sessionId,
                                                  Integer fromSequenceExclusive, Integer toSequenceInclusive) {
        List<ChatMessageEntity> cached = cacheRepository.queryRecentMessages(tenantId, userId, sessionId,
                fromSequenceExclusive, toSequenceInclusive);
        if (cached != null) {
            return cached;
        }
        List<ChatMessageEntity> messages = historyRepository.queryMessages(tenantId, userId, sessionId, fromSequenceExclusive, toSequenceInclusive);
        cacheRepository.warmRecentMessages(tenantId, userId, sessionId, messages, properties.getRecentWindowMaxMessages(),
                Duration.ofSeconds(properties.getCacheTtlSeconds()));
        return messages;
    }

    private List<ChatMessageEntity> selectRecentMessages(List<ChatMessageEntity> messages, int tokenBudget) {
        if (messages == null || messages.isEmpty() || tokenBudget <= 0) {
            return List.of();
        }
        List<ChatMessageEntity> selected = new ArrayList<>();
        int used = 0;
        for (int index = messages.size() - 1; index >= 0; index--) {
            ChatMessageEntity message = messages.get(index);
            int tokens = messageTokens(message);
            if (!selected.isEmpty() && used + tokens > tokenBudget) {
                break;
            }
            selected.add(message);
            used += tokens;
            if (used >= tokenBudget) {
                break;
            }
        }
        Collections.reverse(selected);
        return selected;
    }

    private int calculateCompactionToSequence(List<ChatMessageEntity> messages, int retainTokenBudget) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int retained = 0;
        int toSequence = messages.get(messages.size() - 1).getSequenceNo();
        for (int index = messages.size() - 1; index >= 0; index--) {
            ChatMessageEntity message = messages.get(index);
            retained += messageTokens(message);
            if (retained >= retainTokenBudget) {
                toSequence = message.getSequenceNo() - 1;
                break;
            }
        }
        return Math.max(0, toSequence);
    }

    private int messageTokens(ChatMessageEntity message) {
        if (message == null) {
            return 0;
        }
        if (message.getEstimatedTokenCount() != null && message.getEstimatedTokenCount() > 0) {
            return message.getEstimatedTokenCount();
        }
        return tokenCounter.estimate(message.getContent());
    }

    private long sessionRevision(String tenantId, String userId, String sessionId) {
        ChatSessionEntity session = sessionDomain.assertSessionAccess(tenantId, userId, sessionId, null);
        return session.getContextRevision() == null ? 0L : session.getContextRevision();
    }

    private String coverageHash(List<ChatMessageEntity> messages, Integer fromSequence, Integer toSequence,
                                String attachmentContext) {
        StringBuilder raw = new StringBuilder();
        if (messages != null) {
            messages.stream()
                    .filter(message -> message.getSequenceNo() != null
                            && message.getSequenceNo() >= safe(fromSequence)
                            && message.getSequenceNo() <= safe(toSequence))
                    .forEach(message -> raw.append(message.getMessageId()).append('|')
                            .append(message.getSequenceNo()).append('|')
                            .append(message.getValidityStatus()).append('|')
                            .append(message.getContent()).append('\n'));
        }
        raw.append("attachments|").append(attachmentContext == null ? "" : attachmentContext);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", e);
        }
    }

    private String renderInstruction(List<ContextFragment> fragments) {
        if (fragments == null || fragments.isEmpty()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        lines.add("以下是当前业务会话的可恢复历史上下文。请只把它作为同一 sessionId 的背景信息使用，不要复述本段。");
        lines.add("<conversation_context>");
        for (ContextFragment fragment : fragments) {
            lines.add(fragment.getContent());
        }
        lines.add("</conversation_context>");
        return String.join("\n", lines);
    }

    private String renderLongTermMemory(ConversationMemorySnapshotEntity snapshot) {
        return "<long_term_memory memoryVersion=\"" + snapshot.getMemoryVersion()
                + "\" coveredToSequence=\"" + snapshot.getCoveredToSequence() + "\">\n"
                + snapshot.getContent() + "\n</long_term_memory>";
    }

    private String renderRecentMessages(List<ChatMessageEntity> messages) {
        List<String> lines = new ArrayList<>();
        lines.add("<recent_messages>");
        for (ChatMessageEntity message : messages) {
            lines.add("[seq=" + message.getSequenceNo() + " role=" + safeRole(message.getRole()) + "]");
            lines.add(message.getContent() == null ? "" : message.getContent());
        }
        lines.add("</recent_messages>");
        return String.join("\n", lines);
    }

    private String renderUpstreamOutput(String upstreamOutput) {
        return "<workflow_upstream>\n" + upstreamOutput + "\n</workflow_upstream>";
    }

    private int maxTokensFor(ContextFragmentType type) {
        if (type == ContextFragmentType.LONG_TERM_MEMORY) {
            return properties.getLongTermMemoryTokens();
        }
        if (type == ContextFragmentType.RECENT_CONVERSATION) {
            return properties.getRecentConversationTokens();
        }
        if (type == ContextFragmentType.WORKFLOW_UPSTREAM) {
            return properties.getUpstreamTokens();
        }
        if (type == ContextFragmentType.ATTACHMENT) {
            return properties.getAttachmentTokens();
        }
        return properties.getRagTokens();
    }

    private String buildCompressionPrompt(ConversationMemorySnapshotEntity active,
                                          List<ChatMessageEntity> messages,
                                          String attachmentContext,
                                          ContextCompactionTaskEntity task) {
        List<String> lines = new ArrayList<>();
        lines.add("你是会话长期记忆压缩器。请把旧摘要和新增原始消息压缩成一个严格 JSON 对象。");
        lines.add("只能输出 JSON，不要输出 Markdown，不要输出解释。JSON 必须包含：");
        lines.add("conversationSummary:string, confirmedDecisions:array, constraints:array, openItems:array, keyEntities:array, sourceRange:object。");
        lines.add("长期记忆只属于同一 sessionId，保留已确认事实、约束和待办，删除闲聊和重复内容。");
        lines.add("旧摘要：");
        lines.add(active == null || isBlank(active.getContent()) ? "{}" : active.getContent());
        lines.add("新增消息范围：" + task.getFromSequence() + "-" + task.getToSequence());
        lines.add("新增消息：");
        for (ChatMessageEntity message : messages) {
            lines.add("[seq=" + message.getSequenceNo() + " role=" + safeRole(message.getRole()) + "]");
            lines.add(message.getContent() == null ? "" : message.getContent());
        }
        if (!isBlank(attachmentContext)) {
            lines.add("新增消息关联附件：");
            lines.add(attachmentContext);
        }
        return String.join("\n", lines);
    }

    /** 组装指定消息区间的附件文本；参数是可信会话和序号边界；返回已去重的附件片段。 */
    private String attachmentContext(String tenantId, String userId, String sessionId,
                                     Integer fromSequenceExclusive, Integer toSequenceInclusive) {
        ContextAssembleRequest request = ContextAssembleRequest.builder()
                .tenantId(tenantId).userId(userId).sessionId(sessionId)
                .visibleThroughSequence(toSequenceInclusive)
                .attachmentVisibleThroughSequence(toSequenceInclusive)
                .coveredToSequence(fromSequenceExclusive).build();
        List<String> values = new ArrayList<>();
        for (ContextContributor contributor : contributors) {
            List<ContextContribution> contributions = contributor.contribute(request, properties);
            if (contributions == null) continue;
            contributions.stream().filter(value -> value != null && value.getType() == ContextFragmentType.ATTACHMENT
                            && !isBlank(value.getContent()))
                    .map(ContextContribution::getContent).forEach(values::add);
        }
        return String.join("\n", values);
    }

    private String normalizeSummaryJson(String raw, ContextCompactionTaskEntity task) {
        try {
            String cleaned = stripCodeFence(raw);
            JsonNode node = objectMapper.readTree(cleaned);
            if (!node.isObject()) {
                throw new IllegalStateException("上下文压缩结果不是 JSON 对象");
            }
            ObjectNode objectNode = (ObjectNode) node;
            requireText(objectNode, KEY_SUMMARY);
            ensureArray(objectNode, KEY_DECISIONS);
            ensureArray(objectNode, KEY_CONSTRAINTS);
            ensureArray(objectNode, KEY_OPEN_ITEMS);
            ensureArray(objectNode, KEY_ENTITIES);
            ObjectNode range = objectMapper.createObjectNode();
            range.put("fromSequence", task.getFromSequence());
            range.put("toSequence", task.getToSequence());
            range.put("policyVersion", task.getPolicyVersion());
            objectNode.set(KEY_SOURCE_RANGE, range);
            return objectMapper.writeValueAsString(objectNode);
        } catch (Exception e) {
            throw new IllegalStateException("上下文压缩结果 JSON 校验失败", e);
        }
    }

    private void requireText(ObjectNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalStateException("上下文压缩结果缺少字段 " + key);
        }
    }

    private void ensureArray(ObjectNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || !value.isArray()) {
            ArrayNode arrayNode = objectMapper.createArrayNode();
            node.set(key, arrayNode);
        }
    }

    private ChatModel resolveChatModel(ContextCompactionTaskEntity task) {
        ChatSessionEntity session = sessionDomain.assertSessionAccess(task.getTenantId(), task.getUserId(), task.getSessionId(), null);
        DefaultArmoryFactory defaultArmoryFactory = defaultArmoryFactoryProvider == null ? null : defaultArmoryFactoryProvider.getIfAvailable();
        if (defaultArmoryFactory == null) {
            throw new IllegalStateException("上下文压缩缺少 ArmoryFactory");
        }
        AiAgentRegisterVO agent = queryRegisteredAgent(defaultArmoryFactory, session.getAgentId());
        if (agent == null && !isBlank(session.getAppName()) && !session.getAppName().equals(session.getAgentId())) {
            agent = queryRegisteredAgent(defaultArmoryFactory, session.getAppName());
        }
        if (agent == null) {
            throw new IllegalStateException("上下文压缩找不到会话 Agent：" + session.getAgentId());
        }
        if (agent.getChatModel() == null) {
            throw new IllegalStateException("上下文压缩会话 Agent 未绑定模型：" + session.getAgentId());
        }
        return agent.getChatModel();
    }

    /**
     * 查询已注册运行时 Agent；参数是装配工厂和 Agent 标识；找不到时返回空。
     */
    private AiAgentRegisterVO queryRegisteredAgent(DefaultArmoryFactory defaultArmoryFactory, String agentId) {
        if (isBlank(agentId)) {
            return null;
        }
        try {
            return defaultArmoryFactory.getAiAgentRegisterVO(agentId);
        } catch (NoSuchBeanDefinitionException e) {
            return null;
        }
    }

    private ContextAssemblyResult emptyResult() {
        return ContextAssemblyResult.builder()
                .instruction("")
                .estimatedTokenCount(0)
                .memoryVersion(0)
                .coveredToSequence(0)
                .cacheHit(false)
                .summaryTokens(0).historyTokens(0).upstreamTokens(0).attachmentTokens(0).ragTokens(0)
                .build();
    }

    private String stripCodeFence(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim();
        if (value.startsWith("```")) {
            value = value.replaceFirst("^```[a-zA-Z]*\\s*", "");
            value = value.replaceFirst("\\s*```$", "");
        }
        return value.trim();
    }

    private String safeRole(String role) {
        return isBlank(role) ? "unknown" : role.toLowerCase(Locale.ROOT);
    }

    private int safe(Integer value) {
        return value == null ? 0 : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
