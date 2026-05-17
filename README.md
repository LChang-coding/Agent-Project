# AI Agent Scaffold

基于 **Spring Boot 3.4 + Google ADK 1.1 + Spring AI 1.1 + DeepSeek** 的智能体脚手架，通过 YAML 配置即可装配多智能体、工作流、MCP 工具和 Skills 技能。

## 核心能力

- **YAML 装配智能体** — 零代码定义 Agent 身份、指令、工具和工作流
- **工作流编排** — 支持 Sequential（顺序）、Parallel（并行）、Loop（循环）三种模式
- **MCP 工具集成** — 支持 SSE、Stdio、Local 三种连接方式的 MCP Server
- **Skills 技能系统** — 通过 SKILL.md 注入领域知识，支持 classpath 和本地目录两种加载方式
- **插件机制** — 可扩展的插件体系，在 Agent 运行前后插入自定义逻辑
- **DeepSeek 适配** — 内置 thinking 模式禁用，兼容 deepseek-v4-flash

## 快速开始

### 1. 环境要求

- JDK 17+
- Maven 3.6+
- MySQL 8.0+（可选，用户认证用）

### 2. 配置密钥

```bash
cd ai-agent-scaffold-app/src/main/resources
cp secrets.properties.example secrets.properties
```

填入你的 DeepSeek API Key 和 MCP 工具密钥：

```properties
deepseek.api.key=sk-xxxxxxxxxxxxxxxx
baidu.search.sse.endpoint=sse?api_key=your-baidu-api-key
baidu.map.sse.endpoint=sse?ak=your-baidu-map-ak
```

### 3. 选择智能体

编辑 `application-dev.yml` 中的 `spring.config.import`，选择要激活的智能体：

```yaml
spring:
  config:
    import:
      - classpath:secrets.properties
      - classpath:agent/only-one-agent.yml    # 单一智能体
      # - classpath:agent/test-agent.yml      # 顺序工作流：Code → Review → Refactor
      # - classpath:agent/parallel_research_app.yml  # 并行研究 + 汇总
```

### 4. 启动

```bash
mvn spring-boot:run -pl ai-agent-scaffold-app
```

默认端口 `8091`，访问 `http://localhost:8091` 即可对话。

## 项目结构

```
Agent-Project
├── ai-agent-scaffold-app            # 启动入口 & 配置资源
│   └── src/main/resources
│       ├── application-dev.yml      # 环境配置
│       ├── secrets.properties       # 密钥（gitignore）
│       ├── agent/                   # 智能体 YAML 定义
│       │   ├── only-one-agent.yml
│       │   ├── test-agent.yml
│       │   ├── parallel_research_app.yml
│       │   └── skills/              # Skills 技能文件
│       │       ├── battle-plan/     #   电脑性能优化
│       │       └── pdf/             #   PDF 处理
├── ai-agent-scaffold-trigger        # HTTP 接口层
├── ai-agent-scaffold-domain         # 核心业务逻辑
│   └── service/armory/
│       ├── node/                    #   装配节点链（链式责任）
│       │   ├── AiApiNode            #     构建 OpenAiApi
│       │   ├── ChatModelNode        #     构建 ChatModel + 工具
│       │   ├── AgentNode            #     构建 LlmAgent
│       │   ├── AgentWorkflowNode    #     构建工作流
│       │   ├── RunnerNode           #     构建 Runner
│       │   └── workflow/            #     工作流实现
│       ├── matter/mcp/client/       #   MCP 客户端（SSE/Stdio/Local）
│       ├── matter/skills/           #   Skills 加载器
│       └── matter/plugin/           #   插件实现
├── ai-agent-scaffold-infrastructure # 数据持久化
├── ai-agent-scaffold-api            # DTO & 接口定义
└── ai-agent-scaffold-types          # 常量 & 枚举
```

## 智能体配置

一个典型的 Agent YAML 包含以下模块：

```yaml
ai:
  agent:
    config:
      tables:
        myAgent:
          agent:                         # 智能体基本信息
            agent-id: 100001
            agent-name: 我的智能体
            agent-desc: 智能体描述
          module:
            ai-api:                      # AI API 配置
              base-url: ${deepseek.base.url}
              api-key: ${deepseek.api.key}
            chat-model:                  # 模型 & 工具
              model: deepseek-v4-flash
              tool-mcp-list:             # MCP 工具列表
                - sse:
                    name: baidu-search
                    base-uri: http://...
                    sse-endpoint: ${baidu.search.sse.endpoint}
              tool-skills-list:          # Skills 技能
                - type: resource
                  path: agent/skills
            agents:                      # 子智能体定义
              - name: MyAgent
                instruction: 你的指令...
            agent-workflows:             # 工作流编排
              - type: sequential
                name: MyPipeline
                sub-agents: [AgentA, AgentB]
            runner:                      # 运行配置
              agent-name: MyPipeline
```

## 工作流类型

| 类型 | 说明 | 适用场景 |
|---|---|---|
| `sequential` | 按顺序执行子 Agent，前一个的输出作为后一个的输入 | 代码生成 → Review → 重构 |
| `parallel` | 并行执行多个子 Agent，结果汇总给下游 | 多主题并行研究 |
| `loop` | 循环执行直到满足条件 | 反复优化、自修正 |

## MCP 工具连接方式

| 方式 | 说明 |
|---|---|
| **SSE** | 通过 Server-Sent Events 连接远程 MCP Server |
| **Stdio** | 通过子进程标准输入输出连接本地工具 |
| **Local** | 直接注入 Spring Bean 作为 ToolCallback |

## Skills 技能

Skills 通过 SKILL.md 定义领域知识，让 Agent 获得特定领域的专业能力：

```
agent/skills
├── battle-plan/           # 电脑性能优化
│   ├── SKILL.md           #   技能定义（name, description, 指令）
│   ├── reference.md       #   参考话术
│   └── scripts/
│       ├── get_system_info.sh
│       └── check_cleanable_files.sh
└── pdf/                   # PDF 处理
    ├── SKILL.md
    ├── forms.md
    ├── reference.md
    └── scripts/*.py
```

SKILL.md 格式：

```markdown
---
name: battle-plan
description: 电脑性能优化
license: MIT
---

这里是该技能的详细指令和操作步骤...
```

## 技术栈

| 组件 | 版本 | 用途 |
|---|---|---|
| Spring Boot | 3.4.3 | 应用框架 |
| Spring AI | 1.1.0-M3 | AI 模型接入 |
| Google ADK | 1.1.0 | 智能体编排 |
| DeepSeek | v4-flash | LLM 模型 |
| Spring AI Agent Utils | 0.4.2 | 内置工具 & Skills |
| HikariCP + MySQL | — | 数据库连接池 |

## Maven 脚手架

支持将项目导出为 Archetype，快速生成新项目：

```bash
# 导出 archetype
mvn archetype:create-from-project
cd target/generated-sources/archetype
mvn install

# 从本地 archetype 创建新项目
mvn archetype:generate -X -DarchetypeCatalog=local
```

## License

Apache License 2.0
