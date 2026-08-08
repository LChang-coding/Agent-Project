package cn.bugstack.ai.infrastructure.rag.client;

import cn.bugstack.ai.domain.rag.adapter.port.SparseEncoderPort;
import cn.bugstack.ai.domain.rag.adapter.port.VectorStorePort;
import cn.bugstack.ai.infrastructure.rag.config.RagProperties;
import cn.bugstack.ai.types.exception.AppException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/** Qdrant 1.18.2 REST named dense+sparse 向量存储适配器。 */
@Component
public class QdrantVectorStoreAdapter implements VectorStorePort {

    static final String DENSE_VECTOR = "dense";
    static final String SPARSE_VECTOR = "sparse";
    static final int DENSE_DIMENSION = 768;
    /** 只把预定义标量字段带回领域层，拒绝任意 payload 注入。 */
    private static final Set<String> TRUSTED_PAYLOAD_FIELDS = Set.of(
            "tenant_id", "kb_id", "document_id", "version_id", "generation", "chunk_id", "point_id");

    /** 提供集合名称、认证、批量、超时、重试和响应大小边界。 */
    private final RagProperties properties;
    /** 构造请求体并严格读取 Qdrant JSON 响应。 */
    private final ObjectMapper objectMapper;
    /** 复用连接发送 Qdrant REST 请求。 */
    private final HttpClient httpClient;
    /** 限制本进程同时占用的 Qdrant 请求数。 */
    private final Semaphore concurrency;
    /** 本进程已验证集合 schema 的快速路径；不替代首次服务端检查。 */
    private volatile boolean collectionReady;

