package cn.bugstack.ai.domain.run.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 本机活动运行注册表。
 * <p>数据库保存最终状态，本注册表只用于加速中断当前进程中的订阅。</p>
 */
@Component
public class ActiveRunRegistry {

    private final ConcurrentHashMap<String, Runnable> interrupters = new ConcurrentHashMap<>();

    /**
     * 注册中断动作；参数是运行ID和动作；无返回值。
     */
    public void register(String runId, Runnable interrupter) {
        if (runId != null && interrupter != null) {
            interrupters.put(runId, interrupter);
        }
    }

    /**
     * 移除运行；参数是运行ID；无返回值。
     */
    public void remove(String runId) {
        if (runId != null) {
            interrupters.remove(runId);
        }
    }

    /**
     * 中断本机运行；参数是运行ID；返回是否找到活动运行。
     */
    public boolean interrupt(String runId) {
        Runnable interrupter = runId == null ? null : interrupters.remove(runId);
        if (interrupter == null) {
            return false;
        }
        interrupter.run();
        return true;
    }
}
