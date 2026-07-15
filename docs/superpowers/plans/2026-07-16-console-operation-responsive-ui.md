# 控制台写操作反馈与响应式表格优化计划

## 设计方向

沿用现有“专业 Agent 编辑台”视觉：高信息密度、稳定导航锚点、明确操作层级和克制状态动效。不增加装饰性大依赖，不用纯颜色或透明度代替操作文字。

## 优化前证据

- Workflow 保存与发布可并发，快速切换 Workflow 时旧详情响应可覆盖新选择。
- Skill/MCP 的发布、测试、禁用以及 Schedule 的启停、立即执行、历史查询缺少按资源维度的 pending/error/防重。
- Skill/MCP 宽表直接放入 `overflow:hidden` 卡片，窄屏右侧操作列不可达。
- 全局 z-index 为零散数字，缺少 nav/sticky/popover/modal/toast 语义 token。

## 执行计划（执行前落盘）

1. Workflow Store 建立单一写操作状态，保存/发布/删除互斥；详情请求引入 generation 或 AbortController，只有最新选择可回写。
2. 对 Skill/MCP/Schedule 写操作建立 `operationType:resourceId` 行级 key，按钮展示正在进行的动作，冲突操作禁用，重复点击只产生一个请求。
3. 成功/失败反馈就近显示并具有 `aria-live`，失败保留可重试动作，不吞掉 Promise rejection。
4. Skill/MCP 表格增加可键盘滚动容器和粘滞操作列；窄屏优先使用资源卡/字段标签布局，保证状态和操作可达。
5. 在全局样式定义 z-index 语义 token，将现有关键层级迁移到 token，不无限抬高数值。
6. 执行 Vue 类型检查/生产构建，静态复核 320/390/768/1024 宽度，追加实录后中文提交。

## 验收条件

- 所有已覆盖写按钮 100ms 内显示 pending，请求期间不可重复提交。
- Workflow 旧详情响应不会覆盖最新选择，保存/发布/删除不并发。
- 320–768px 下 Skill/MCP 状态和操作全部可达，无页面级横向溢出。
- 所有叠层使用统一 z-index token，关键反馈支持屏幕阅读器感知。

## 执行实录

- Workflow：新增单一 `writeOperation`，保存/发布/删除互斥，详情加载期禁止写入；详情请求引入 generation，旧选择的迟到响应不能覆盖新工作流；发布仅在保存成功后继续。
- Skill/MCP：以 `operationType:resourceId` 建立行级 pending/成功/失败状态，同一资源的重复或冲突请求不会再发；Store 保留行级错误并继续抛出，视图解锁后可就地重试。
- Schedule：启停、立即执行、重试和历史查询按配置行互斥，动作文案和结果就地反馈；历史查询增加 generation，旧配置响应不覆盖新选择。
- 响应式：Skill/MCP 在桌面端提供可键盘聚焦的横向滚动和粘滞操作列，`<=768px` 转为字段标签资源卡；320px 下 MCP 三个操作按钮可按 72px basis 换行。Schedule 两张宽表支持键盘横向滚动和粘滞操作列。
- 层级：全局新增 content/sticky/nav/popover/modal/toast 语义 z-index token，`src` 内已有数字层级全部迁移，`rg` 确认无数字硬编码 z-index。
- 可访问性：行级与工作流反馈使用 `aria-live`，宽表滚动容器具有 `tabindex` 和语义标签。
- 验证：`npm run build` 通过（`vue-tsc` + Vite，1914 modules，实现阶段 859ms/812ms，主审复跑 1.07s）；目标文件 `git diff --check` 通过；静态源码断言覆盖 320/390/768 卡片断点与 1024 宽表操作可达。
- 限制：本地无可用登录态/JWT，项目未配置 Playwright，未执行真实后端写请求的浏览器 E2E 与截图；请求并发/错误路径通过代码审查和类型生产构建验证。
