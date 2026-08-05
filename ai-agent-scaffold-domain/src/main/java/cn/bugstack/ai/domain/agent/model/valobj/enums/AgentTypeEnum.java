package cn.bugstack.ai.domain.agent.model.valobj.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 组合型 Agent 的三种编排方式，同时记住每种方式该由哪个装配节点来建。
 *
 * <p>解决什么问题：配置文件里写的是 {@code type: loop} 这样的字符串，而真正要创建的是不同的
 * ADK 组合 Agent。这个枚举把「配置里的字符串」和「负责装配它的 Spring Bean 名」绑在一起，
 * 装配时按 type 查出 Bean 名再从容器里取节点，避免在代码里写一长串 if-else。</p>
 *
 * <p>所属层次：领域层的值对象（枚举）。</p>
 *
 * <p>谁会调用它：{@code AgentWorkflowNode} 在装配组合工作流时按配置里的 type 解析出枚举，
 * 再用 {@code node} 字段去Spring 容器里取对应的装配节点。</p>
 *
 * <p>它不负责什么：不创建 Agent、不校验子 Agent 是否存在，只做「字符串到装配节点名」的映射。</p>
 */
@Getter
@AllArgsConstructor
@NoArgsConstructor
public enum AgentTypeEnum {

    /** 让同一批子 Agent 反复执行，直到达到配置的最大迭代次数；适合「写完再自我修订」这类场景。 */
    Loop("循环执行", "loop", "loopAgentNode"),
    /** 让多个子 Agent 同时跑，各自结果互不依赖；总耗时取决于最慢的那个。 */
    Parallel("并行执行", "parallel", "parallelAgentNode"),
    /** 让子 Agent 按配置顺序一个接一个跑，前一个的输出可作为后一个的输入。 */
    Sequential("串行执行", "sequential", "sequentialAgentNode"),

    ;

    /** 中文展示名，只给日志和管理界面看，不参与任何匹配逻辑。 */
    private String name;
    /**
     * 配置文件里写的类型字符串，是 YAML 与代码之间的约定值。
     *
     * <p>解析时按忽略大小写比对，所以配置写Loop 或 loop 都能识别；
     * 写成别的词会解析成空值，那条组合工作流就装配不出来。</p>
     */
    private String type;
    /**
     * 负责装配这种组合方式的 Spring Bean 名称。
     *
     * <p>装配链拿到它之后直接去容器里按名取 Bean，因此这里的字符串必须和节点类上的
     * Bean 名严格一致，改了类名却忘了改这里，装配时会因为找不到 Bean 而失败。</p>
     */
    private String node;

    /**
     * 把配置文件里的类型字符串翻译成枚举。
     *
     * <p>关键输入是YAML 里的 type 值；比对时忽略大小写，降低配置书写负担。</p>
     *
     * <p>返回结果：匹配到就返回对应枚举；传入空值或无法识别的类型都返回 null，
     * 由调用方决定是跳过这条配置还是报错，这里不主动抛异常。</p>
     */
    public static AgentTypeEnum formType(String type) {
        // 配置里根本没写类型，没什么可匹配的，直接返回空让调用方处理。
        if (type == null) {
            return null;
        }

        // 逐个比对三种组合方式，找到就立即返回。
        for (AgentTypeEnum value : values()) {
            // 忽略大小写比对，保证 Loop / loop / LOOP 都能命中同一个枚举。
            if (value.getType().equalsIgnoreCase(type)) {
                // 命中后立刻返回，避免继续无意义地遍历。
                return value;
            }
        }

        // 三种都没命中，说明配置写了系统不支持的组合方式，返回空表示无法识别。
        return null;
    }

}
