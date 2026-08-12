package cn.bugstack.ai.types.context;

/** 在内部回调线程上传递原始主运行编号，模型参数无法写入。 */
public final class AgentOrchestrationContextHolder {
    private static final ThreadLocal<String> ROOT_RUN_ID = new ThreadLocal<>();
    private AgentOrchestrationContextHolder() { }
    public static void setRootRunId(String runId) { ROOT_RUN_ID.set(runId); }
    public static String getRootRunId() { return ROOT_RUN_ID.get(); }
    public static void clear() { ROOT_RUN_ID.remove(); }
}
