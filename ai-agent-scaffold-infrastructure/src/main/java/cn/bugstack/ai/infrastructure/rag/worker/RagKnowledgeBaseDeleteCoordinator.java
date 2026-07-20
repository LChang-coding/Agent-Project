package cn.bugstack.ai.infrastructure.rag.worker;

import cn.bugstack.ai.domain.rag.adapter.repository.IRagRepository;
import cn.bugstack.ai.domain.rag.adapter.repository.RagKnowledgeBaseDeletionRepository;
import cn.bugstack.ai.domain.rag.model.entity.RagDocumentEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagIngestJobEntity;
import cn.bugstack.ai.domain.rag.model.entity.RagKnowledgeBaseDeleteTaskEntity;
import cn.bugstack.ai.domain.rag.model.valobj.RagDocumentStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagIngestJobStatus;
import cn.bugstack.ai.domain.rag.model.valobj.RagKnowledgeBaseDeleteCheckpoint;
import cn.bugstack.ai.domain.rag.model.valobj.RagKnowledgeBaseDeleteStage;
import cn.bugstack.ai.domain.rag.service.RagDocumentDeletionService;
import cn.bugstack.ai.infrastructure.rag.config.RagProperties;
import cn.bugstack.ai.types.exception.AppException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** 以MySQL为真相源，逐文档编排现有DELETE子任务的知识库删除协调器。 */
@Component
@ConditionalOnProperty(prefix = "ai.rag.worker", name = "enabled", havingValue = "true")
public class RagKnowledgeBaseDeleteCoordinator {

    private final RagKnowledgeBaseDeletionRepository deletionRepository;
    private final IRagRepository repository;
    private final RagDocumentDeletionService documentDeletionService;
    private final RagProperties properties;
    private final Clock clock;
    private final RagIngestErrorClassifier errorClassifier = new RagIngestErrorClassifier();

    @Autowired
    public RagKnowledgeBaseDeleteCoordinator(RagKnowledgeBaseDeletionRepository deletionRepository,
                                              IRagRepository repository,
                                              RagDocumentDeletionService documentDeletionService,
                                              RagProperties properties) {
        this(deletionRepository, repository, documentDeletionService, properties, Clock.systemUTC());
    }

    RagKnowledgeBaseDeleteCoordinator(RagKnowledgeBaseDeletionRepository deletionRepository,
                                       IRagRepository repository,
                                       RagDocumentDeletionService documentDeletionService,
                                       RagProperties properties, Clock clock) {
        this.deletionRepository = deletionRepository;
        this.repository = repository;
        this.documentDeletionService = documentDeletionService;
        this.properties = properties;
        this.clock = clock;
    }

    public boolean execute(String tenantId, String taskId, String leaseOwner) {
        Instant now = clock.instant();
        RagKnowledgeBaseDeleteTaskEntity current = deletionRepository.findByTaskId(tenantId, taskId)
                .orElse(null);
        if (current == null || current.status().terminal()) return false;
        RagKnowledgeBaseDeleteTaskEntity task = deletionRepository.claim(tenantId, taskId, leaseOwner,
                now, now.plus(leaseDuration())).orElse(null);
        if (task == null) return false;
        try {
            process(task, leaseOwner);
        } catch (Exception error) {
            handleFailure(tenantId, taskId, leaseOwner, task.fencingToken(), error);
        }
        return true;
    }

    private void process(RagKnowledgeBaseDeleteTaskEntity claimed, String leaseOwner) {
        RagKnowledgeBaseDeleteTaskEntity task = barrier(claimed, leaseOwner);
        if (task.checkpoint().stage() == RagKnowledgeBaseDeleteStage.RECEIVED) {
            task = advance(task, leaseOwner, new RagKnowledgeBaseDeleteCheckpoint(
                    RagKnowledgeBaseDeleteStage.DELETING_DOCUMENTS,
                    task.checkpoint().totalDocuments(), 0, null));
        }
        if (task.checkpoint().stage() == RagKnowledgeBaseDeleteStage.DELETING_DOCUMENTS) {
            task = processDocuments(task, leaseOwner);
            if (task.status() != cn.bugstack.ai.domain.rag.model.valobj.RagKnowledgeBaseDeleteStatus.RUNNING) {
                return;
            }
        }
        if (task.checkpoint().stage() != RagKnowledgeBaseDeleteStage.VERIFYING) {
            throw new AppException("RAG_KB_DELETE_STAGE_INVALID", "知识库删除任务阶段非法");
        }
        task = barrier(task, leaseOwner);
        deletionRepository.completeClaimed(task.tenantId(), task.taskId(), task.revision(),
                leaseOwner, task.fencingToken(), clock.instant());
    }

