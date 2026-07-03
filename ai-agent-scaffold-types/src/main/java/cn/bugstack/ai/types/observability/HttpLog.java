package cn.bugstack.ai.types.observability;

public final class HttpLog {

    HttpLog() {
    }

    public AiLogRecord request(String method,
                               String uri,
                               Integer status,
                               Long costMs,
                               Boolean success) {
        return AiLogRecord.event(AiLogEvent.HTTP_REQUEST)
                .field(AiLogFields.METHOD, method)
                .field(AiLogFields.URI, uri)
                .field(AiLogFields.STATUS, status)
                .field(AiLogFields.COST_MS, costMs)
                .field(AiLogFields.SUCCESS, success);
    }

    public AiLogRecord error(String method,
                             String uri,
                             Integer status,
                             Long costMs,
                             Throwable throwable) {
        return request(method, uri, status, costMs, false)
                .field(AiLogFields.EVENT, AiLogEvent.HTTP_ERROR.code())
                .error(throwable);
    }
}
