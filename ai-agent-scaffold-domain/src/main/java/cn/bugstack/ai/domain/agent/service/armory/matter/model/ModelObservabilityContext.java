package cn.bugstack.ai.domain.agent.service.armory.matter.model;

import com.google.genai.types.GenerateContentResponseUsageMetadata;

/**
 * 在同一个线程内，把模型适配器拿到的观测数据临时传给日志插件。
 *
 * <p>解决什么问题：模型返回的「实际用的哪个模型版本」和「花了多少 Token」有时会在框架转换过程中丢掉。
 * 适配器能看到原始响应，日志插件能写日志，但两者之间没有直接的参数通道。
 * 这里用一个线程内的临时存放点做桥接：适配器写进去，插件紧接着在同一线程里读出来。</p>
 *
 * <p>所属层次：领域层的装配辅料（进程内工具类，纯静态）。</p>
 *
 * <p>谁会调用它：{@code ObservabilitySpringAI} 写入，{@code MyLogPlugin} 读取并负责清理。</p>
 *
 * <p>它不负责什么：不持久化、不跨线程传递、不做聚合。它只是一个「上一步刚算出来的值」的暂存处。</p>
 *
 * <p>最大的风险是线程池复用：如果写了不清，下一次借到同一个线程的调用会读到上一次的数据，
 * 导致用量记错。所以每条终态路径和异常路径都必须清理。</p>
 */
public final class ModelObservabilityContext {

    /**
     * 线程私有的观测快照存放点。
     *
     * <p>每个线程一份，互不干扰；但线程池会复用线程，所以它必须在每次调用结束时被显式清空。
     * 忘记清空的后果是把上一次调用的模型版本和 Token 数记到这一次的账上。</p>
     */
    private static final ThreadLocal<Snapshot> CONTEXT = new ThreadLocal<>();

    /**
     * 私有构造：这是个纯静态工具类，不允许创建实例。
     *
     * <p>做成实例会给人「可以注入、可以有多份」的错觉，而实际状态是全局线程私有的。</p>
     */
    private ModelObservabilityContext() {
    }

    /**
     * 记下当前线程最近一次模型调用的版本和用量。
     *
     * <p>直接覆盖旧值：一次模型调用里流式响应会来很多片，每片都可能带更新的累计用量，
     * 我们只关心最新的那一份。</p>
     */
    public static void set(String modelVersion, GenerateContentResponseUsageMetadata usageMetadata) {
        // 覆盖式写入，永远只保留最近一次的观测结果。
        CONTEXT.set(new Snapshot(modelVersion, usageMetadata));
    }

    /**
     * 取出当前线程的观测快照。
     *
     * <p>返回 null 表示这次调用没有产生任何可用的观测数据（比如调用还没走到模型就失败了），
     * 调用方必须处理空值，不能假定一定有值。</p>
     */
    public static Snapshot get() {
        // 取出本线程的快照；没写过就是 null。
        return CONTEXT.get();
    }

    /**
     * 清空当前线程的观测快照。
     *
     * <p>必须在每次模型调用的成功、失败、取消路径上都调用，否则线程被复用时会把旧数据算到新调用头上。</p>
     */
    public static void clear() {
        // 用 remove 而不是 set(null)，彻底解除 ThreadLocal 与线程的关联，避免内存驻留。
        CONTEXT.remove();
    }

    /**
     * 一次模型调用的观测结果：用的哪个模型版本，花了多少 Token。
     *
     * <p>用不可变记录承载，避免读取方无意间改掉暂存值。</p>
     */
    public record Snapshot(String modelVersion, GenerateContentResponseUsageMetadata usageMetadata) {
    }
}
