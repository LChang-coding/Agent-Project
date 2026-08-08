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

    /** 保存级联删除任务、租约和检查点。 */
    private final RagKnowledgeBaseDeletionRepository deletionRepository;
    /** 查询知识库文档及文档删除后的事实状态。 */
    private final IRagRepository repository;
    /** 为每个文档登记或复用异步删除子任务。 */
    private final RagDocumentDeletionService documentDeletionService;
    /** 提供租约、扫描间隔和失败退避配置。 */
    private final RagProperties properties;
    /** 统一生成可测试的租约和状态时间。 */
    private final Clock clock;
    /** 将异常转换为稳定的重试决定和安全错误摘要。 */
    private final RagIngestErrorClassifier errorClassifier = new RagIngestErrorClassifier();

    /** 使用 UTC 时钟创建生产环境协调器。 */
    @Autowired
    public RagKnowledgeBaseDeleteCoordinator(RagKnowledgeBaseDeletionRepository deletionRepository,
                                              IRagRepository repository,
                                              RagDocumentDeletionService documentDeletionService,
                                              RagProperties properties) {
        this(deletionRepository, repository, documentDeletionService, properties, Clock.systemUTC());
    }

    /** 注入可控时钟，供租约和退避测试使用。 */
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

    /** 领取级联任务后执行；未取得租约不调用任何删除逻辑。 */
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

    /** 先逐文档登记/等待删除，再验证全库收口并关闭知识库。 */
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

    /**
     * 逐个确认文档删除子任务。
     * 遇到尚未完成的子任务时保存当前文档检查点并释放执行，等待后续扫描继续。
     */
    private RagKnowledgeBaseDeleteTaskEntity processDocuments(RagKnowledgeBaseDeleteTaskEntity task,
                                                                String leaseOwner) {
        List<RagDocumentEntity> documents = repository.listDocuments(task.tenantId(), task.knowledgeBaseId());
        if (documents.size() != task.checkpoint().totalDocuments()) {
            throw new AppException("RAG_KB_DELETE_DOCUMENT_SET_CHANGED", "知识库文档集合已变化");
        }
        int completed = 0;
        for (RagDocumentEntity document : documents) {
            task = barrier(task, leaseOwner);
            // ensure 操作按文档复用已有删除任务，重复扫描不会创建第二个子任务。
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
            // 子任务尚未完成时把父任务推进到等待状态，避免当前线程持续轮询。
            RagKnowledgeBaseDeleteTaskEntity target = task.waitForChild(leaseOwner,
                    task.fencingToken(), now, now.plusMillis(properties.getWorker().getPollDelayMs()), waiting);
            updateClaimed(task, target, leaseOwner, now);
            return target;
        }
        return advance(task, leaseOwner, new RagKnowledgeBaseDeleteCheckpoint(
                RagKnowledgeBaseDeleteStage.VERIFYING, documents.size(), completed, null));
    }

    /** 每个阶段前续租并重读任务，防止过期 Worker 继续清理。 */
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

    /** 提交新的阶段检查点，并回读数据库中的最新 revision。 */
    private RagKnowledgeBaseDeleteTaskEntity advance(RagKnowledgeBaseDeleteTaskEntity current,
                                                       String leaseOwner,
                                                       RagKnowledgeBaseDeleteCheckpoint checkpoint) {
        Instant now = clock.instant();
        RagKnowledgeBaseDeleteTaskEntity target = current.advance(
                leaseOwner, current.fencingToken(), now, checkpoint);
        updateClaimed(current, target, leaseOwner, now);
        return deletionRepository.findByTaskId(current.tenantId(), current.taskId()).orElse(target);
    }

    /** 使用租约持有者、围栏令牌和 revision 条件提交任务状态。 */
    private void updateClaimed(RagKnowledgeBaseDeleteTaskEntity current,
                               RagKnowledgeBaseDeleteTaskEntity target,
                               String leaseOwner, Instant now) {
        if (deletionRepository.updateClaimed(current.tenantId(), target, current.revision(),
                leaseOwner, current.fencingToken(), now) != 1) {
            throw new AppException("RAG_KB_DELETE_FENCE_LOST", "知识库删除检查点提交失败");
        }
    }

    /** 瞬态错误安排重试，永久错误写终态；两者都保留 checkpoint。 */
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

    /** 读取单次任务领取的租约时长。 */
    private Duration leaseDuration() {
        return Duration.ofMillis(properties.getWorker().getLeaseDurationMs());
    }

    /** 按已执行次数计算有上限的指数退避时间。 */
    private Duration retryDelay(int attempts) {
        long base = properties.getWorker().getRetryBaseDelayMs();
        long max = properties.getWorker().getRetryMaxDelayMs();
        long factor = 1L << Math.min(Math.max(0, attempts - 1), 16);
        return Duration.ofMillis(Math.min(max, base * factor));
    }
}
