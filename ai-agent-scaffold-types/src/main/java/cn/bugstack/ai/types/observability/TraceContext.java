package cn.bugstack.ai.types.observability;

import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.UUID;
import java.util.regex.Pattern;

public final class TraceContext {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String TRACE_ID_MDC_KEY = "traceId";
    public static final String LEGACY_TRACE_ID_MDC_KEY = "trace-id";
    public static final String TRACE_ID_STATE_KEY = "_observability_trace_id";
    private static final Pattern SAFE_TRACE_ID = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();

    private TraceContext() {
    }

    public static String ensureTraceId() {
        String traceId = getTraceId();
        if (traceId == null || traceId.isBlank()) {
            traceId = newTraceId();
            setTraceId(traceId);
        }
        return traceId;
    }

    public static String currentOrNewTraceId() {
        String traceId = getTraceId();
        return traceId == null || traceId.isBlank() ? newTraceId() : traceId;
    }

    public static String getTraceId() {
        String traceId = TRACE_ID.get();
        if (traceId != null && !traceId.isBlank()) {
            return traceId;
        }

        traceId = MDC.get(TRACE_ID_MDC_KEY);
        if (traceId != null && !traceId.isBlank()) {
            TRACE_ID.set(traceId);
            return traceId;
        }

        traceId = MDC.get(LEGACY_TRACE_ID_MDC_KEY);
        if (traceId != null && !traceId.isBlank()) {
            TRACE_ID.set(traceId);
            return traceId;
        }

        return null;
    }

    public static void setTraceId(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            clear();
            return;
        }
        TRACE_ID.set(traceId);
        MDC.put(TRACE_ID_MDC_KEY, traceId);
        MDC.put(LEGACY_TRACE_ID_MDC_KEY, traceId);
    }

    public static String newTraceId() {
        return UUID.randomUUID().toString();
    }

    /** 校验外部链路ID；非法、过长或可注入日志的值会替换为服务端ID。 */
    public static String normalizeOrNew(String candidate) {
        if (candidate == null) return newTraceId();
        String normalized = candidate.trim();
        return SAFE_TRACE_ID.matcher(normalized).matches() ? normalized : newTraceId();
    }

    public static Runnable wrap(Runnable task) {
        String capturedTraceId = getTraceId();
        Map<String, String> capturedMdc = MDC.getCopyOfContextMap();
        return () -> runWithCapturedContext(capturedTraceId, capturedMdc, task);
    }

    public static <T> Callable<T> wrap(Callable<T> task) {
        String capturedTraceId = getTraceId();
        Map<String, String> capturedMdc = MDC.getCopyOfContextMap();
        return () -> callWithCapturedContext(capturedTraceId, capturedMdc, task);
    }

    public static void clear() {
        TRACE_ID.remove();
        MDC.remove(TRACE_ID_MDC_KEY);
        MDC.remove(LEGACY_TRACE_ID_MDC_KEY);
    }

    private static void runWithCapturedContext(String capturedTraceId, Map<String, String> capturedMdc, Runnable task) {
        TraceSnapshot previous = apply(capturedTraceId, capturedMdc);
        try {
            task.run();
        } finally {
            previous.restore();
        }
    }

    private static <T> T callWithCapturedContext(String capturedTraceId, Map<String, String> capturedMdc, Callable<T> task) throws Exception {
        TraceSnapshot previous = apply(capturedTraceId, capturedMdc);
        try {
            return task.call();
        } finally {
            previous.restore();
        }
    }

    private static TraceSnapshot apply(String traceId, Map<String, String> mdc) {
        TraceSnapshot previous = TraceSnapshot.capture();
        if (mdc == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(mdc);
        }

        if (traceId == null || traceId.isBlank()) {
            TRACE_ID.remove();
            MDC.remove(TRACE_ID_MDC_KEY);
            MDC.remove(LEGACY_TRACE_ID_MDC_KEY);
        } else {
            TRACE_ID.set(traceId);
            MDC.put(TRACE_ID_MDC_KEY, traceId);
            MDC.put(LEGACY_TRACE_ID_MDC_KEY, traceId);
        }
        return previous;
    }

    private static final class TraceSnapshot {
        private final String traceId;
        private final Map<String, String> mdc;

        private TraceSnapshot(String traceId, Map<String, String> mdc) {
            this.traceId = traceId;
            this.mdc = mdc;
        }

        private static TraceSnapshot capture() {
            return new TraceSnapshot(TRACE_ID.get(), MDC.getCopyOfContextMap());
        }

        private void restore() {
            if (mdc == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(mdc);
            }

            if (traceId == null || traceId.isBlank()) {
                TRACE_ID.remove();
            } else {
                TRACE_ID.set(traceId);
                MDC.put(TRACE_ID_MDC_KEY, traceId);
                MDC.put(LEGACY_TRACE_ID_MDC_KEY, traceId);
            }
        }
    }
}