    private RagKnowledgeBaseDeleteTaskEntity processDocuments(RagKnowledgeBaseDeleteTaskEntity task,
                                                                String leaseOwner) {
        List<RagDocumentEntity> documents = repository.listDocuments(task.tenantId(), task.knowledgeBaseId());
        if (documents.size() != task.checkpoint().totalDocuments()) {
            throw new AppException("RAG_KB_DELETE_DOCUMENT_SET_CHANGED", "知识库文档集合已变化");
        }
        int completed = 0;
        for (RagDocumentEntity document : documents) {
            task = barrier(task, leaseOwner);
            RagIngestJobEntity child = documentDeletionService.ensureCascadeDeletion(
                    task.tenantId(), task.knowledgeBaseId(), document.documentId());
            RagDocumentEntity latest = repository.findDocument(task.tenantId(), document.documentId())
                    .orElseThrow(() -> new AppException("RAG_DOCUMENT_NOT_FOUND", "级联删除文档不存在"));
            if (child.status() == RagIngestJobStatus.COMPLETED && latest.status() == RagDocumentStatus.DELETED) {
                completed++;
                continue;
            }
            RagKnowledgeBaseDeleteCheckpoint waiting = new RagKnowledgeBaseDeleteCheckpoint(
                    RagKnowledgeBaseDeleteStage.DELETING_DOCUMENTS, documents.size(), completed,
                    document.documentId());
            Instant now = clock.instant();
            RagKnowledgeBaseDeleteTaskEntity target = task.waitForChild(leaseOwner,
                    task.fencingToken(), now, now.plusMillis(properties.getWorker().getPollDelayMs()), waiting);
            updateClaimed(task, target, leaseOwner, now);
            return target;
        }
        return advance(task, leaseOwner, new RagKnowledgeBaseDeleteCheckpoint(
                RagKnowledgeBaseDeleteStage.VERIFYING, documents.size(), completed, null));
    }

    private RagKnowledgeBaseDeleteTaskEntity barrier(RagKnowledgeBaseDeleteTaskEntity expected,
                                                       String leaseOwner) {
        Instant now = clock.instant();
        RagKnowledgeBaseDeleteTaskEntity current = deletionRepository.findByTaskId(
                        expected.tenantId(), expected.taskId()).orElseThrow(() ->
                new AppException("RAG_KB_DELETE_TASK_NOT_FOUND", "知识库删除任务不存在"));
        current.assertClaim(leaseOwner, expected.fencingToken(), now);
        if (deletionRepository.heartbeat(current.tenantId(), current.taskId(), leaseOwner,
                current.fencingToken(), now, now.plus(leaseDuration())) != 1) {
            throw new AppException("RAG_KB_DELETE_FENCE_LOST", "知识库删除租约续期失败");
        }
        return deletionRepository.findByTaskId(current.tenantId(), current.taskId()).orElseThrow();
    }

    private RagKnowledgeBaseDeleteTaskEntity advance(RagKnowledgeBaseDeleteTaskEntity current,
                                                       String leaseOwner,
                                                       RagKnowledgeBaseDeleteCheckpoint checkpoint) {
        Instant now = clock.instant();
        RagKnowledgeBaseDeleteTaskEntity target = current.advance(
                leaseOwner, current.fencingToken(), now, checkpoint);
        updateClaimed(current, target, leaseOwner, now);
        return deletionRepository.findByTaskId(current.tenantId(), current.taskId()).orElse(target);
    }

    private void updateClaimed(RagKnowledgeBaseDeleteTaskEntity current,
                               RagKnowledgeBaseDeleteTaskEntity target,
                               String leaseOwner, Instant now) {
        if (deletionRepository.updateClaimed(current.tenantId(), target, current.revision(),
                leaseOwner, current.fencingToken(), now) != 1) {
            throw new AppException("RAG_KB_DELETE_FENCE_LOST", "知识库删除检查点提交失败");
        }
    }

    private void handleFailure(String tenantId, String taskId, String leaseOwner,
                               long fence, Exception error) {
        RagKnowledgeBaseDeleteTaskEntity latest = deletionRepository.findByTaskId(tenantId, taskId)
                .orElse(null);
        if (latest == null || latest.lease() == null || !leaseOwner.equals(latest.lease().owner())
                || latest.fencingToken() != fence || latest.status().terminal()) return;
        Instant now = clock.instant();
        RagIngestErrorClassifier.Failure failure = errorClassifier.classify(error);
        RagKnowledgeBaseDeleteTaskEntity failed = latest.fail(leaseOwner, fence, now,
                failure.retryable(), failure.retryable() ? now.plus(retryDelay(latest.attemptCount())) : null,
                failure.code(), failure.safeMessage());
        deletionRepository.updateClaimed(tenantId, failed, latest.revision(), leaseOwner, fence, now);
    }

    private Duration leaseDuration() {
        return Duration.ofMillis(properties.getWorker().getLeaseDurationMs());
    }

    private Duration retryDelay(int attempts) {
        long base = properties.getWorker().getRetryBaseDelayMs();
        long max = properties.getWorker().getRetryMaxDelayMs();
        long factor = 1L << Math.min(Math.max(0, attempts - 1), 16);
        return Duration.ofMillis(Math.min(max, base * factor));
    }
}
