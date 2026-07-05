# AI Agent Scaffold Web

企业智能体平台前端骨架，使用 Vue 3、Vite、TypeScript、Pinia 和 Vue Router。

## 本地启动

```bash
npm install
npm run dev
```

默认访问地址：

```text
http://127.0.0.1:5173
```

默认后端代理：

```text
/api -> http://127.0.0.1:8091
```

如需调整后端地址，修改 `.env.development` 的 `VITE_API_TARGET`。

## 已接入能力

- 登录、注册、自动登录检查
- JWT Bearer Token 注入
- refreshToken 静默续期
- 修改个人资料
- 修改密码后清理前端登录态
- 查询后端 Agent 配置
- 创建持久化会话
- 流式聊天 `POST /api/v1/chat_stream`
- 非流式聊天兜底 `POST /api/v1/chat`

## 占位模块

- 上下文管理可视化
- Token 用量分析
- 工作流编排
- MCP 发布与授权
- Skill 版本管理
- RAG 知识库
- 附件资产
- 租户成员与角色权限
- Grafana / Loki 可观测入口

## 设计约束

- 前端不保存模型密钥，模型提供方仍由后端系统配置。
- 前端展示 `userId` 只用于兼容和可视化，可信身份以后端 JWT 解析结果为准。
- 后端未完成的能力先保留页面入口和信息架构，接 API 时不需要重做导航与布局。
