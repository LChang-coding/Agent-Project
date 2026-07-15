# 附件绑定失败与 SSE 异常闭环计划

## 问题证据

- 2026-07-16 00:38，工作流流式消息在 `AssetService.bindToMessage` 抛出 `ASSET_BIND_DENIED`。
- 绑定失败发生在用户消息、附件和运行绑定的同一事务内，业务数据应整体回滚。
- SSE 响应已经提交后，异常继续冒泡到 MVC，触发 `LinkedHashMap` 无法按 `text/event-stream` 转换的二次告警。

## 执行计划（执行前落盘）

1. 只读核对远端该 tenant/user/session 附件记录的 `asset_id/session_id/message_id/status/parse_status/deleted`，并检查前端资产列表是否允许选择已绑定附件，明确真实失败条件。
2. 保持后端可信绑定约束，不放宽跨租户、跨用户或跨会话校验；若是已绑定附件被重复选择，则在服务端返回资产状态时暴露可发送性，并在前端禁止重新选择，同时对发送快照做最终过滤。
3. 对同一消息的幂等重试与不同消息复用附件作明确区分：同一 asset 不允许静默绑定到新消息，避免历史归属被篡改；错误响应应指出具体不可绑定附件而不泄露其他租户数据。
4. 收口流式异常：SSE 建立后将业务异常编码为 SSE error 事件并正常结束响应，不再把异常交给普通 MVC `Response` 转换器；前端保持可读错误并重新加载数据库有效消息。
5. 补领域测试、Mapper 测试和前端生产构建；具备本地启动条件时补附件上传→选择→工作流流式发送冒烟。
6. 将根因、代码改动、测试结果和遗留限制追加到本文档，随后进行中文本地提交；不提交用户原有日志和资料目录，不向服务器上传本地项目。

## 完成条件

- 新上传且 ready 的附件可以在当前轮工作流消息中成功绑定并进入上下文。
- 已绑定、已删除、解析失败、跨会话或越权附件不能被前端再次选中，后端继续失败关闭。
- 流式请求发生业务错误时，客户端收到合法 SSE error 事件，不再出现 `HttpMessageNotWritableException`。
- 相关 Java 测试和前端生产构建通过。

## 执行实录

### 根因核对

- 只读查询远程数据库后确认，日志中的附件 `asset_0326decb-9960-42d1-8a03-cc3de32f0921` 状态为 `active/ready`，但已经绑定到消息 `msg_51bbad61-77e9-46ca-876a-31cb0f2eb908`。
- 该消息后续因取消被标记为 `invalidated`，附件引用仍保留原消息归属，符合审计需求。前端原先只判断 `parseStatus/status`，因此将已绑定引用再次放入新消息；后端 SQL 的 `message_id IS NULL` 约束正确拒绝了改绑。
- `ASSET_BIND_DENIED` 在 SSE 已建立后通过 `completeWithError` 冒泡到 MVC，MVC 尝试以 `text/event-stream` 写普通错误对象，引发了二次 `HttpMessageNotWritableException`。

### 代码改动

- 资产 Store 将 `messageId` 非空纳入最终可发送判定，即使 UI 选择状态滞后，发送快照也不会再携带已绑定引用。
- 会话附件列表禁用已绑定项，并显示“已用于消息，重新发送请再次上传”；同时移除解析器实际不支持的 `.doc`，补齐 `.csv/.json` 选择类型，明确图片当前只保存、不注入模型。
- 后端保留“一个附件引用只属于一条消息”的绑定约束，完善 `ASSET_BIND_DENIED` 的可操作错误信息。重新上传相同文件时，现有 SHA-256 去重逻辑会复用 MinIO 对象，但创建新的未绑定资产引用。
- 流式接口将业务异常转换为合法的纯文本 `event:error` SSE 事件后正常结束；前端识别该事件、保留可读错误，并取消、释放当前 Reader。
- 新增 `AgentServiceControllerSseTest`，验证业务失败会发送 SSE 事件并正常 complete，不再调用 `completeWithError`。

### 验证结果

- Java 定向回归：`AssetServiceTest`、`AgentServiceControllerSseTest`、`RunControlServiceTest`、`MyBatisMapperLoadTest` 共 11 项，0 失败、0 错误。
- Web 生产构建：`npm run build` 通过，`vue-tsc --noEmit` 与 Vite 构建均成功。
- 本次不需要数据库结构或数据修改，未向服务器上传本地项目。未执行会写入远程会话数据的真实附件端到端冒烟，以定向领域/控制器测试和前端生产构建完成本地闭环。
