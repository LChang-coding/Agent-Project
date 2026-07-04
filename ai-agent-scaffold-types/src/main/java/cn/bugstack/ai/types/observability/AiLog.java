package cn.bugstack.ai.types.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AiLog {

    private static final Logger OBSERVABILITY_LOG = LoggerFactory.getLogger("observability");

    private static final AppLog APP_LOG = new AppLog();
    private static final HttpLog HTTP_LOG = new HttpLog();
    private static final ModelLog MODEL_LOG = new ModelLog();
    private static final DbLog DB_LOG = new DbLog();
    private static final AuthLog AUTH_LOG = new AuthLog();
    private static final RedisLog REDIS_LOG = new RedisLog();
    private static final RagLog RAG_LOG = new RagLog();
    private static final OssLog OSS_LOG = new OssLog();
    private static final SchedulerLog SCHEDULER_LOG = new SchedulerLog();

    private AiLog() {
    }

    public static void info(AiLogRecord record) {
        OBSERVABILITY_LOG.info(record.toLogfmt());
    }

    public static void error(AiLogRecord record) {
        OBSERVABILITY_LOG.error(record.toLogfmt());
    }

    public static AppLog app() {
        return APP_LOG;
    }

    public static HttpLog http() {
        return HTTP_LOG;
    }

    public static ModelLog model() {
        return MODEL_LOG;
    }

    public static DbLog db() {
        return DB_LOG;
    }

    public static AuthLog auth() {
        return AUTH_LOG;
    }

    public static RedisLog redis() {
        return REDIS_LOG;
    }

    public static RagLog rag() {
        return RAG_LOG;
    }

    public static OssLog oss() {
        return OSS_LOG;
    }

    public static SchedulerLog scheduler() {
        return SCHEDULER_LOG;
    }
}
