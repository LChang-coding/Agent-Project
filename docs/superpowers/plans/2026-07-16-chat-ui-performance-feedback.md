# 会话页性能、布局与操作反馈优化计划

## 优化前证据

- 分享条作为 Grid 第四个子元素插入只定义三行的工作区，消息区/组合器会落入隐式行并被裁切。
- session watcher 与 `createSession/switchSession` 重复加载 tool calls。
- 流式更新每字符都对全部消息 `map + join`，并频繁 smooth scroll。
- 按钮主要靠替换文字表达 loading，页面缺少统一的当前操作/成功/错误可见区域。

## 执行计划（执行前落盘）

1. 用明确 grid areas/四行布局承载命令栏、状态条、消息和组合器，修复分享条显隐造成的裁切。
2. 将 sessionId watcher 作为会话附属资源刷新的单一入口，移除显式重复 tool call 请求。
3. 流式渲染只观察最后一条消息的长度/版本，用 requestAnimationFrame 合并自动滚动；用户离开底部时不强制拉回。
4. 在会话页增加明确的运行状态表达，展示正在生成/取消/引导/上传/分享与失败原因，不覆盖消息。
5. 保持专业编辑台视觉方向，统一层级 token，修复矮屏消息区和组合器可达性。
6. 执行 Vue 类型检查/生产构建，可行时做多 viewport 截图/交互验证；追加实录后中文提交。

## 验收条件

- 分享条显示/隐藏时消息区和组合器均在 stage 内。
- 切换会话不重复请求 tool calls；流式更新不扫描全量消息正文。
- 高频操作 100ms 内可见当前状态，失败原因不使用成功色。
- 1024x600 与 390x844 下编辑器、消息区和主操作不被裁切。

## 执行实录

- 会话 stage 改为 command/status/messages/composer 明确四行 Grid，分享结果合并到常驻状态栏，不再动态插入未定义第四行。
- 状态栏统一显示刷新、上传、生成、取消、引导、分享、成功和错误，使用 `aria-busy/aria-live`；错误使用 danger 语义，不再使用成功色。
- sessionId immediate watcher 成为 tool calls + insight 的单一切换入口，移除 create/switch/delete/onMounted 中的重复请求；`tools.ts` 增加请求代际，防止旧会话慢返回覆盖新会话。
- 流式观察从全量消息 `map + join` 改为最后消息 id/长度/状态；自动滚动使用 requestAnimationFrame 合并，用户离开底部 72px 后停止强制跟随。
- 布局使用 `dvh`，composer 限高并内部滚动，增加矮屏/390px 规则，静态复核 1024x600 与 390x844 的主操作可达性。
- `npm run build` 通过：Vite 7.3.6，1914 modules，约 833ms；`git diff --check` 通过。本地无可用 JWT 测试身份，未伪造认证或声称已完成登录态浏览器 E2E。
