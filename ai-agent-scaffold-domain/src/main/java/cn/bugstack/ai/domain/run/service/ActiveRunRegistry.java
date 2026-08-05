package cn.bugstack.ai.domain.run.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 本机（当前 JVM）正在推送的运行登记表，作用是让「取消」立刻掐断本机上的订阅。
 *
 * <p>解决什么问题：用户点停止后，数据库里的状态改成已取消只解决了「事实」问题，
 * 但那条还在往浏览器推内容的订阅并不会因为数据库变了就自己停下来。它可能还要继续调模型、继续烧额度。
 * 所以每条正在推送的流都要在这里留一个「怎么把我掐断」的动作，取消接口按 runId 找到它并执行。</p>
 *
 * <p>与数据库的分工：数据库保存运行的最终状态，是跨进程、跨重启都认的唯一事实；
 * 这张表只活在内存里，纯粹是为了「本机能立刻中断」。所以取消流程一定是先落库改状态、
 * 提交之后再来这里中断；如果运行是在另一台机器上跑的，这里找不到条目也不算失败——
 * 那台机器上的执行点会在下一次读数据库状态时自己停下来，只是响应会慢一点。</p>
 *
 * <p>所属层次：领域层运行服务。谁会调用它：SSE 控制器登记与摘除句柄，
 * {@code RunControlService} 在取消 / 引导 / 终结提交后触发中断或清理。</p>
 *
 * <p>它不负责什么：不写数据库、不判断状态机是否允许取消、不保证取消一定成功。
 * 它只做一件事：按 runId 存一个只会被执行一次的中断动作。</p>
 */
@Component
public class ActiveRunRegistry {

    /**
     * runId 到「中断动作」的映射，只保存本 JVM 内可以立即取消的订阅句柄。
     *
     * <p>用并发容器是因为登记、摘除、中断分别发生在 HTTP 线程、流回调线程和取消请求线程上。
     * 条目必须在流结束、连接出错、取消完成时被移除，否则内存里会不断堆积已经没用的句柄。
     * 注意这里没有租户维度：runId 本身全局唯一，且取消入口在到达这里之前已经做过租户与用户校验。</p>
     */
    private final ConcurrentHashMap<String, Runnable> interrupters = new ConcurrentHashMap<>();

    /**
     * 登记一次运行的中断动作。
     *
     * <p>必须在把 runId 告诉前端之前登记，否则前端一拿到 runId 就点停止时会找不到目标，
     * 流会一直跑到底，而界面上已经显示停止了。</p>
     *
     * @param runId       运行编号，作为取消时的查找键
     * @param interrupter 中断动作，通常是「释放订阅 + 关闭 SSE 连接」
     */
    public void register(String runId, Runnable interrupter) {
        // 键或动作缺一个都无法构成可用的取消条目，直接忽略，避免往表里塞空值造成后续空指针。
        if (runId != null && interrupter != null) {
            // 同一 runId 重复登记会覆盖旧条目；正常流程一次运行只登记一次，覆盖意味着上一条已经没用了。
            interrupters.put(runId, interrupter);
        }
    }

    /**
     * 摘除一次运行的登记，不执行中断动作。
     *
     * <p>用于流正常结束、连接出错、运行落入终态之后的清理。只清内存，不碰数据库状态。
     * 漏调会造成内存里残留永远不会被执行的句柄，长期运行下来是内存泄漏。</p>
     */
    public void remove(String runId) {
     // 没有编号就没有条目可清，直接跳过。
        if (runId != null) {
            // 摘掉条目即代表「本机不再有可中断的流」，后续取消请求会返回未找到。
            interrupters.remove(runId);
        }
    }

    /**
  * 中断本机上正在跑的这次运行，并保证中断动作最多只执行一次。
     *
     * <p>并发安全的关键在于「先移除再执行」：remove 是原子操作，同时到达的多个取消请求里
     * 只有一个能拿到非空句柄，其余都会拿到 null 并返回 false。这样即使用户连点五次停止，
     * 也不会出现重复释放订阅、重复关闭连接导致的异常。</p>
     *
     * <p>返回 false 只说明「本机没有这条运行的活动流」，不代表取消失败：
     * 运行可能已经自己结束了，也可能正在另一台机器上执行，那边会靠数据库状态自行收敛。</p>
     *
     * @return true 表示本机确实找到了活动流并执行了中断
     */
    public boolean interrupt(String runId) {
        // 先移除再执行，保证并发取消最多触发一次中断动作。
        Runnable interrupter = runId == null ? null : interrupters.remove(runId);
        // 拿不到句柄说明本机没有这条运行的活动流，交由调用方决定是否还要兜底处理。
        if (interrupter == null) {
         // 返回未找到，例如 SSE 超时回调会据此自己关闭连接。
            return false;
        }
  // 真正执行中断：释放上游订阅并关闭连接，模型调用随之停止，不再产生费用和无人接收的输出。
        interrupter.run();
        // 告知调用方中断已生效，不需要再做兜底。
        return true;
    }
}
