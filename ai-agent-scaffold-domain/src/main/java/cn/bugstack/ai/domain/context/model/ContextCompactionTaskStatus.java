package cn.bugstack.ai.domain.context.model;

/** 上下文压缩任务状态。 */
public enum ContextCompactionTaskStatus { PENDING, PROCESSING, SUCCEEDED, RETRYING, DEAD, CANCEL_REQUESTED, STALE }
