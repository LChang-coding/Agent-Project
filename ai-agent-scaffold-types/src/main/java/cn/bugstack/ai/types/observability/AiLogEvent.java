package cn.bugstack.ai.types.observability;

public enum AiLogEvent {

    APP_START(AiLogDomain.APP, "app_start"),

    HTTP_REQUEST(AiLogDomain.HTTP, "http_request"),
    HTTP_ERROR(AiLogDomain.HTTP, "http_error"),

    CHAT_SESSION_CREATED(AiLogDomain.CHAT, "chat_session_created"),
    CHAT_MESSAGE_SAVED(AiLogDomain.CHAT, "chat_message_saved"),
    CHAT_SESSION_REJECTED(AiLogDomain.CHAT, "chat_session_rejected"),
    CHAT_RAG_SETTING_CHANGED(AiLogDomain.CHAT, "chat_rag_setting_changed"),
    CHAT_RUN_STARTED(AiLogDomain.CHAT, "chat_run_started"),
    CHAT_RUN_COMPLETED(AiLogDomain.CHAT, "chat_run_completed"),
    CHAT_RUN_FAILED(AiLogDomain.CHAT, "chat_run_failed"),
    CONTEXT_ASSEMBLY_STARTED(AiLogDomain.CHAT, "context_assembly_started"),
    CONTEXT_ASSEMBLY_COMPLETED(AiLogDomain.CHAT, "context_assembly_completed"),
    CONTEXT_ASSEMBLY_FAILED(AiLogDomain.CHAT, "context_assembly_failed"),

    MODEL_CALL_STARTED(AiLogDomain.MODEL, "model_call_started"),
    MODEL_CALL(AiLogDomain.MODEL, "model_call"),
    TOKEN_USAGE(AiLogDomain.MODEL, "token_usage"),
    MODEL_ERROR(AiLogDomain.MODEL, "model_error"),

    DB_QUERY(AiLogDomain.DB, "db_query"),
    DB_ERROR(AiLogDomain.DB, "db_error"),

    AUTH_REGISTER_SUCCESS(AiLogDomain.AUTH, "auth_register_success"),
    AUTH_REGISTER_FAILED(AiLogDomain.AUTH, "auth_register_failed"),
    AUTH_LOGIN_SUCCESS(AiLogDomain.AUTH, "auth_login_success"),
    AUTH_LOGIN_FAILED(AiLogDomain.AUTH, "auth_login_failed"),
    AUTH_REFRESH_SUCCESS(AiLogDomain.AUTH, "auth_refresh_success"),
    AUTH_REFRESH_FAILED(AiLogDomain.AUTH, "auth_refresh_failed"),
    AUTH_PASSWORD_CHANGED(AiLogDomain.AUTH, "auth_password_changed"),
    AUTH_PASSWORD_CHANGE_FAILED(AiLogDomain.AUTH, "auth_password_change_failed"),
    AUTH_PROFILE_UPDATED(AiLogDomain.AUTH, "auth_profile_updated"),
    AUTH_PROFILE_UPDATE_FAILED(AiLogDomain.AUTH, "auth_profile_update_failed"),

    WORKFLOW_CREATED(AiLogDomain.WORKFLOW, "workflow_created"),
    WORKFLOW_DRAFT_SAVED(AiLogDomain.WORKFLOW, "workflow_draft_saved"),
    WORKFLOW_PUBLISHED(AiLogDomain.WORKFLOW, "workflow_published"),
    WORKFLOW_RUNTIME_LOADED(AiLogDomain.WORKFLOW, "workflow_runtime_loaded"),
    WORKFLOW_NODE_STARTED(AiLogDomain.WORKFLOW, "workflow_node_started"),
    WORKFLOW_NODE_COMPLETED(AiLogDomain.WORKFLOW, "workflow_node_completed"),
    WORKFLOW_NODE_FAILED(AiLogDomain.WORKFLOW, "workflow_node_failed"),
    WORKFLOW_DAG_COMPLETED(AiLogDomain.WORKFLOW, "workflow_dag_completed"),
    WORKFLOW_RUN_FAILED(AiLogDomain.WORKFLOW, "workflow_run_failed"),
    MODEL_ROUTED(AiLogDomain.WORKFLOW, "model_routed"),

    REDIS_COMMAND(AiLogDomain.REDIS, "redis_command"),
    REDIS_ERROR(AiLogDomain.REDIS, "redis_error"),

    RAG_RETRIEVE(AiLogDomain.RAG, "rag_retrieve"),
    RAG_RETRIEVE_STARTED(AiLogDomain.RAG, "rag_retrieve_started"),
    RAG_STAGE(AiLogDomain.RAG, "rag_stage"),
    RAG_RETRIEVE_DEGRADED(AiLogDomain.RAG, "rag_retrieve_degraded"),
    RAG_INGEST_STARTED(AiLogDomain.RAG, "rag_ingest_started"),
    RAG_INGEST_STAGE_STARTED(AiLogDomain.RAG, "rag_ingest_stage_started"),
    RAG_INGEST_STAGE_COMPLETED(AiLogDomain.RAG, "rag_ingest_stage_completed"),
    RAG_INGEST_COMPLETED(AiLogDomain.RAG, "rag_ingest_completed"),
    RAG_INGEST_FAILED(AiLogDomain.RAG, "rag_ingest_failed"),
    RAG_ERROR(AiLogDomain.RAG, "rag_error"),

    OSS_UPLOAD(AiLogDomain.OSS, "oss_upload"),
    OSS_DOWNLOAD(AiLogDomain.OSS, "oss_download"),
    OSS_ERROR(AiLogDomain.OSS, "oss_error"),

