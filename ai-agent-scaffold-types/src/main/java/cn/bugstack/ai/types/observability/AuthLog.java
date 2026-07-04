package cn.bugstack.ai.types.observability;

public final class AuthLog {

    /**
     * 记录注册成功；参数是用户身份；返回日志记录。
     */
    public AiLogRecord registerSuccess(String tenantId,
                                       String userId,
                                       String username,
                                       String roleCode) {
        return success(AiLogEvent.AUTH_REGISTER_SUCCESS, tenantId, userId, username, roleCode);
    }

    /**
     * 记录注册失败；参数是用户名和错误信息；返回日志记录。
     */
    public AiLogRecord registerFailed(String username,
                                      String errorCode,
                                      String errorMessage) {
        return failed(AiLogEvent.AUTH_REGISTER_FAILED, username, errorCode, errorMessage);
    }

    /**
     * 记录登录成功；参数是用户身份；返回日志记录。
     */
    public AiLogRecord loginSuccess(String tenantId,
                                    String userId,
                                    String username,
                                    String roleCode) {
        return success(AiLogEvent.AUTH_LOGIN_SUCCESS, tenantId, userId, username, roleCode);
    }

    /**
     * 记录登录失败；参数是用户名和错误信息；返回日志记录。
     */
    public AiLogRecord loginFailed(String username,
                                   String errorCode,
                                   String errorMessage) {
        return failed(AiLogEvent.AUTH_LOGIN_FAILED, username, errorCode, errorMessage);
    }

    /**
     * 记录续期成功；参数是用户身份；返回日志记录。
     */
    public AiLogRecord refreshSuccess(String tenantId,
                                      String userId,
                                      String username,
                                      String roleCode) {
        return success(AiLogEvent.AUTH_REFRESH_SUCCESS, tenantId, userId, username, roleCode);
    }

    /**
     * 记录续期失败；参数是用户名和错误信息；返回日志记录。
     */
    public AiLogRecord refreshFailed(String username,
                                     String errorCode,
                                     String errorMessage) {
        return failed(AiLogEvent.AUTH_REFRESH_FAILED, username, errorCode, errorMessage);
    }

    /**
     * 记录密码修改成功；参数是用户身份；返回日志记录。
     */
    public AiLogRecord passwordChanged(String tenantId,
                                       String userId,
                                       String username,
                                       String roleCode) {
        return success(AiLogEvent.AUTH_PASSWORD_CHANGED, tenantId, userId, username, roleCode);
    }

    /**
     * 记录密码修改失败；参数是用户名和错误信息；返回日志记录。
     */
    public AiLogRecord passwordChangeFailed(String username,
                                            String errorCode,
                                            String errorMessage) {
        return failed(AiLogEvent.AUTH_PASSWORD_CHANGE_FAILED, username, errorCode, errorMessage);
    }

    /**
     * 记录资料更新成功；参数是用户身份；返回日志记录。
     */
    public AiLogRecord profileUpdated(String tenantId,
                                      String userId,
                                      String username,
                                      String roleCode) {
        return success(AiLogEvent.AUTH_PROFILE_UPDATED, tenantId, userId, username, roleCode);
    }

    /**
     * 记录资料更新失败；参数是用户名和错误信息；返回日志记录。
     */
    public AiLogRecord profileUpdateFailed(String username,
                                           String errorCode,
                                           String errorMessage) {
        return failed(AiLogEvent.AUTH_PROFILE_UPDATE_FAILED, username, errorCode, errorMessage);
    }

    /**
     * 组装成功日志；参数是事件和用户身份；返回日志记录。
     */
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

    /**
     * 组装失败日志；参数是事件、用户名和错误信息；返回日志记录。
     */
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
