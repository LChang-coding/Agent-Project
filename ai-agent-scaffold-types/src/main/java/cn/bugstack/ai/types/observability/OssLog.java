package cn.bugstack.ai.types.observability;

public final class OssLog {

    OssLog() {
    }

    public AiLogRecord upload(String bucket,
                              String objectKey,
                              Long bytes,
                              Long costMs,
                              Boolean success) {
        return file(AiLogEvent.OSS_UPLOAD, bucket, objectKey, bytes, costMs, success);
    }

    public AiLogRecord download(String bucket,
                                String objectKey,
                                Long bytes,
                                Long costMs,
                                Boolean success) {
        return file(AiLogEvent.OSS_DOWNLOAD, bucket, objectKey, bytes, costMs, success);
    }

    public AiLogRecord error(String operation,
                             String bucket,
                             String objectKey,
                             Long costMs,
                             Throwable throwable) {
        return AiLogRecord.event(AiLogEvent.OSS_ERROR)
                .field("operation", operation)
                .field("bucket", bucket)
                .field("objectKey", objectKey)
                .field(AiLogFields.COST_MS, costMs)
                .field(AiLogFields.SUCCESS, false)
                .error(throwable);
    }

    private AiLogRecord file(AiLogEvent event,
                             String bucket,
                             String objectKey,
                             Long bytes,
                             Long costMs,
                             Boolean success) {
        return AiLogRecord.event(event)
                .field("bucket", bucket)
                .field("objectKey", objectKey)
                .field("bytes", bytes)
                .field(AiLogFields.COST_MS, costMs)
                .field(AiLogFields.SUCCESS, success);
    }
}
