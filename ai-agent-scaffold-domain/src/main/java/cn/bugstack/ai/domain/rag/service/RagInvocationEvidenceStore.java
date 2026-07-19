package cn.bugstack.ai.domain.rag.service;

import cn.bugstack.ai.domain.context.model.RagContextEvidence;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;

/** 模型调用期间按可信业务范围隔离的有界 RAG 证据仓。 */
@Service
public class RagInvocationEvidenceStore {
    private static final int MAX_SCOPES = 2_000;
    private static final int MAX_INVOCATIONS_PER_SCOPE = 128;
    private static final int MAX_CITATIONS_PER_INVOCATION = 128;
    private static final long TTL_MILLIS = Duration.ofMinutes(30).toMillis();
    private final ConcurrentHashMap<Scope, ScopeEvidence> scopes = new ConcurrentHashMap<>();

    /** 记录一次实际模型注入；参数来自可信运行状态。 */
    public void record(String tenantId, String userId, String sessionId, String runId, String invocationId,
                       List<RagContextEvidence> evidence) {
        Scope scope = Scope.of(tenantId, userId, sessionId, runId);
        requireText(invocationId, "模型调用ID");
        List<RagContextEvidence> safe = evidence == null ? List.of() : List.copyOf(evidence);
        int count = safe.stream().mapToInt(item -> item == null ? 0 : item.citations().size()).sum();
        if (count > MAX_CITATIONS_PER_INVOCATION) throw new IllegalStateException("单次模型调用RAG引用超过上限");
        evictExpired();
        if (!scopes.containsKey(scope) && scopes.size() >= MAX_SCOPES) throw new IllegalStateException("RAG运行证据仓已满");
        scopes.compute(scope, (key, current) -> {
            ScopeEvidence value = current == null ? new ScopeEvidence() : current;
            value.put(invocationId, safe);
            return value;
        });
    }

    /** 获取当前运行全部实际注入证据的稳定快照。 */
    public List<RagContextEvidence> snapshot(String tenantId, String userId, String sessionId, String runId) {
        ScopeEvidence value = scopes.get(Scope.of(tenantId, userId, sessionId, runId));
        return value == null ? List.of() : value.snapshot();
    }

    /** 获取一次指定模型调用实际注入的证据。 */
    public List<RagContextEvidence> snapshotInvocation(String tenantId, String userId, String sessionId,
                                                       String runId, String invocationId) {
        ScopeEvidence value = scopes.get(Scope.of(tenantId, userId, sessionId, runId));
        return value == null ? List.of() : value.snapshotInvocation(invocationId);
    }

    /** 清理终态运行证据。 */
    public void clear(String tenantId, String userId, String sessionId, String runId) {
        scopes.remove(Scope.of(tenantId, userId, sessionId, runId));
    }

    private void evictExpired() {
        long cutoff = System.currentTimeMillis() - TTL_MILLIS;
        scopes.entrySet().removeIf(entry -> entry.getValue().updatedAt < cutoff);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + "不能为空");
    }

    private record Scope(String tenantId, String userId, String sessionId, String runId) {
        private static Scope of(String tenantId, String userId, String sessionId, String runId) {
            requireText(tenantId, "租户ID"); requireText(userId, "用户ID");
            requireText(sessionId, "会话ID"); requireText(runId, "运行ID");
            return new Scope(tenantId, userId, sessionId, runId);
        }
    }

    private static final class ScopeEvidence {
        private final Map<String, InvocationEvidence> invocations = new ConcurrentHashMap<>();
        private volatile long updatedAt = System.currentTimeMillis();

        private void put(String invocationId, List<RagContextEvidence> evidence) {
            if (!invocations.containsKey(invocationId) && invocations.size() >= MAX_INVOCATIONS_PER_SCOPE) {
                throw new IllegalStateException("单次运行模型调用次数超过RAG证据上限");
            }
            invocations.compute(invocationId, (key, previous) -> new InvocationEvidence(invocationId,
                    merge(previous == null ? List.of() : previous.evidence, evidence)));
            updatedAt = System.currentTimeMillis();
        }

        private List<RagContextEvidence> merge(List<RagContextEvidence> existing,
                                               List<RagContextEvidence> incoming) {
            Map<String, RagContextEvidence> retrievals = new LinkedHashMap<>();
            Map<String, RagContextEvidence.CitationReference> citations = new LinkedHashMap<>();
            for (RagContextEvidence item : java.util.stream.Stream.concat(existing.stream(), incoming.stream()).toList()) {
                if (item == null) continue;
                RagContextEvidence previous = retrievals.putIfAbsent(item.retrievalId(), item);
                if (previous != null && !previous.equals(item)) {
                    throw new IllegalStateException("同一检索ID的RAG证据发生冲突");
                }
                for (RagContextEvidence.CitationReference citation : item.citations()) {
                    RagContextEvidence.CitationReference prior = citations.putIfAbsent(citation.citationId(), citation);
                    if (prior != null && !prior.equals(citation)) {
                        throw new IllegalStateException("同一引用ID的RAG证据发生冲突");
                    }
                }
            }
            if (citations.size() > MAX_CITATIONS_PER_INVOCATION) {
                throw new IllegalStateException("单次模型调用RAG引用累计超过上限");
            }
            return List.copyOf(retrievals.values());
        }

        private List<RagContextEvidence> snapshot() {
            List<RagContextEvidence> result = new ArrayList<>();
            invocations.values().stream().sorted(Comparator.comparing(InvocationEvidence::invocationId))
                    .forEach(item -> result.addAll(item.evidence));
            return List.copyOf(result);
        }

        private List<RagContextEvidence> snapshotInvocation(String invocationId) {
            InvocationEvidence value = invocations.get(invocationId);
            return value == null ? List.of() : value.evidence;
        }
    }

    private record InvocationEvidence(String invocationId, List<RagContextEvidence> evidence) { }
}
