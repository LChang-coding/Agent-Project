package cn.bugstack.ai.domain.agent.model.valobj;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 一张完整的 Agent 应用配置表：把「一个智能体应用需要的全部零件」按依赖顺序描述清楚。
 *
 * <p>解决什么问题：一个可用的智能体不是单个对象，它需要模型服务地址、模型代码、MCP 工具、
 * Skill 目录、若干原子 Agent、可选的组合工作流，最后还要一个 Runner 把它们串起来。
 * 这个类就是这份「零件清单」的数据形状，配置文件写好后由Spring 直接绑定进来。</p>
 *
 * <p>所属层次：领域层的值对象，纯数据，没有任何行为。</p>
 *
 * <p>谁会调用它：{@code AiAgentAutoConfigProperties} 按表名持有多张；装配链的每个节点
 * （AiApiNode → ChatModelNode → AgentNode → AgentWorkflowNode → RunnerNode）各取自己那一段来建Bean。</p>
 *
 * <p>它不负责什么：不校验字段是否齐全、不判断引用的名字是否存在、不创建任何对象。
 * 配置里写错了工具名或子 Agent 名，要到装配阶段才会暴露出来。</p>
 */
@Data
public class AiAgentConfigTableVO {

    /**
     * ADK Runner 的应用名，装配出的 Runner 和运行时句柄都会带上它。
     *
     * <p>它决定模型侧会话数据挂在哪个应用空间下，同一份配置换了appName 相当于换了一套会话空间，
     * 已有会话的历史上下文会读不到。</p>
     */
    private String appName;

    /**
     * 这张表对外暴露的那个 Agent 的身份信息（编号、名称、描述）。
     *
     * <p>Agent 列表接口和租户启停功能都只认这里的 agentId；表内部的那些原子 Agent 不对外可见。</p>
     */
    private Agent agent;

    /**
     * 装配所需的全部零件：API 端点、聊天模型、原子 Agent、组合工作流和 Runner。
     *
     * <p>装配链按固定顺序消费它，前一个节点产出的 Bean 会被后一个节点按名字引用，
     * 因此里面的名称必须自洽，写错名字会让装配在某一层断掉。</p>
     */
    private Module module;

    /**
     * 对外可见的 Agent 身份与展示信息。
     *
     * <p>只做展示和寻址用，不含模型、提示词等执行细节，因此可以安全地返回给前端。</p>
     */
    @Data
    public static class Agent {

        /** 对外的 Agent 编号，是前端建会话、租户启停、运行时取句柄三处共用的钥匙，改动它等于换了一个 Agent。 */
        private String agentId;

        /** 展示名称，只用于界面和日志。 */
        private String agentName;

        /** 能力描述，帮用户判断该选哪个智能体，不参与执行。 */
        private String agentDesc;

    }

    /**
     * 装配图：这张配置表里所有可被引用的零件集合。
     *
     * <p>零件之间是有依赖顺序的——模型依赖 API 端点，Agent 依赖模型和工具，
     * 工作流依赖 Agent，Runner 依赖根 Agent。装配链就是照这个顺序一层层往上建的。</p>
     */
    @Data
    public static class Module {

        /** 模型服务的接入信息（地址、密钥、路径），是整张表最底层的依赖，缺了它模型建不出来。 */
        private AiApi aiApi;
        /** 聊天模型配置，包含用哪个模型代码以及这个模型能用哪些 MCP 工具和 Skill。 */
        private ChatModel chatModel;
        /** 原子 LLM Agent 列表，每个都有自己的提示词和工具集；工作流和 Runner 按名称引用它们。 */
        private List<Agent> agents;
        /** 组合工作流 Agent 列表，把上面的原子 Agent 按循环/并行/串行编排起来；没有编排需求时可为空。 */
        private List<AgentWorkflow> agentWorkflows;
        /** Runner 入口配置，指明启动时以哪个 Agent 为根、挂哪些插件；它是装配链的最后一环。 */
        private Runner runner;

