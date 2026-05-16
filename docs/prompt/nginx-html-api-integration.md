# Nginx 静态页面（login/index）API 对接说明

本文说明 `docs/dev-ops/nginx/html` 目录下的静态页面如何与后端接口对接，包括接口列表、请求/响应结构、页面调用时序与常见故障处理。

## 目录结构

- 静态页面目录：`docs/dev-ops/nginx/html`
  - `login.html`：演示登录（默认 `admin/admin`），写入 cookie 后跳转 `index.html`
  - `index.html`：对话页面（未登录跳转 `login.html`）
  - `images/ai-hero.svg`：页面效果图
  - `js/config.js`：前端配置（服务端地址）

## 前端配置（API_BASE）

前端通过 `js/config.js` 提供后端根地址：

- 文件：`docs/dev-ops/nginx/html/js/config.js`
- 配置项：`window.APP_CONFIG.API_BASE`
- 默认值：`http://127.0.0.1:8091`

`index.html` 会读取该配置，拼接为完整接口地址（例如：`${API_BASE}/api/v1/query_ai_agent_config_list`）。

## 登录态（cookie）

- cookie 名称：`ai_agent_login`
- cookie 内容（JSON 字符串）：`{"user":"admin","ts":<登录时间戳>}`
- `login.html`：
  - 登录成功写入 cookie
  - 已存在 cookie 时直接跳转 `index.html`
- `index.html`：
  - 读取 cookie 获取 `userId`（即 `payload.user`）
  - 未获取到 `userId` 则跳转 `login.html`

说明：该登录逻辑用于静态页面演示；真实系统可替换为后端鉴权接口并按需设置 cookie 策略。

## 接口与后端位置

后端控制器：
- [AgentServiceController.java](file:///Users/fuzhengwei/coding/gitcode/KnowledgePlanet/ai-agent-scaffold/ai-agent-scaffold-lite/ai-agent-scaffold-lite-trigger/src/main/java/cn/bugstack/ai/trigger/http/AgentServiceController.java)

接口基础前缀：
- `/api/v1/`

统一响应结构（泛型包装）：
- [Response.java](file:///Users/fuzhengwei/coding/gitcode/KnowledgePlanet/ai-agent-scaffold/ai-agent-scaffold-lite/ai-agent-scaffold-lite-api/src/main/java/cn/bugstack/ai/api/response/Response.java)
- 成功码：`code = "0000"`（见 [ResponseCode.java](file:///Users/fuzhengwei/coding/gitcode/KnowledgePlanet/ai-agent-scaffold/ai-agent-scaffold-lite/ai-agent-scaffold-lite-types/src/main/java/cn/bugstack/ai/types/enums/ResponseCode.java)）

### 1）查询智能体列表

- 方法：`GET`
- 路径：`/api/v1/query_ai_agent_config_list`
- 用途：渲染下拉框智能体列表（展示 `agentName`，value 为 `agentId`）
- 前端调用：`index.html` 启动时加载

响应示例（简化）：

```json
{
  "code": "0000",
  "info": "成功",
  "data": [
    {
      "agentId": "xxx",
      "agentName": "智能体名称",
      "agentDesc": "描述"
    }
  ]
}
```

### 2）创建会话

推荐方式（浏览器更友好）：
- 方法：`POST`
- 路径：`/api/v1/create_session`
- Content-Type：`application/json`
- 请求体：

```json
{
  "agentId": "xxx",
  "userId": "admin"
}
```

响应体（简化）：

```json
{
  "code": "0000",
  "info": "成功",
  "data": { "sessionId": "S-2026..." }
}
```

兼容方式（可用于手工验证）：
- 方法：`GET`
- 路径：`/api/v1/create_session?agentId=xxx&userId=admin`

前端调用策略（index.html）：
- 点击【新建】：立即创建新的 `sessionId` 并作为“当前会话”
- 首次【发送】但尚未创建会话：自动创建一次会话
- 后续发送：复用当前 `sessionId`
- 切换智能体：会清空当前 `sessionId`，避免串会话

### 3）对话

- 方法：`POST`
- 路径：`/api/v1/chat`
- Content-Type：`application/json`
- 请求体字段：
  - `agentId`：下拉框选择的智能体
  - `userId`：从 cookie 读取的登录用户
  - `sessionId`：当前会话 ID（由 create_session 返回）
  - `message`：用户输入

请求体示例：

```json
{
  "agentId": "xxx",
  "userId": "admin",
  "sessionId": "S-2026...",
  "message": "你好，介绍一下你能做什么？"
}
```

响应体示例（简化）：

```json
{
  "code": "0000",
  "info": "成功",
  "data": { "content": "智能体回复内容..." }
}
```

页面渲染：
- 用户消息：左侧气泡（user）
- 智能体回复：右侧气泡（agent）

## 页面时序（从登录到对话）

1. 访问 `login.html`
2. 输入 `admin/admin`（演示）
3. 写入 cookie `ai_agent_login`，跳转到 `index.html`
4. `index.html` 校验 cookie，读取 `userId`
5. 加载 `js/config.js` 获取 `API_BASE`
6. 调用 `GET /api/v1/query_ai_agent_config_list` 获取智能体列表
7. 选择智能体
8. 点击【新建】创建会话（或首次发送时自动创建）
9. 每次发送调用 `POST /api/v1/chat` 获取回复并渲染

## 服务端不可用时的提示策略

当浏览器出现以下常见问题时（服务端没启动、端口不通、跨域失败导致 `fetch` 抛错）：
- 页面会弹出“服务端接口不可用”的遮罩弹窗
- 弹窗会提示检查 `docs/dev-ops/nginx/html/js/config.js` 的 `API_BASE` 是否被更换
- 弹窗提供“我已启动，重试”：重新拉取智能体列表

## 常见问题排查

1. 页面提示“服务端接口不可用”
   - 检查后端服务是否启动、端口是否为 8091
   - 检查 `docs/dev-ops/nginx/html/js/config.js` 的 `API_BASE` 是否正确
2. 浏览器控制台出现 CORS 错误
   - 确认后端允许跨域（控制器已使用 `@CrossOrigin(origins = "*")`）
3. create_session 无法调用
   - 浏览器不支持 GET 携带 body，建议使用 POST JSON（页面已按 POST 调用）

