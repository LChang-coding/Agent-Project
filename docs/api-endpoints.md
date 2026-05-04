# AI Agent 脚手架 — 对外接口文档

Base URL: `http://localhost:8091

统一响应格式：

```json
{ "code": "0000", "info": "成功", "data": {...} }
```

---

## 1. 查询智能体列表

获取当前脚手架中所有可用的智能体。

```
GET /api/v1/query_ai_agent_config_list
```

**参数：** 无

**响应示例：**

```json
{
  "code": "0000",
  "info": "成功",
  "data": [
    {
      "agentId": "100003",
      "agentName": "单一智能体",
      "agentDesc": "一个基础的对话智能体"
    }
  ]
}
```

**curl 测试：**

```bash
curl -s http://localhost:8091/api/v1/query_ai_agent_config_list | jq
```

---

## 2. 创建会话

选定一个智能体，为用户创建一个新会话，返回 sessionId。

### GET 方式

```
GET /api/v1/create_session?agentId=100003&userId=lcode
```

| 参数    | 类型   | 必填 | 说明      |
| ------- | ------ | ---- | --------- |
| agentId | String | 是   | 智能体 ID |
| userId  | String | 是   | 用户标识  |

**curl 测试：**

```bash
curl -s "http://localhost:8091/api/v1/create_session?agentId=100001&userId=lcode" | jq
```

### POST 方式

```
POST /api/v1/create_session
Content-Type: application/json
```

**请求体：**

```json
{
  "agentId": "100003",
  "userId": "lcode"
}
```

**curl 测试：**

```bash
curl -s -X POST http://localhost:8091/api/v1/create_session \
  -H "Content-Type: application/json" \
  -d '{"agentId":"100001","userId":"lcode"}' | jq
```

**响应示例：**

```json
{
  "code": "0000",
  "info": "成功",
  "data": {
    "sessionId": "s-abc123..."
  }
}
```

---

## 3. 发送消息（非流式）

向智能体发送消息，等待完整回复后一次性返回。

```
POST /api/v1/chat
Content-Type: application/json
```

| 参数      | 类型   | 必填 | 说明                 |
| --------- | ------ | ---- | -------------------- |
| agentId   | String | 是   | 智能体 ID            |
| userId    | String | 是   | 用户标识             |
| sessionId | String | 否   | 为空则自动创建新会话 |
| message   | String | 是   | 用户消息内容         |

**请求体：**

```json
{
  "agentId": "100003",
  "userId": "lcode",
  "sessionId": "",
  "message": "你好，请介绍一下你自己"
}
```

**响应示例：**

```json
{
  "code": "0000",
  "info": "成功",
  "data": {
    "content": "你好！我是 AI 助手，可以回答你的问题..."
  }
}
```

**curl 测试：**

```bash
8091curl -s -X POST http://localhost:8080/api/v1/chat \
  -H "Content-Type: application/json" \
  -d '{"agentId":"100001","userId":"lcode","sessionId":"","message":"你好"}' | jq
```

---

## 4. 发送消息（SSE 流式）

流式返回，打字机效果。底层通过 `Flowable<Event>` + SSE 推送每个 token 事件。

```
POST /api/v1/chat_stream
Content-Type: application/json
```

**请求体：** 同非流式

**响应：** `text/event-stream`，超时 3 分钟，每条事件为 SSE 格式。

**curl 测试：**

```bash
curl -N -X POST http://localhost:8091/api/v1/chat_stream \
  -H "Content-Type: application/json" \
  -d '{"agentId":"100001","userId":"lcode","sessionId":"xxx","message":"你好"}'
```

---

## 5. 发送消息（多模态）

支持文本 + 文件 + 内联数据混合发送。

```
POST /api/v1/chat_multimodal
Content-Type: application/json
```

**请求体：**

```json
{
  "agentId": "100001",
  "userId": "lcode",
  "sessionId": "xxx",
  "texts": [
    { "message": "请分析这张图片" }
  ],
  "files": [
    { "fileUri": "/Users/codeliu/Desktop/test_red.png", "mimeType": "image/png" }
  ],
  "inlineDatas": [
    { "bytes": "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAEElEQVR4nGP4z8AARAwQCgAf7gP9i18U1AAAAABJRU5ErkJggg==", "mimeType": "image/png" }
  ]
}
```

| 字段        | 类型               | 说明                    |
| ----------- | ------------------ | ----------------------- |
| texts       | List\<Text\>       | 文本消息列表            |
| files       | List\<File\>       | 文件 URI + MIME 类型    |
| inlineDatas | List\<InlineData\> | Base64 编码的二进制数据 |

---

## 推荐测试顺序

```
  ┌─ 1. query_ai_agent_config_list ──→ 拿到可用的 agentId
  │
  ├─ 2. create_session ──→ 拿到 sessionId
  │
  └─ 3. chat / chat_stream / chat_multimodal ──→ 发消息
```

## 一键测试脚本

```bash
#!/bin/bash
BASE="http://localhost:8091/api/v1"

# 1. 查智能体列表
echo "=== 智能体列表 ==="
curl -s "$BASE/query_ai_agent_config_list" | jq

# 2. 创建会话
echo "=== 创建会话 ==="
SESSION=$(curl -s -X POST "$BASE/create_session" \
  -H "Content-Type: application/json" \
  -d '{"agentId":"100001","userId":"lcode"}' | jq -r '.data.sessionId')
echo "sessionId: $SESSION"

# 3. 发送消息
echo "=== 发送消息 ==="
curl -s -X POST "$BASE/chat" \
  -H "Content-Type: application/json" \
  -d "{\"agentId\":\"100001\",\"userId\":\"lcode\",\"sessionId\":\"$SESSION\",\"message\":\"你好\"}" | jq
```

