package cn.bugstack.ai.types.context;

/** 在内部回调线程上传递原始主运行编号，模型参数无法写入。 */
public final class AgentOrchestrationContextHolder {
    private static final ThreadLocal<String> ROOT_RUN_ID = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> SUMMARY_ONLY = new ThreadLocal<>();
    private static final ThreadLocal<String> RESUME_LEASE_OWNER = new ThreadLocal<>();
    private static final ThreadLocal<Long> RESUME_FENCING_TOKEN = new ThreadLocal<>();
    private AgentOrchestrationContextHolder() { }
    public static void setRootRunId(String runId) { ROOT_RUN_ID.set(runId); }
    public static String getRootRunId() { return ROOT_RUN_ID.get(); }
    public static void setSummaryOnly(boolean summaryOnly) { SUMMARY_ONLY.set(summaryOnly); }
    public static boolean isSummaryOnly() { return Boolean.TRUE.equals(SUMMARY_ONLY.get()); }
    public static void setResumeLease(String owner, long fencingToken) {
        RESUME_LEASE_OWNER.set(owner);
        RESUME_FENCING_TOKEN.set(fencingToken);
    }
    public static String getResumeLeaseOwner() { return RESUME_LEASE_OWNER.get(); }
    public static Long getResumeFencingToken() { return RESUME_FENCING_TOKEN.get(); }
    public static void clear() {
        ROOT_RUN_ID.remove();
        SUMMARY_ONLY.remove();
        RESUME_LEASE_OWNER.remove();
        RESUME_FENCING_TOKEN.remove();
    }
}