    @Autowired
    /** 按配置创建生产环境 HTTP 客户端和公平并发限制器。 */
    public QdrantVectorStoreAdapter(RagProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, HttpClient.newBuilder()
                .connectTimeout(properties.getQdrant().getTimeout()).build());
    }

    /** 注入可测试 HTTP 客户端，并在启动时校验向量维度和重试配置。 */
    QdrantVectorStoreAdapter(RagProperties properties, ObjectMapper objectMapper, HttpClient httpClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.concurrency = new Semaphore(properties.getQdrant().getMaxConcurrency(), true);
        validateConfiguration();
    }

    /** 幂等创建集合，或严格校验既有 named vector schema。 */
    public void ensureCollection() {
        if (collectionReady) return;
        synchronized (this) {
            if (collectionReady) return;
            JsonResponse current = exchange("GET", collectionPath(), null, null, true);
            if (current.statusCode() == 404) {
                JsonResponse created = exchange("PUT", collectionPath(), null, createCollectionBody(), true);
                if (created.statusCode() != 409) requireOk(created, "RAG_QDRANT_COLLECTION_CREATE_FAILED");
                current = exchange("GET", collectionPath(), null, null, false);
            }
            requireOk(current, "RAG_QDRANT_COLLECTION_READ_FAILED");
            validateCollectionSchema(current.body());
            collectionReady = true;
        }
    }

    @Override
    /** 批量写入前校验租户、版本、向量和 payload。 */
    public void upsert(String tenantId, String versionId, List<VectorPoint> points) {
        requireText(tenantId, "tenantId");
        requireText(versionId, "versionId");
        if (points == null || points.isEmpty()) throw invalid("upsert点不能为空");
        ensureCollection();
        int batchSize = properties.getQdrant().getBatchSize();
        for (int from = 0; from < points.size(); from += batchSize) {
            List<VectorPoint> batch = points.subList(from, Math.min(points.size(), from + batchSize));
            ObjectNode body = objectMapper.createObjectNode();
            ArrayNode rows = body.putArray("points");
            for (VectorPoint point : batch) rows.add(pointNode(tenantId, versionId, point));
            requireOk(exchange("PUT", pointsPath(), "wait=true", body, false),
                    "RAG_QDRANT_UPSERT_FAILED");
        }
    }

    @Override
    /** 以 tenantId + versionId 服务端过滤器删除，禁止跨租户清理。 */
    public void deleteVersion(String tenantId, String versionId) {
        requireText(tenantId, "tenantId");
        requireText(versionId, "versionId");
        ensureCollection();
        ObjectNode body = objectMapper.createObjectNode();
        body.set("filter", tenantVersionFilter(tenantId, versionId));
        requireOk(exchange("POST", pointsPath() + "/delete", "wait=true", body, false),
                "RAG_QDRANT_DELETE_FAILED");
    }

    @Override
    /** 精确统计租户指定版本的向量点数量，用于写入后完整性校验。 */
    public long countVersion(String tenantId, String versionId) {
        requireText(tenantId, "tenantId");
        requireText(versionId, "versionId");
        ensureCollection();
        ObjectNode body = objectMapper.createObjectNode();
        body.set("filter", tenantVersionFilter(tenantId, versionId));
        body.put("exact", true);
        JsonResponse response = exchange("POST", pointsPath() + "/count", null, body, false);
        requireOk(response, "RAG_QDRANT_COUNT_FAILED");
        JsonNode count = response.body().path("result").path("count");
        if (!count.canConvertToLong() || count.asLong() < 0) throw responseInvalid("统计结果缺失");
        return count.asLong();
    }

    @Override
    /** 分页读取版本点的可信身份快照，用于删除或重试前核对向量范围。 */
    public List<VectorPointSnapshot> listVersionPointSnapshots(String tenantId, String versionId) {
        requireText(tenantId, "tenantId");
        requireText(versionId, "versionId");
        ensureCollection();
        List<VectorPointSnapshot> result = new ArrayList<>();
        Set<String> pointIds = new java.util.HashSet<>();
        Set<String> visitedOffsets = new java.util.HashSet<>();
        JsonNode offset = null;
        do {
            ObjectNode body = objectMapper.createObjectNode();
            body.set("filter", tenantVersionFilter(tenantId, versionId));
            body.put("limit", Math.max(1, properties.getQdrant().getBatchSize()));
            body.put("with_payload", true);
            body.put("with_vector", false);
            if (offset != null && !offset.isNull()) body.set("offset", offset);
            JsonResponse response = exchange("POST", pointsPath() + "/scroll", null, body, false);
            requireOk(response, "RAG_QDRANT_SCROLL_FAILED");
            JsonNode rows = response.body().path("result").path("points");
            if (!rows.isArray()) throw responseInvalid("版本点快照列表缺失");
            for (JsonNode row : rows) {
                JsonNode payload = row.path("payload");
                if (!tenantId.equals(requiredText(payload, "tenant_id"))
                        || !versionId.equals(requiredText(payload, "version_id"))) {
                    throw new AppException("RAG_QDRANT_SCOPE_VIOLATION",
                            "Qdrant版本点快照超出可信租户或版本范围");
                }
                String pointId = requiredText(payload, "point_id");
                if (!pointIds.add(pointId)) throw responseInvalid("版本点快照包含重复point_id");
                result.add(new VectorPointSnapshot(pointId, requiredText(payload, "chunk_id"),
                        requiredText(payload, "content_hash")));
                if (result.size() > 1_000_000) throw responseInvalid("单版本向量点数量超过核验上限");
            }
            offset = response.body().path("result").get("next_page_offset");
            if (offset != null && !offset.isNull() && !visitedOffsets.add(offset.toString())) {
                throw responseInvalid("版本点快照分页游标重复");
            }
        } while (offset != null && !offset.isNull());
        return List.copyOf(result);
    }

    @Override
    /** 多路查询始终附带租户、知识库和 generation 过滤器。 */
    public List<VectorSearchHit> search(String tenantId, VectorSearchCommand command) {
        requireText(tenantId, "tenantId");
        if (command == null || command.topK() > properties.getQdrant().getMaxSearchTopK()) {
            throw invalid("检索 topK 超过上限");
        }
        long distinctKnowledgeBases = command.scopes().stream().map(KnowledgeBaseScope::knowledgeBaseId)
                .distinct().count();
        if (distinctKnowledgeBases != command.scopes().size()) throw invalid("同一知识库不能提供多个活动代次");
        validateDense(command.denseVector(), true);
        ensureCollection();
        ObjectNode filter = scopedFilter(tenantId, command.scopes());
        ObjectNode body = queryBody(command, filter);
        JsonResponse response = exchange("POST", pointsPath() + "/query", null, body, false);
        requireOk(response, "RAG_QDRANT_SEARCH_FAILED");
        return readHits(tenantId, command, response.body());
    }

    /** 构造同时包含稠密向量和稀疏向量的集合定义。 */
    private ObjectNode createCollectionBody() {
        ObjectNode body = objectMapper.createObjectNode();
        ObjectNode dense = body.putObject("vectors").putObject(DENSE_VECTOR);
        dense.put("size", DENSE_DIMENSION);
        dense.put("distance", "Cosine");
        body.putObject("sparse_vectors").putObject(SPARSE_VECTOR)
                .putObject("index").put("on_disk", properties.getQdrant().isSparseOnDisk());
        return body;
    }

    /** 校验既有集合的向量名称、维度和距离算法，拒绝向不兼容集合写入。 */
    private void validateCollectionSchema(JsonNode response) {
        JsonNode params = response.path("result").path("config").path("params");
        JsonNode vectors = params.path("vectors");
        JsonNode sparseVectors = params.path("sparse_vectors");
        JsonNode dense = vectors.path(DENSE_VECTOR);
        JsonNode sparse = sparseVectors.path(SPARSE_VECTOR);
        boolean exactNames = vectors.isObject() && vectors.size() == 1 && sparseVectors.isObject()
                && sparseVectors.size() == 1;
        boolean denseValid = dense.path("size").asInt(-1) == DENSE_DIMENSION
                && "cosine".equalsIgnoreCase(dense.path("distance").asText());
        boolean sparseValid = sparse.isObject()
                && sparse.path("index").path("on_disk").asBoolean(false)
                == properties.getQdrant().isSparseOnDisk();
        if (!exactNames || !denseValid || !sparseValid) {
            throw new AppException("RAG_QDRANT_SCHEMA_MISMATCH",
                    "Qdrant集合schema与 dense-768-Cosine + sparse 配置不一致");
        }
    }

    /** 将业务向量点转换为 Qdrant point，并写入可信检索范围字段。 */
    private ObjectNode pointNode(String tenantId, String versionId, VectorPoint point) {
        if (point == null || !versionId.equals(point.versionId())) throw invalid("向量点版本越界");
        validateDense(point.denseVector(), false);
        ObjectNode row = objectMapper.createObjectNode();
        row.put("id", qdrantPointId(point.pointId()));
        ObjectNode vectors = row.putObject("vector");
        vectors.set(DENSE_VECTOR, objectMapper.valueToTree(point.denseVector()));
        if (point.sparseVector() != null) vectors.set(SPARSE_VECTOR, sparseNode(point.sparseVector()));
        ObjectNode payload = row.putObject("payload");
        point.payload().forEach((key, value) -> {
            if (!TRUSTED_PAYLOAD_FIELDS.contains(key)) payload.put(key, value);
        });
        payload.put("tenant_id", tenantId);
        payload.put("kb_id", point.knowledgeBaseId());
        payload.put("document_id", point.documentId());
        payload.put("version_id", versionId);
        payload.put("generation", point.generation());
        payload.put("chunk_id", point.chunkId());
        payload.put("point_id", point.pointId());
        return row;
    }

    /** 按 Dense、Sparse 或 Hybrid 模式构造查询体和预取分支。 */
    private ObjectNode queryBody(VectorSearchCommand command, ObjectNode filter) {
        ObjectNode body = objectMapper.createObjectNode();
        boolean dense = !command.denseVector().isEmpty();
        boolean sparse = command.sparseVector() != null;
        if (dense && sparse) {
            long desired = (long) command.topK() * properties.getQdrant().getPrefetchMultiplier();
            int candidateLimit = (int) Math.min(properties.getQdrant().getMaxSearchTopK(),
                    Math.max(command.topK(), desired));
            ArrayNode prefetch = body.putArray("prefetch");
            prefetch.add(prefetchNode(objectMapper.valueToTree(command.denseVector()), DENSE_VECTOR,
                    filter, candidateLimit));
            prefetch.add(prefetchNode(sparseNode(command.sparseVector()), SPARSE_VECTOR,
                    filter, candidateLimit));
            body.putObject("query").put("fusion", "rrf");
        } else if (dense) {
            body.set("query", objectMapper.valueToTree(command.denseVector()));
            body.put("using", DENSE_VECTOR);
        } else {
            body.set("query", sparseNode(command.sparseVector()));
            body.put("using", SPARSE_VECTOR);
        }
        body.set("filter", filter);
        body.put("limit", command.topK());
        body.put("with_payload", true);
        body.put("with_vector", false);
        return body;
    }

    /** 构造 Hybrid 查询的一路预取请求。 */
    private ObjectNode prefetchNode(JsonNode query, String using, ObjectNode filter, int limit) {
        ObjectNode value = objectMapper.createObjectNode();
        value.set("query", query);
        value.put("using", using);
        value.set("filter", filter.deepCopy());
        value.put("limit", limit);
        return value;
    }

    /** 将稀疏特征的索引和值转换为 Qdrant 稀疏向量格式。 */
    private ObjectNode sparseNode(SparseEncoderPort.SparseVector sparse) {
        ObjectNode node = objectMapper.createObjectNode();
        ArrayNode indices = node.putArray("indices");
        ArrayNode values = node.putArray("values");
        sparse.weights().forEach((index, weight) -> { indices.add(index); values.add(weight); });
        return node;
    }

    /** 构造租户硬过滤，并把每个知识库限定到活动 generation。 */
    /**
     * 构造租户与知识库活动代次过滤条件。
     * 每个知识库必须同时匹配 kb_id 和 generation，防止召回旧版本向量。
     */
    private ObjectNode scopedFilter(String tenantId, Set<KnowledgeBaseScope> scopes) {
        ObjectNode filter = objectMapper.createObjectNode();
        ArrayNode must = filter.putArray("must");
        must.add(match("tenant_id", tenantId));
        ObjectNode alternatives = objectMapper.createObjectNode();
        ArrayNode should = alternatives.putArray("should");
        scopes.stream().sorted(Comparator.comparing(KnowledgeBaseScope::knowledgeBaseId)
                        .thenComparingLong(KnowledgeBaseScope::activeGeneration))
                .forEach(scope -> {
                    ObjectNode pair = objectMapper.createObjectNode();
                    ArrayNode pairMust = pair.putArray("must");
                    pairMust.add(match("kb_id", scope.knowledgeBaseId()));
                    pairMust.add(match("generation", scope.activeGeneration()));
                    should.add(pair);
                });
        must.add(alternatives);
        return filter;
    }

    /** 构造按租户和文档版本精确删除、计数或遍历的过滤条件。 */
    private ObjectNode tenantVersionFilter(String tenantId, String versionId) {
        ObjectNode filter = objectMapper.createObjectNode();
        ArrayNode must = filter.putArray("must");
        must.add(match("tenant_id", tenantId));
        must.add(match("version_id", versionId));
        return filter;
    }

    /** 构造字符串字段精确匹配条件。 */
    private ObjectNode match(String key, String value) {
        ObjectNode condition = objectMapper.createObjectNode();
        condition.put("key", key);
        condition.putObject("match").put("value", value);
        return condition;
    }

    /** 构造整数值字段精确匹配条件。 */
    private ObjectNode match(String key, long value) {
        ObjectNode condition = objectMapper.createObjectNode();
        condition.put("key", key);
        condition.putObject("match").put("value", value);
        return condition;
    }

    /** 校验命中 payload 的租户和业务身份后才映射领域结果。 */
    /**
     * 解析并校验检索命中。
     * 返回载荷必须属于请求租户和允许的知识库代次，否则按越权响应拒绝整批结果。
     */
    private List<VectorSearchHit> readHits(String tenantId, VectorSearchCommand command, JsonNode response) {
        JsonNode rows = response.path("result").path("points");
        if (!rows.isArray() || rows.size() > command.topK()) throw responseInvalid("检索结果数量非法");
        Map<String, Long> scopeMap = new LinkedHashMap<>();
        command.scopes().forEach(scope -> scopeMap.put(scope.knowledgeBaseId(), scope.activeGeneration()));
        List<VectorSearchHit> hits = new ArrayList<>();
        for (JsonNode row : rows) {
            JsonNode payloadNode = row.path("payload");
            String payloadTenant = requiredText(payloadNode, "tenant_id");
            String kbId = requiredText(payloadNode, "kb_id");
            String documentId = requiredText(payloadNode, "document_id");
            String versionId = requiredText(payloadNode, "version_id");
            String chunkId = requiredText(payloadNode, "chunk_id");
            String originalPointId = requiredText(payloadNode, "point_id");
            long generation = payloadNode.path("generation").asLong(-1);
            Double score = row.path("score").isNumber() ? row.path("score").doubleValue() : null;
            if (!tenantId.equals(payloadTenant) || !scopeMap.containsKey(kbId)
                    || scopeMap.get(kbId) != generation) {
                throw new AppException("RAG_QDRANT_SCOPE_VIOLATION", "Qdrant返回了超出可信租户或活动代次的命中");
            }
            if (generation < 1 || score == null || !Double.isFinite(score)) throw responseInvalid("命中字段非法");
            hits.add(new VectorSearchHit(originalPointId, kbId, documentId, versionId, generation,
                    chunkId, score, scalarPayload(payloadNode)));
        }
        return List.copyOf(hits);
    }

    /** 只向领域层暴露标量载荷，忽略嵌套结构和数组。 */
    private Map<String, String> scalarPayload(JsonNode payload) {
        Map<String, String> values = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = payload.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (field.getValue().isValueNode()) values.put(field.getKey(), field.getValue().asText());
        }
        return Map.copyOf(values);
    }

    /** 在总 deadline 内执行带瞬态重试的 Qdrant JSON 协议。 */
    private JsonResponse exchange(String method, String path, String query, JsonNode body, boolean allowNotFound) {
        RagProperties.Qdrant config = properties.getQdrant();
        try {
            URI uri = endpoint(config.getEndpoint(), path, query);
            byte[] requestBytes = body == null ? null : objectMapper.writeValueAsBytes(body);
            if (requestBytes != null && requestBytes.length > config.getMaxRequestBytes()) {
                throw new AppException("RAG_QDRANT_REQUEST_TOO_LARGE", "Qdrant请求超过安全上限");
            }
            long deadlineNanos = System.nanoTime() + config.getTotalTimeout().toNanos();
            int attempt = 0;
            while (true) {
                try {
                    RawResponse response = send(uri, method, requestBytes, deadlineNanos, config);
                    if (allowNotFound && (response.statusCode() == 404 || response.statusCode() == 409)) {
                        return new JsonResponse(response.statusCode(), null);
                    }
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        try {
                            return new JsonResponse(response.statusCode(), objectMapper.readTree(response.body()));
                        } catch (JsonProcessingException e) {
                            throw new AppException("RAG_QDRANT_RESPONSE_INVALID", "Qdrant响应不是合法JSON", e);
                        }
                    }
                    if (!isTransient(response.statusCode()) || attempt >= config.getMaxRetries()) {
                        throw new AppException("RAG_QDRANT_HTTP_ERROR",
                                "Qdrant返回状态 " + response.statusCode() + "，尝试次数 " + (attempt + 1));
                    }
                } catch (IOException e) {
                    if (attempt >= config.getMaxRetries()) {
                        throw new AppException("RAG_QDRANT_UNAVAILABLE",
                                "Qdrant调用失败，尝试次数 " + (attempt + 1), e);
                    }
                }
                backoff(attempt++, deadlineNanos, config);
            }
        } catch (AppException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AppException("RAG_QDRANT_INTERRUPTED", "Qdrant请求被中断", e);
        } catch (Exception e) {
            throw new AppException("RAG_QDRANT_UNAVAILABLE", "Qdrant调用失败", e);
        }
    }

    /** 在剩余总时限内取得并发许可、发送一次请求并限量读取响应。 */
    private RawResponse send(URI uri, String method, byte[] requestBytes, long deadlineNanos,
                             RagProperties.Qdrant config) throws Exception {
        Duration remaining = remaining(deadlineNanos);
        TeiEmbeddingAdapter.acquire(concurrency, remaining, "Qdrant");
        try {
            Duration afterAcquire = remaining(deadlineNanos);
            Duration requestTimeout = afterAcquire.compareTo(config.getTimeout()) < 0
                    ? afterAcquire : config.getTimeout();
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(requestTimeout)
                    .header("Accept", "application/json");
            if (config.getApiKey() != null && !config.getApiKey().isBlank()) {
                builder.header("api-key", config.getApiKey());
            }
            if (requestBytes != null) builder.header("Content-Type", "application/json");
            switch (method) {
                case "GET" -> builder.GET();
                case "PUT" -> builder.PUT(HttpRequest.BodyPublishers.ofByteArray(requestBytes));
                case "POST" -> builder.POST(HttpRequest.BodyPublishers.ofByteArray(requestBytes));
                default -> throw new IllegalArgumentException("不支持的HTTP方法");
            }
            HttpResponse<InputStream> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            return new RawResponse(response.statusCode(),
                    TeiEmbeddingAdapter.readBounded(response.body(), config.getMaxResponseBytes()));
        } finally {
            concurrency.release();
        }
    }

    /** 识别适合在总时限内重试的超时、限流和网关状态。 */
    private boolean isTransient(int statusCode) {
        return statusCode == 408 || statusCode == 429
                || statusCode == 502 || statusCode == 503 || statusCode == 504;
    }

    /** 在总时限内执行有上限的指数退避，预算不足时直接结束。 */
    private void backoff(int retryIndex, long deadlineNanos, RagProperties.Qdrant config)
            throws InterruptedException {
        long multiplier = 1L << Math.min(retryIndex, 30);
        long desiredNanos;
        try {
            desiredNanos = Math.multiplyExact(config.getRetryInitialBackoff().toNanos(), multiplier);
        } catch (ArithmeticException ignored) {
            desiredNanos = Long.MAX_VALUE;
        }
        desiredNanos = Math.min(desiredNanos, config.getRetryMaxBackoff().toNanos());
        long remainingNanos = remaining(deadlineNanos).toNanos();
        if (desiredNanos >= remainingNanos) {
            throw new AppException("RAG_QDRANT_TIMEOUT", "Qdrant操作超过总时限");
        }
        long millis = desiredNanos / 1_000_000L;
        int nanos = (int) (desiredNanos % 1_000_000L);
        Thread.sleep(millis, nanos);
    }

    /** 计算 Qdrant 操作的剩余总预算，耗尽时立即结束。 */
    private Duration remaining(long deadlineNanos) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            throw new AppException("RAG_QDRANT_TIMEOUT", "Qdrant操作超过总时限");
        }
        return Duration.ofNanos(remainingNanos);
    }

    /** 校验 Qdrant 写操作返回明确的 ok 状态。 */
    private void requireOk(JsonResponse response, String code) {
        if (response.body() == null || !"ok".equalsIgnoreCase(response.body().path("status").asText())) {
            throw new AppException(code, "Qdrant响应状态非法");
        }
    }

    /** 拒绝维度不符与非有限数值，防止坏向量入库或查询。 */
    /** 校验稠密向量维度和数值有限性，可选向量允许为空。 */
    private void validateDense(List<Float> vector, boolean optional) {
        if (optional && (vector == null || vector.isEmpty())) return;
        if (vector == null || vector.size() != DENSE_DIMENSION || vector.stream().anyMatch(
                value -> value == null || !Float.isFinite(value))) {
            throw invalid("Dense向量必须为768维有限数");
        }
    }

    /** 校验集合名和向量维度等启动配置，防止发送无效请求。 */
    private void validateConfiguration() {
        RagProperties.Qdrant config = properties.getQdrant();
        if (properties.getEmbedding().getDimension() != DENSE_DIMENSION
                || config.getCollection() == null
                || !config.getCollection().matches("[A-Za-z0-9_-]{1,128}")) {
            throw new AppException("RAG_QDRANT_CONFIG_INVALID", "Qdrant集合名或Dense维度配置非法");
        }
    }

    /** 将业务点 ID 稳定映射为 Qdrant UUID，不改变业务身份。 */
    /** 将业务 pointId 确定性转换为 Qdrant 接受的 UUID。 */
    private String qdrantPointId(String businessPointId) {
        try {
            return UUID.fromString(businessPointId).toString();
        } catch (IllegalArgumentException ignore) {
            return UUID.nameUUIDFromBytes(("rag-qdrant:" + businessPointId).getBytes(StandardCharsets.UTF_8)).toString();
        }
    }

    /** 读取必需文本载荷，缺失时拒绝不可信响应。 */
    private String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        if (value == null || value.isBlank()) throw responseInvalid("命中payload缺少 " + field);
        return value;
    }

    /** 在配置端点之后安全拼接资源路径和可选查询串。 */
    private URI endpoint(URI base, String path, String query) {
        String value = base.toString().replaceAll("/+$", "") + path;
        return URI.create(query == null ? value : value + "?" + query);
    }

    /** 返回当前配置集合的 API 路径。 */
    private String collectionPath() { return "/collections/" + encode(properties.getQdrant().getCollection()); }
    /** 返回当前集合 points 资源的 API 路径。 */
    private String pointsPath() { return collectionPath() + "/points"; }
    /** 对路径片段进行 UTF-8 URL 编码并使用标准空格编码。 */
    private String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
    /** 校验请求必需文本字段。 */
    private void requireText(String value, String field) { if (value == null || value.isBlank()) throw invalid(field + "不能为空"); }
    /** 创建稳定的 Qdrant 请求参数错误。 */
    private AppException invalid(String message) { return new AppException("RAG_QDRANT_REQUEST_INVALID", message); }
    /** 创建稳定的 Qdrant 响应协议错误。 */
    private AppException responseInvalid(String message) { return new AppException("RAG_QDRANT_RESPONSE_INVALID", message); }
    /** 限制大小后的原始 HTTP 响应。 */
    private record RawResponse(int statusCode, byte[] body) {}
    /** 已解析为 JSON 的 Qdrant 响应。 */
    private record JsonResponse(int statusCode, JsonNode body) {}
}
