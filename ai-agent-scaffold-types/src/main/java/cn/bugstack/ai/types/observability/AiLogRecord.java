package cn.bugstack.ai.types.observability;

import cn.bugstack.ai.types.context.TenantContextHolder;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class AiLogRecord {

    private final LinkedHashMap<String, Object> fields = new LinkedHashMap<>();

    private AiLogRecord(AiLogEvent event) {
        field(AiLogFields.LOG_ID, UUID.randomUUID().toString());
        field(AiLogFields.TRACE_ID, TraceContext.currentOrNewTraceId());
        field(AiLogFields.TENANT_ID, TenantContextHolder.getTenantId());
        field(AiLogFields.USER_ID, TenantContextHolder.getUserId());
        field(AiLogFields.EVENT, event.code());
        field(AiLogFields.DOMAIN, event.domain().code());
    }

    public static AiLogRecord event(AiLogEvent event) {
        return new AiLogRecord(event);
    }

    public AiLogRecord field(String key, Object value) {
        if (value != null) {
            fields.put(key, value);
        }
        return this;
    }

    public AiLogRecord nullableField(String key, Object value) {
        fields.put(key, value);
        return this;
    }

    public AiLogRecord error(Throwable throwable) {
        if (throwable == null) {
            return this;
        }
        return field(AiLogFields.ERROR_TYPE, throwable.getClass().getSimpleName())
                .field(AiLogFields.ERROR_MESSAGE, Logfmt.truncate(throwable.getMessage(), 512));
    }

    public Map<String, Object> fields() {
        return Map.copyOf(fields);
    }

    public String toLogfmt() {
        return Logfmt.format(fields);
    }
}
