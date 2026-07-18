package cn.bugstack.ai.infrastructure.rag.persistence;

import cn.bugstack.ai.domain.rag.model.valobj.RagIngestCheckpoint;
import cn.bugstack.ai.domain.rag.model.valobj.RagIngestStage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

/**
 * RAG 持久化 JSON 和枚举的严格编解码器。
 *
 * <p>数据库内容是受审计的业务事实；损坏 JSON 或未知枚举必须显式失败，禁止用默认值掩盖数据问题。</p>
 */
@Component
@RequiredArgsConstructor
public class RagPersistenceCodec {

    private static final TypeReference<Map<String, String>> STRING_MAP_TYPE = new TypeReference<>() { };

    private final ObjectMapper objectMapper;

    /** 将字符串元数据编码为 JSON；空映射编码为空对象。 */
    public String writeMetadata(Map<String, String> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata == null ? Map.of() : metadata);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("RAG 元数据无法序列化", exception);
        }
    }

    /** 从 JSON 读取字符串元数据；数据库 NULL 视为空对象。 */
    public Map<String, String> readMetadata(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, String> value = objectMapper.readValue(json, STRING_MAP_TYPE);
            return value == null ? Map.of() : Map.copyOf(value);
        } catch (JsonProcessingException | ClassCastException exception) {
            throw new IllegalStateException("RAG 元数据 JSON 非法", exception);
        }
    }

    /** 编码摄取检查点。 */
    public String writeCheckpoint(RagIngestCheckpoint checkpoint) {
        if (checkpoint == null) {
            throw new IllegalArgumentException("RAG 摄取检查点不能为空");
        }
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "stage", databaseValue(checkpoint.stage()),
                    "processedChunks", checkpoint.processedChunks(),
                    "totalChunks", checkpoint.totalChunks(),
                    "embeddingBatchIndex", checkpoint.embeddingBatchIndex(),
                    "vectorUpsertIndex", checkpoint.vectorUpsertIndex()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("RAG 摄取检查点无法序列化", exception);
        }
    }

    /** 解码摄取检查点，并要求 JSON 中阶段与独立 stage 列一致。 */
    public RagIngestCheckpoint readCheckpoint(String json, String stageValue) {
        RagIngestStage persistedStage = enumValue(RagIngestStage.class, stageValue, "摄取阶段");
        if (json == null || json.isBlank()) {
            if (persistedStage != RagIngestStage.RECEIVED) {
                throw new IllegalStateException("非初始 RAG 任务缺少 checkpoint");
            }
            return RagIngestCheckpoint.initial();
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            RagIngestStage checkpointStage = enumValue(RagIngestStage.class,
                    requiredText(root, "stage"), "检查点阶段");
            if (persistedStage != checkpointStage) {
                throw new IllegalStateException("RAG task.stage 与 checkpoint.stage 不一致");
            }
            return new RagIngestCheckpoint(checkpointStage,
                    requiredInt(root, "processedChunks"), requiredInt(root, "totalChunks"),
                    requiredInt(root, "embeddingBatchIndex"), requiredInt(root, "vectorUpsertIndex"));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("RAG 摄取检查点 JSON 非法", exception);
        }
    }

    /** 将领域枚举转换为稳定的小写数据库值。 */
    public String databaseValue(Enum<?> value) {
        if (value == null) {
            throw new IllegalArgumentException("RAG 枚举值不能为空");
        }
        return value.name().toLowerCase(Locale.ROOT);
    }

    /** 严格读取数据库枚举；空值或未知值均失败。 */
    public <E extends Enum<E>> E enumValue(Class<E> enumType, String value, String fieldName) {
        if (enumType == null || value == null || value.isBlank()) {
            throw new IllegalStateException(fieldName + "为空");
        }
        try {
            return Enum.valueOf(enumType, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(fieldName + "包含未知值：" + value, exception);
        }
    }

    private String requiredText(JsonNode root, String fieldName) {
        JsonNode value = root == null ? null : root.get(fieldName);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalStateException("RAG checkpoint 缺少字段：" + fieldName);
        }
        return value.textValue();
    }

    private int requiredInt(JsonNode root, String fieldName) {
        JsonNode value = root == null ? null : root.get(fieldName);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalStateException("RAG checkpoint 缺少整数字段：" + fieldName);
        }
        return value.intValue();
    }
}
