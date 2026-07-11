package cn.bugstack.ai.domain.context.adapter.repository;

import cn.bugstack.ai.domain.context.model.ContextCompactionTaskEntity;
import cn.bugstack.ai.domain.context.model.ContextTaskCreateCommand;

import java.util.List;

/**
 * 上下文压缩任务仓储。
 */
public interface IContextCompactionTaskRepository {

    /**
     * 创建幂等压缩任务；参数是创建命令；返回新建或已存在任务。
     */
    ContextCompactionTaskEntity createIfAbsent(ContextTaskCreateCommand command);

    /**
     * 查询压缩任务；参数是任务ID；返回任务或空。
     */
    ContextCompactionTaskEntity queryByTaskId(String taskId);

    /**
     * 查询会话未完成任务；参数是会话身份；返回待重投任务。
     */
    List<ContextCompactionTaskEntity> queryUnfinished(String tenantId, String userId, String sessionId);

    /**
     * 领取任务；参数是任务ID；成功返回 true。
     */
    boolean claim(String taskId);

    /**
     * 完成任务；参数是任务ID；返回影响行数。
     */
    int complete(String taskId);

    /**
     * 标记任务重试；参数是任务ID和错误摘要；返回影响行数。
     */
    int retry(String taskId, String errorMessage);

    /**
     * 标记任务进入死信；参数是任务ID和错误摘要；返回影响行数。
     */
    int dead(String taskId, String errorMessage);
}
