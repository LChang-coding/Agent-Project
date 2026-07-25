package cn.bugstack.ai.domain.agent.model.valobj;

import lombok.Data;

import java.util.List;
import java.util.Map;

/** 一张可独立装配的 Agent 应用配置表。 */
@Data
public class AiAgentConfigTableVO {

    /** Runner 应用名。 */
    private String appName;

    /** 对外暴露的根 Agent 摘要。 */
    private Agent agent;

    /** API、模型、子 Agent、工作流和 Runner 装配模块。 */
    private Module module;

    /** 根 Agent 的稳定身份与展示信息。 */
    @Data
    public static class Agent {

        /** 对外 Agent ID。 */
        private String agentId;

        /** 配置内 Agent 名称。 */
        private String agentName;

        /** Agent 能力描述。 */
        private String agentDesc;

    }

    /** 装配图中所有可引用模块。 */
    @Data
    public static class Module {

        /** 模型供应商 API 配置。 */
        private AiApi aiApi;
        /** Spring AI 聊天模型与工具配置。 */
        private ChatModel chatModel;
        /** 原子 LLM Agent 列表。 */
        private List<Agent> agents;
        /** 组合工作流 Agent 列表。 */
        private List<AgentWorkflow> agentWorkflows;
        /** ADK Runner 入口。 */
        private Runner runner;

        /** OpenAI 兼容 API 端点。 */
        @Data
        public static class AiApi {
            /** 服务根地址。 */
            private String baseUrl;
            /** 服务端密钥。 */
            private String apiKey;
            /** 对话完成相对路径。 */
            private String completionsPath = "/v1/chat/completions";
            /** 向量相对路径。 */
            private String embeddingsPath = "/v1/embeddings";

        }

        /** 模型名称及其 MCP/Skill 工具集合。 */
        @Data
        public static class ChatModel {

            /** 实际模型代码。 */
            private String model;
            /** MCP 工具服务配置。 */
            private List<ToolMcp> toolMcpList;
            /** Skill 目录或资源配置。 */
            private List<ToolSkills> toolSkillsList;

            /** 一种 MCP 连接方式。 */
            @Data
            public static class ToolMcp {

                /** 远程 SSE 连接参数。 */
                private SSEServerParameters sse;
                /** 本地子进程 stdio 参数。 */
                private StdioServerParameters stdio;
                /** JVM 内本地工具参数。 */
                private LocalParameters local;

                /** SSE MCP 服务配置。 */
                @Data
                public static class SSEServerParameters {
                    /** 工具服务名称。 */
                    private String name;
                    /** 服务根 URI。 */
                    private String baseUri;
                    /** SSE 相对端点。 */
                    private String sseEndpoint;
                    /** 单次请求超时毫秒数。 */
                    private Integer requestTimeout = 3000;

                }

                /** stdio MCP 子进程配置。 */
                @Data
                public static class StdioServerParameters {
                    /** 工具服务名称。 */
                    private String name;
                    /** 单次请求超时毫秒数。 */
                    private Integer requestTimeout = 3000;
                    /** 子进程启动参数。 */
                    private ServerParameters serverParameters;

                    /** MCP 子进程命令、参数和环境变量。 */
                    @Data
                    public static class ServerParameters {
                        /** 可执行命令。 */
                        private String command;
                        /** 命令参数。 */
                        private List<String> args;
                        /** 受控环境变量。 */
                        private Map<String, String> env;

                    }
                }

                /** JVM 内工具 Bean 名称。 */
                @Data
                public static class LocalParameters {
                    /** 本地工具名称。 */
                    private String name;
                }

            }

            /** Skill 来源与路径。 */
            @Data
            public static class ToolSkills {

                /** directory 读取映射目录，resource 读取工程资源。 */
                private String type = "directory";

                /** Skill 根路径。 */
                private String path;

            }

        }

        /** 可被 Runner 或工作流引用的原子 Agent。 */
        @Data
        public static class Agent {
            /** 配置内唯一名称。 */
            private String name;
            /** 系统指令。 */
            private String instruction;
            /** 能力描述。 */
            private String description;
            /** 输出写入会话状态的键。 */
            private String outputKey;
            /** 引用的模型节点。 */
            private String model;
            /** Agent 允许调用的 MCP 工具。 */
            private List<ChatModel.ToolMcp> toolMcpList;
            /** Agent 允许使用的 Skill。 */
            private List<ChatModel.ToolSkills> toolSkillsList;

        }

        /** loop、parallel 或 sequential 组合 Agent。 */
        @Data
        public static class AgentWorkflow {
            /** 组合类型。 */
            private String type;
            /** 配置内唯一名称。 */
            private String name;
            /** 按名称引用的子 Agent。 */
            private List<String> subAgents;
            /** 工作流能力描述。 */
            private String description;
            /** loop 最大迭代次数。 */
            private Integer maxIterations = 3;

        }

        /** Runner 根 Agent 与插件配置。 */
        @Data
        public static class Runner {
            /** Runner 启动的根 Agent 名称。 */
            private String agentName;
            /** 按名称装配的插件。 */
            private List<String> pluginNameList;
        }
    }

}
