package cn.bugstack.ai.types.observability;

public final class AuthLog {

    public AiLogRecord registerSuccess(String tenantId,
                                       String userId,
                                       String username,
                                       String roleCode) {
        return success(AiLogEvent.AUTH_REGISTER_SUCCESS, tenantId, userId, username, roleCode);
    }

    public AiLogRecord registerFailed(String username,
                                      String errorCode,
                                      String errorMessage) {
        return failed(AiLogEvent.AUTH_REGISTER_FAILED, username, errorCode, errorMessage);
    }

    public AiLogRecord loginSuccess(String tenantId,
                                    String userId,
                                    String username,
                                    String roleCode) {
        return success(AiLogEvent.AUTH_LOGIN_SUCCESS, tenantId, userId, username, roleCode);
    }

    public AiLogRecord loginFailed(String username,
                                   String errorCode,
                                   String errorMessage) {
        return failed(AiLogEvent.AUTH_LOGIN_FAILED, username, errorCode, errorMessage);
    }

    private AiLogRecord success(AiLogEvent event,
                                String tenantId,
                                String userId,
                                String username,
                                String roleCode) {
        return AiLogRecord.event(event)
                .field(AiLogFields.TENANT_ID, tenantId)
                .field(AiLogFields.USER_ID, userId)
                .field(AiLogFields.USERNAME, username)
                .field(AiLogFields.ROLE_CODE, roleCode)
                .field(AiLogFields.SUCCESS, true);
    }

    private AiLogRecord failed(AiLogEvent event,
                              String username,
                              String errorCode,
                              String errorMessage) {
        return AiLogRecord.event(event)
                .field(AiLogFields.USERNAME, username)
                .field(AiLogFields.ERROR_CODE, errorCode)
                .field(AiLogFields.ERROR_MESSAGE, errorMessage)
                .field(AiLogFields.SUCCESS, false);
    }
}