        /**
         * 兼容 OpenAI 协议的模型服务接入点。
         *
         * <p>装配时用它构造 HTTP 客户端，所有模型调用都从这里出去。
         * 地址或密钥写错的表现是对话时报鉴权/连接失败，而不是启动失败。</p>
         */
        @Data
        public static class AiApi {
            /** 模型服务的根地址，例如网关或厂商域名；对话和向量请求都拼在它后面。 */
            private String baseUrl;
            /** 访问模型服务的密钥，属于敏感信息，不要打进日志也不要返回给前端。 */
            private String apiKey;
            /** 对话补全接口的相对路径，默认走 OpenAI 标准路径；自建网关改了路由时才需要覆盖。 */
            private String completionsPath = "/v1/chat/completions";
            /** 向量化接口的相对路径，知识库检索时用；默认同样是 OpenAI 标准路径。 */
            private String embeddingsPath = "/v1/embeddings";

        }

        /**
         * 聊天模型及它能使用的工具集合。
         *
         * <p>工具分两类：MCP 是外部工具服务（可能是远程 SSE、本地子进程或 JVM 内实现），
         * Skill 是以目录或资源形式提供的技能包。两类都会在装配阶段真实建立连接或读取文件，
         * 因此配置不通会直接导致装配失败。</p>
         */
        @Data
        public static class ChatModel {

            /** 实际下发给模型服务的模型代码，决定对话质量、上下文长度和计费口径。 */
            private String model;
            /** 这个模型可用的 MCP 工具服务列表，装配时逐个建立连接并把工具清单注册给模型。 */
            private List<ToolMcp> toolMcpList;
            /** 这个模型可用的 Skill 列表，装配时按目录或资源路径读取技能定义。 */
            private List<ToolSkills> toolSkillsList;

            /**
             * 一个 MCP 工具服务的接入方式，三种参数互斥，只会填其中一种。
             *
             * <p>装配时按哪个字段不为空来决定用哪种客户端：远程 SSE、本地子进程 stdio，还是 JVM 内实现。
             * 三个都为空这条配置就被跳过，对应的工具在对话时不可用。</p>
             */
            @Data
            public static class ToolMcp {

                /** 远程 SSE 方式的连接参数；填了它就走 HTTP 长连接访问外部工具服务。 */
                private SSEServerParameters sse;
                /** 本地子进程方式的参数；填了它会在本机拉起一个进程用标准输入输出通信。 */
                private StdioServerParameters stdio;
                /** JVM 内本地工具的参数；填了它直接用容器里的 Bean，不产生任何进程或网络开销。 */
                private LocalParameters local;

                /**
                 * 通过 SSE 访问远程 MCP 工具服务所需的参数。
                 *
                 * <p>装配时会真的去连这个地址拉取工具清单，地址不通会导致这张表装配失败。</p>
                 */
                @Data
                public static class SSEServerParameters {
                    /** 工具服务的名称，用于在模型侧区分不同工具来源，也是排查问题时的定位依据。 */
                    private String name;
                    /** 工具服务的根地址，SSE 端点拼在它后面。 */
                    private String baseUri;
                    /** SSE 端点的相对路径，不同工具服务实现可能不同，需按对方文档填写。 */
                    private String sseEndpoint;
                    /** 单次工具调用的超时毫秒数，默认 3 秒；设得太小会让慢工具频繁超时中断对话。 */
                    private Integer requestTimeout = 3000;

                }

                /**
                 * 以本地子进程方式运行 MCP 工具服务所需的参数。
                 *
                 * <p>装配时会在本机启动这个进程并通过标准输入输出通信，
                 * 因此宿主机上必须有对应的可执行命令，容器化部署时尤其容易漏装。</p>
                 */
                @Data
                public static class StdioServerParameters {
                    /** 工具服务名称，用于在模型侧区分工具来源。 */
                    private String name;
                    /** 单次工具调用的超时毫秒数，默认 3 秒。 */
                    private Integer requestTimeout = 3000;
                    /** 启动子进程用的命令、参数和环境变量。 */
                    private ServerParameters serverParameters;

                    /**
                     * 子进程的启动方式。
                     *
                     * <p>这三项直接决定会在宿主机上执行什么命令，属于高风险配置，
                     * 不应允许外部输入拼进来，否则等于开放了任意命令执行。</p>
                     */
                    @Data
                    public static class ServerParameters {
                        /** 可执行文件名或路径，例如 npx、python；宿主机上找不到会导致装配失败。 */
                        private String command;
                        /** 命令行参数，按顺序拼在命令后面。 */
                        private List<String> args;
                        /** 传给子进程的环境变量，通常放工具自己的密钥；只传必需项，避免把宿主环境整体暴露出去。 */
                        private Map<String, String> env;

                    }
                }