    TOOL_SKILL_PUBLISHED(AiLogDomain.TOOL, "tool_skill_published"),
    TOOL_MCP_PUBLISHED(AiLogDomain.TOOL, "tool_mcp_published"),
    TOOL_STAGE(AiLogDomain.TOOL, "tool_stage"),
    TOOL_CALL_STARTED(AiLogDomain.TOOL, "tool_call_started"),
    TOOL_CALL_SUCCESS(AiLogDomain.TOOL, "tool_call_success"),
    TOOL_CALL_FAILED(AiLogDomain.TOOL, "tool_call_failed"),

    SCHEDULER_DONE(AiLogDomain.SCHEDULER, "scheduler_done"),
    SCHEDULER_ERROR(AiLogDomain.SCHEDULER, "scheduler_error");

    private final AiLogDomain domain;
    private final String code;

    /**
     * 创建日志事件；参数是领域和事件编码；返回枚举实例。
     */
    AiLogEvent(AiLogDomain domain, String code) {
        this.domain = domain;
        this.code = code;
    }

    /**
     * 读取事件领域；无参数；返回日志领域。
     */
    public AiLogDomain domain() {
        return domain;
    }

    /**
     * 读取事件编码；无参数；返回事件编码。
     */
    public String code() {
        return code;
    }

    /**
     * 读取供人类理解的中文事件名称；无参数；返回稳定中文名称。
     */
    public String eventName() {
        return switch (this) {
            case APP_START -> "应用启动完成";
            case HTTP_REQUEST -> "HTTP请求完成";
            case HTTP_ERROR -> "HTTP请求失败";
            case CHAT_SESSION_CREATED -> "会话创建完成";
            case CHAT_MESSAGE_SAVED -> "会话消息已保存";
            case CHAT_SESSION_REJECTED -> "会话访问被拒绝";
            case CHAT_RAG_SETTING_CHANGED -> "会话RAG设置已更新";
            case CHAT_RUN_STARTED -> "会话运行已开始";
            case CHAT_RUN_COMPLETED -> "会话运行已完成";
            case CHAT_RUN_FAILED -> "会话运行失败";
            case CONTEXT_ASSEMBLY_STARTED -> "上下文组装已开始";
            case CONTEXT_ASSEMBLY_COMPLETED -> "上下文组装已完成";
            case CONTEXT_ASSEMBLY_FAILED -> "上下文组装失败";
            case MODEL_CALL_STARTED -> "模型调用已开始";
            case MODEL_CALL -> "模型调用完成";
            case TOKEN_USAGE -> "模型Token用量已记录";
            case MODEL_ERROR -> "模型调用失败";
            case DB_QUERY -> "数据库操作完成";
            case DB_ERROR -> "数据库操作失败";
            case AUTH_REGISTER_SUCCESS -> "用户注册成功";
            case AUTH_REGISTER_FAILED -> "用户注册失败";
            case AUTH_LOGIN_SUCCESS -> "用户登录成功";
            case AUTH_LOGIN_FAILED -> "用户登录失败";
            case AUTH_REFRESH_SUCCESS -> "登录状态续期成功";
            case AUTH_REFRESH_FAILED -> "登录状态续期失败";
            case AUTH_PASSWORD_CHANGED -> "密码修改成功";
            case AUTH_PASSWORD_CHANGE_FAILED -> "密码修改失败";
            case AUTH_PROFILE_UPDATED -> "用户资料更新成功";
            case AUTH_PROFILE_UPDATE_FAILED -> "用户资料更新失败";
            case WORKFLOW_CREATED -> "工作流创建完成";
            case WORKFLOW_DRAFT_SAVED -> "工作流草稿已保存";
            case WORKFLOW_PUBLISHED -> "工作流发布完成";
            case WORKFLOW_RUNTIME_LOADED -> "工作流运行时已加载";
            case WORKFLOW_NODE_STARTED -> "工作流节点已开始";
            case WORKFLOW_NODE_COMPLETED -> "工作流节点已完成";
            case WORKFLOW_NODE_FAILED -> "工作流节点执行失败";
            case WORKFLOW_DAG_COMPLETED -> "工作流执行完成";
            case WORKFLOW_RUN_FAILED -> "工作流执行失败";
            case MODEL_ROUTED -> "模型路由完成";
            case REDIS_COMMAND -> "Redis操作完成";
            case REDIS_ERROR -> "Redis操作失败";
            case RAG_RETRIEVE -> "RAG检索完成";
            case RAG_RETRIEVE_STARTED -> "RAG检索已开始";
            case RAG_STAGE -> "RAG检索阶段";
            case RAG_RETRIEVE_DEGRADED -> "RAG检索发生降级";
            case RAG_INGEST_STARTED -> "RAG文档摄取已开始";
            case RAG_INGEST_STAGE_STARTED -> "RAG摄取阶段已开始";
            case RAG_INGEST_STAGE_COMPLETED -> "RAG摄取阶段已完成";
            case RAG_INGEST_COMPLETED -> "RAG文档摄取已完成";
            case RAG_INGEST_FAILED -> "RAG文档摄取失败";
            case RAG_ERROR -> "RAG检索失败";
            case OSS_UPLOAD -> "对象上传完成";
            case OSS_DOWNLOAD -> "对象下载完成";
            case OSS_ERROR -> "对象存储操作失败";
            case TOOL_SKILL_PUBLISHED -> "Skill发布完成";
            case TOOL_MCP_PUBLISHED -> "MCP发布完成";
            case TOOL_STAGE -> "工具调用阶段";
            case TOOL_CALL_STARTED -> "工具调用已开始";
            case TOOL_CALL_SUCCESS -> "工具调用成功";
            case TOOL_CALL_FAILED -> "工具调用失败";
            case SCHEDULER_DONE -> "定时任务执行完成";
            case SCHEDULER_ERROR -> "定时任务执行失败";
        };
    }
}
