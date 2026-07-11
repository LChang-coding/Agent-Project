package cn.bugstack.ai.domain.context.adapter.port;

import cn.bugstack.ai.domain.context.model.ContextCompactionCommand;

/**
 * 上下文压缩命令发布端口。
 */
public interface ContextCompactionPublisher {

    /**
     * 发布压缩命令；参数是任务命令；无返回值。
     */
    void publish(ContextCompactionCommand command);
}