                /**
                 * JVM 内本地工具的引用方式。
                 *
                 * <p>不启进程、不走网络，直接按名字从 Spring 容器里取工具实现，
                 * 适合系统自带的内置工具，调用开销最小。</p>
                 */
                @Data
                public static class LocalParameters {
                    /** 本地工具在容器里的名称，装配时按它取 Bean；名字对不上工具就注册不进模型。 */
                    private String name;
                }

            }

            /**
             * 一个 Skill 技能包的来源。
             *
             * <p>Skill 是以文件形式提供的能力描述，装配时会真的去读这些文件，
             * 路径不存在或没有读权限会导致装配失败。</p>
             */
            @Data
            public static class ToolSkills {

                /** 来源类型：directory 表示读磁盘上的映射目录，resource 表示读打进包里的工程资源。 */
                private String type = "directory";

                /** Skill 的根路径；配合 type 决定是磁盘路径还是 classpath 路径，写错会读不到任何技能。 */
                private String path;

            }

        }

        /**
         * 一个原子 LLM Agent 的完整定义：用什么模型、按什么指令做事、能用哪些工具。
         *
         * <p>它可以被 Runner 直接当根 Agent 使用，也可以被组合工作流按名称引用成子 Agent。</p>
         */
        @Data
        public static class Agent {
            /** 在这张配置表里的唯一名称，工作流和 Runner 都靠它引用；重名会导致引用到错的那个。 */
            private String name;
            /** 系统指令（提示词），决定这个 Agent 的角色和行为边界，是影响回答质量最直接的字段。 */
            private String instruction;
            /** 能力描述；在组合工作流里，上层 Agent 靠它判断该把任务交给哪个子 Agent。 */
            private String description;
            /** 把这个 Agent 的输出写进会话状态的哪个键；串行工作流的下游节点靠这个键读上游结果。 */
            private String outputKey;
            /** 引用哪个模型节点；引用不存在的模型会让这个 Agent 装配不出来。 */
            private String model;
            /** 只允许这个 Agent 使用的 MCP 工具，是收窄工具权限的手段，避免每个 Agent 都能调用全部工具。 */
            private List<ChatModel.ToolMcp> toolMcpList;
            /** 只允许这个 Agent 使用的 Skill，作用同上。 */
            private List<ChatModel.ToolSkills> toolSkillsList;

        }

        /**
         * 一个组合工作流 Agent 的定义：把多个原子 Agent 按某种方式编排起来。
         *
         * <p>它自己不调用模型，只负责调度子 Agent；编排方式由 type 决定。</p>
         */
        @Data
        public static class AgentWorkflow {
            /** 编排方式，取loop / parallel / sequential；装配时按它找对应的装配节点，写错这条工作流建不出来。 */
            private String type;
            /** 在配置表里的唯一名称，Runner 可以把它当根 Agent 引用。 */
            private String name;
            /** 按名称引用的子 Agent 列表；串行方式下列表顺序就是执行顺序，顺序错了结果就错。 */
            private List<String> subAgents;
            /** 工作流的能力描述，用于日志和上层调度判断。 */
            private String description;
            /** 循环方式下的最大迭代次数，默认 3；它是防止无限循环烧掉模型额度的唯一闸门。 */
            private Integer maxIterations = 3;

        }

        /**
         * Runner 的装配入口：指明以谁为根 Agent，以及要挂哪些插件。
         *
         * <p>它是装配链的最后一环，产出的 Runner 就是对话时真正执行的对象。</p>
         */
        @Data
        public static class Runner {
            /** 根 Agent 的名称，可以指向原子 Agent 也可以指向组合工作流；引用不到就没有 Runner 可用。 */
            private String agentName;
            /** 要挂载的插件名称列表，按顺序生效；插件负责注入上下文、拦截工具调用和记录日志。 */
            private List<String> pluginNameList;
        }
    }

}
