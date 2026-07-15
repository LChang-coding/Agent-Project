# 长会话分段加载与渲染窗口优化计划

## 优化前证据

- `reloadSessionMessages` 循环请求全部历史页后一次性写入 Store；会话越长，切换耗时、网络量和内存线性增长。
- 会话视图直接 `v-for=chatStore.messages` 渲染全部消息，2000 条历史会形成 2000 个 article，并放大 TransitionGroup patch 成本。
- 主 chunk gzip 64.36KiB，高于基线目标 50KiB；路由已懒加载，可评估稳定 vendor 分包而不增加依赖。

## 执行计划（执行前落盘）

1. 会话切换只读取最新一页，保存 `nextBeforeSequence/hasMore`；在消息区顶部提供“加载更早消息”，按页前插并做 generation 防乱序/防重。
2. DOM 只保留有界可见窗口（目标 <=100 条），在加载更早历史时维持滚动锚点；当前流式消息始终可见，发送/取消/引导语义不变。
3. 为历史加载补 pending/success/error/disabled 反馈与 `aria-live`，避免用户点击后不知道状态。
4. 配置稳定的 Vue 核心与 Axios vendor 分包，比较主 chunk、总 JS 与路由 chunk，若总量或缓存收益无改善则回退。
5. 执行类型检查/生产构建和静态窗口测试；无登录态时明确记录 E2E 限制。

## 验收条件

- 切换会话只请求一页，初始 DOM 消息 <=100；旧页按需加载且无重复/乱序。
- 加载历史时按钮立即反馈，失败可重试，滚动位置不跳到底部。
- 主 chunk gzip <=50KiB，单路由 JS gzip <=10KiB，总 JS 不高于基线 285.27KiB。

## 执行实录

- 会话切换现在先同步清空旧会话消息与分页状态，再仅请求最新 50 条；慢请求期间显示明确的“正在载入最近消息”占位，不会短暂展示上一会话内容。reload 统一递增 generation 一次，旧响应和旧 finally 均不能回写。
- Store 保存 `nextBeforeSequence/hasMoreMessages`，更早历史按 cursor 每页 50 条加载，具备 pending 防重、generation 防乱序、messageId 去重前插以及成功/失败重试/到达起点反馈。
- 消息渲染移除 `TransitionGroup`，`visibleMessages` 严格 `slice(start,start+100)`，article DOM 恒不超过 100；已载入历史以 50 条步长浏览，底部提供 sticky“返回最新消息”。
- 历史前插和本地窗口移动会记录首条 article 相对滚动容器的位置，`nextTick` 后按差值恢复 `scrollTop`；发送/流式期间禁止向前浏览，竞态完成后强制回到最新窗口，保证流式消息可见。
- `loadingMessages/loadingEarlierMessages/historyMessage/historyErrorMessage` 已接入按钮、状态栏、`aria-busy` 与 `aria-live`。长会话本地标题优先保留服务端已有标题，避免最新页不含首条用户消息时错误改名。
- Vendor 分包实验可把入口降到 4,987B gzip，但总 JS 会由未分包实验口径 301,595B/118,542B gzip 增到 303,192B/119,878B gzip；按计划回退，`vite.config.ts` 无最终差异。
- 最终实现阶段 `npm run build` 通过（1914 modules，1.03s）；主审补加载占位后复跑仍通过（835ms），主入口 165.19kB / gzip 63.61kB，Chat route 21.48kB / gzip 7.78kB，chat store 16.08kB / gzip 4.79kB，最大路由仍 <=10KiB。实现阶段同口径 JS 为 27 chunks、总 raw 301,762B / gzip 118,616B。
- 量化结论：初始只请求一页、DOM <=100、单路由 <=10KiB 已达标；主入口 <=50KiB 和总 JS 不高于早期 285.27KiB 基线未达标。后者受本阶段新增取消/分享/资产/控制台等功能总量影响，本轮不以增加总量的分包结果伪装达标。
- 15 项静态断言覆盖单页请求、无全量循环、切换清空、代次、防重去重、50/100 边界、锚点与流式竞态，`git diff --check` 通过。
- 限制：本地无可用登录态/JWT 与 Playwright，未对真实 2000 条会话执行浏览器 E2E、网络延迟注入或像素级锚点截图。
