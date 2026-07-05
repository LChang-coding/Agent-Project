package cn.bugstack.ai.types.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AiLog {

    private static final Logger OBSERVABILITY_LOG = LoggerFactory.getLogger("observability");

    private static final AppLog APP_LOG = new AppLog();
    private static final HttpLog HTTP_LOG = new HttpLog();
    private static final ChatLog CHAT_LOG = new ChatLog();
    private static final ModelLog MODEL_LOG = new ModelLog();
    private static final DbLog DB_LOG = new DbLog();
    private static final AuthLog AUTH_LOG = new AuthLog();
    private static final WorkflowLog WORKFLOW_LOG = new WorkflowLog();
    private static final RedisLog REDIS_LOG = new RedisLog();
    private static final RagLog RAG_LOG = new RagLog();
    private static final OssLog OSS_LOG = new OssLog();
    private static final ToolLog TOOL_LOG = new ToolLog();
    private static final SchedulerLog SCHEDULER_LOG = new SchedulerLog();

    /**
     * 禁止创建工具类实例；无参数；无返回值。
     */
    private AiLog() {
    }

    /**
     * 打印信息日志；参数是结构化日志记录；无返回值。
     */
    public static void info(AiLogRecord record) {
        OBSERVABILITY_LOG.info(record.toLogfmt());
    }

    /**
     * 打印错误日志；参数是结构化日志记录；无返回值。
     */
    public static void error(AiLogRecord record) {
        OBSERVABILITY_LOG.error(record.toLogfmt());
    }

    /**
     * 获取应用日志工具；无参数；返回应用日志构造器。
     */
    public static AppLog app() {
        return APP_LOG;
    }

    /**
     * 获取 HTTP 日志工具；无参数；返回 HTTP 日志构造器。
     */
    public static HttpLog http() {
        return HTTP_LOG;
    }

    /**
     * 获取聊天日志工具；无参数；返回聊天日志构造器。
     */
    public static ChatLog chat() {
        return CHAT_LOG;
    }

    /**
     * 获取模型日志工具；无参数；返回模型日志构造器。
     */
    public static ModelLog model() {
        return MODEL_LOG;
    }

    /**
     * 获取数据库日志工具；无参数；返回数据库日志构造器。
     */
    public static DbLog db() {
        return DB_LOG;
    }

    /**
     * 获取认证日志工具；无参数；返回认证日志构造器。
     */
    public static AuthLog auth() {
        return AUTH_LOG;
    }

    /**
     * 获取工作流日志工具；无参数；返回工作流日志构造器。
     */
    public static WorkflowLog workflow() {
        return WORKFLOW_LOG;
    }

    /**
     * 获取 Redis 日志工具；无参数；返回 Redis 日志构造器。
     */
    public static RedisLog redis() {
        return REDIS_LOG;
    }

    /**
     * 获取 RAG 日志工具；无参数；返回 RAG 日志构造器。
     */
    public static RagLog rag() {
        return RAG_LOG;
    }

    /**
     * 获取 OSS 日志工具；无参数；返回 OSS 日志构造器。
     */
    public static OssLog oss() {
        return OSS_LOG;
    }

    /**
     * 获取工具日志工具；无参数；返回工具日志构造器。
     */
    public static ToolLog tool() {
        return TOOL_LOG;
    }

    /**
     * 获取调度日志工具；无参数；返回调度日志构造器。
     */
    public static SchedulerLog scheduler() {
        return SCHEDULER_LOG;
    }
}
