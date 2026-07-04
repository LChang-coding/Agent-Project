package cn.bugstack.ai.types.observability;

public enum AiLogEvent {

    APP_START(AiLogDomain.APP, "app_start"),

    HTTP_REQUEST(AiLogDomain.HTTP, "http_request"),
    HTTP_ERROR(AiLogDomain.HTTP, "http_error"),

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

    REDIS_COMMAND(AiLogDomain.REDIS, "redis_command"),
    REDIS_ERROR(AiLogDomain.REDIS, "redis_error"),

    RAG_RETRIEVE(AiLogDomain.RAG, "rag_retrieve"),
    RAG_ERROR(AiLogDomain.RAG, "rag_error"),

    OSS_UPLOAD(AiLogDomain.OSS, "oss_upload"),
    OSS_DOWNLOAD(AiLogDomain.OSS, "oss_download"),
    OSS_ERROR(AiLogDomain.OSS, "oss_error"),

    SCHEDULER_DONE(AiLogDomain.SCHEDULER, "scheduler_done"),
    SCHEDULER_ERROR(AiLogDomain.SCHEDULER, "scheduler_error");

    private final AiLogDomain domain;
    private final String code;

    AiLogEvent(AiLogDomain domain, String code) {
        this.domain = domain;
        this.code = code;
    }

    public AiLogDomain domain() {
        return domain;
    }

    public String code() {
        return code;
    }
}
