# Frontend Visual Refinement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 Vue 3 控制台收敛为轻量、专业的精炼编辑台，同时保持所有现有业务交互不变。

**Architecture:** 以 `base.css` 作为设计令牌和通用组件密度的单一来源，`ConsoleLayout.vue` 只调整外壳结构，页面 scoped CSS 只处理页面特有布局。聊天页保留既有 Store、接口、事件和输入法逻辑，只重新组织视觉分组与抽屉信息密度。

**Tech Stack:** Vue 3、TypeScript、Vue Router、Pinia、Vite、CSS、Lucide Vue。

## Global Constraints

- 只修改 Vue 展示结构、CSS 变量、页面 scoped 样式和低幅度 CSS 动效。
- 不修改路由定义、Pinia Store、API 请求、DTO、后端代码、事件名称或 `v-model` 字段。
- 不修改聊天流式处理、中文输入法处理、会话筛选与 Agent/工作流切换逻辑。
- 不新增 UI 或动画依赖，不使用大面积渐变、厚重阴影或驾驶舱式卡片墙。
- 所有现有用户未提交修改保持原样；每次提交只暂存本计划涉及的文件。

---

### Task 1: 建立紧凑设计令牌与通用组件密度

**Files:**
- Modify: `ai-agent-scaffold-web/src/styles/base.css`
- Test: `ai-agent-scaffold-web/package.json` 的 `npm run build`

**Interfaces:**
- Consumes: 现有全局类 `.page`、`.card`、`.button`、`.table`、`.stat-grid`、`.badge`。
- Produces: 所有页面复用的间距、圆角、阴影、边框、控件和响应式视觉规则。

- [ ] **Step 1: 记录当前全局样式基线**

运行：

```bash
sed -n '1,620p' ai-agent-scaffold-web/src/styles/base.css
```

确认 `.page`、`.card`、`.button`、`.table` 和媒体查询仍由全局文件管理，避免在页面内重复定义通用控件。

- [ ] **Step 2: 调整设计令牌和通用容器**

在 `:root`、`.page`、`.card`、`.card__body`、`.stat-grid`、`.stat-card` 中使用以下视觉基线：

```css
:root {
  --surface: #fcfcfa;
  --surface-muted: #f4f5f3;
  --line: #e4e7e3;
  --line-strong: #cfd6d0;
  --shadow-sm: 0 10px 28px rgba(24, 32, 42, 0.045);
  --radius-xl: 20px;
  --radius-lg: 16px;
  --radius-md: 12px;
  --radius-sm: 8px;
}

.page { padding: 20px 24px 28px; }
.card { border-radius: var(--radius-lg); box-shadow: none; }
.card__body { padding: 18px; }
.stat-grid { gap: 10px; }
.stat-card { padding: 15px 16px; border-radius: var(--radius-md); }
```

保留现有 class 名称，删除通用卡片 hover 位移，只保留细微边框反馈。

- [ ] **Step 3: 收敛表单、按钮、表格和标签**

将全局控件尺寸统一为 36 至 42px，应用以下规则：

```css
.input, .textarea, .select { min-height: 40px; border-radius: 10px; }
.textarea { min-height: 96px; }
.button { min-height: 36px; padding: 0 13px; border-radius: 9px; }
.table th { height: 40px; font-size: 11px; letter-spacing: 0.06em; }
.table td { padding: 12px 14px; }
.badge { border-radius: 6px; font-size: 11px; }
```

保留所有按钮状态、禁用态和表格数据绑定。

- [ ] **Step 4: 添加可访问的动效降级**

在文件末尾添加：

```css
@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after {
    scroll-behavior: auto !important;
    animation-duration: 1ms !important;
    animation-iteration-count: 1 !important;
    transition-duration: 1ms !important;
  }
}
```

- [ ] **Step 5: 验证构建**

运行：

```bash
npm run build
```

预期：`vue-tsc --noEmit && vite build` 成功结束。

- [ ] **Step 6: 提交全局视觉基础**

```bash
git add ai-agent-scaffold-web/src/styles/base.css
git commit -m "style(web): 收敛全局界面密度和视觉令牌"
```

### Task 2: 收敛控制台外壳与导航层级

**Files:**
- Modify: `ai-agent-scaffold-web/src/layouts/ConsoleLayout.vue`
- Modify: `ai-agent-scaffold-web/src/styles/base.css`
- Test: `ai-agent-scaffold-web/package.json` 的 `npm run build`

**Interfaces:**
- Consumes: `navItems`、`currentTitle`、`currentSubtitle`、`authStore`、`logout()`。
- Produces: 不改变 RouterLink 路径和退出行为的窄导航与轻量顶栏。

- [ ] **Step 1: 保持导航数据和事件不变**

确认 `navItems` 的 `path`、`label`、`icon` 和 `logout()` 函数不变；只调整模板 class 与 CSS 选择器，不新增导航状态。

- [ ] **Step 2: 调整外壳展示分组**

保留以下绑定关系，补充仅用于视觉的容器 class：

```vue
<header class="topbar">
  <div class="topbar__breadcrumb">
    <strong>{{ currentTitle }}</strong>
    <span>{{ currentSubtitle }}</span>
  </div>
  <div class="topbar__actions">...</div>
</header>
```

不得修改 `<RouterLink :to="item.path">`、`@click="logout"`、`authStore.displayName` 或 avatar 计算逻辑。

- [ ] **Step 3: 压缩导航与顶栏样式**

在 `base.css` 更新对应选择器：

```css
.app-shell { grid-template-columns: 60px minmax(0, 1fr); }
.sidebar { padding: 10px 8px; background: rgba(252, 252, 250, 0.92); }
.sidebar__nav { gap: 4px; }
.nav-link { width: 40px; height: 40px; border-radius: 10px; }
.topbar { min-height: 52px; padding: 0 20px; background: rgba(252, 252, 250, 0.86); }
.topbar__title span { display: none; }
.user-chip { padding: 4px 8px 4px 4px; border-color: transparent; background: transparent; }
```

保留 hover tooltip 和 active 路由反馈，但降低颜色对比与阴影。

- [ ] **Step 4: 验证路由入口和构建**

运行：

```bash
npm run build
```

手工检查：点击所有导航图标仍进入原有页面；账号设置与退出按钮仍可用。

- [ ] **Step 5: 提交控制台外壳改造**

```bash
git add ai-agent-scaffold-web/src/layouts/ConsoleLayout.vue ai-agent-scaffold-web/src/styles/base.css
git commit -m "style(web): 精简控制台导航与顶栏"
```

### Task 3: 重构聊天工作台的信息密度

**Files:**
- Modify: `ai-agent-scaffold-web/src/views/chat/ChatWorkspaceView.vue`
- Modify: `ai-agent-scaffold-web/src/styles/base.css`
- Test: `ai-agent-scaffold-web/package.json` 的 `npm run build`

**Interfaces:**
- Consumes: `chatStore`、`toolStore`、`createSession()`、`switchSession()`、`send()`、`onComposerEnter()`、`toggleInsightPanel()`、`openInsightTab()`。
- Produces: 保持冻结会话列、可滚动消息区和固定输入区行为的紧凑聊天布局。

- [ ] **Step 1: 保留聊天行为基线**

确认下列模板和脚本绑定不变：

```vue
<form class="composer" @submit.prevent="send">
<textarea v-model="draft" @compositionstart="onCompositionStart" @compositionend="onCompositionEnd" @keydown.enter="onComposerEnter" />
<button type="button" @click="createSession">新建</button>
<button type="button" @click="switchSession(session.sessionId)">...</button>
```

不得修改 `visibleSessions`、`watch`、流式消息状态或 `insightTabs` 的数据来源。

- [ ] **Step 2: 重新组织仅展示性的聊天头部**

把 `.chat-commandbar` 中的标题与控制器保持为原有 select 绑定，但使用 `chat-commandbar__identity` 和 `runtime-controls` 两个紧凑分区。控制器仍使用：

```vue
<select v-model="chatStore.activeSourceType" @change="onSourceChanged">...</select>
<select v-model="chatStore.activeAgentId" @change="onAgentChanged">...</select>
<select v-model="chatStore.activeWorkflowId" @change="onWorkflowChanged">...</select>
```

- [ ] **Step 3: 将洞察区改为紧凑抽屉**

不改 `v-if="insightPanelOpen"` 或 tab 切换函数；将每个 tab 的展示层保持为以下信息密度：

```css
.insight-panel { border-radius: 14px 14px 0 0; box-shadow: 0 -12px 36px rgba(24, 32, 42, 0.06); }
.insight-tabs { gap: 2px; padding: 8px; border-bottom: 1px solid var(--line); }
.insight-tab { min-height: 30px; padding: 0 9px; border-radius: 7px; }
.context-card, .token-grid { padding: 14px; border: 0; background: transparent; }
.token-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 1px; background: var(--line); }
.token-grid > div { padding: 10px 12px; background: var(--surface); }
```

工具调用项显示工具名、耗时、状态和 traceId，不删除 `toolStore.calls`、`call.errorMessage` 或附件占位按钮。

- [ ] **Step 4: 收紧会话、消息与输入区样式**

使用以下关键尺寸，并保持现有冻结与滚动容器：

```css
.chat-workbench { grid-template-columns: 232px minmax(0, 1fr); gap: 10px; }
.session-rail, .chat-stage { border-radius: 16px; box-shadow: none; }
.message-list { padding: 24px clamp(18px, 5vw, 88px); }
.message { border-radius: 14px; box-shadow: none; }
.composer { padding: 0 14px 14px; }
.composer-surface { border-radius: 14px; }
```

不要修改 `.message-list`、`.session-list` 的 `overflow-y`、`scroll-behavior`、`overscroll-behavior` 或页面高度计算。

- [ ] **Step 5: 验证聊天行为**

运行：

```bash
npm run build
```

手工检查：中文输入法 Enter 只确认候选词；普通 Enter 发送；Shift+Enter 换行；切换会话、切换 Agent/工作流、切换模型、打开五个 `+` 面板页签都保持可用。

- [ ] **Step 6: 提交聊天视觉改造**

```bash
git add ai-agent-scaffold-web/src/views/chat/ChatWorkspaceView.vue ai-agent-scaffold-web/src/styles/base.css
git commit -m "style(web): 重构紧凑聊天工作台"
```

### Task 4: 收敛管理页、占位页与工作流编辑器外观

**Files:**
- Modify: `ai-agent-scaffold-web/src/views/DashboardView.vue`
- Modify: `ai-agent-scaffold-web/src/views/context/ContextInsightView.vue`
- Modify: `ai-agent-scaffold-web/src/views/tokens/TokenUsageView.vue`
- Modify: `ai-agent-scaffold-web/src/views/mcp/McpRegistryView.vue`
- Modify: `ai-agent-scaffold-web/src/views/skills/SkillRegistryView.vue`
- Modify: `ai-agent-scaffold-web/src/views/workflow/WorkflowBuilderView.vue`
- Modify: `ai-agent-scaffold-web/src/styles/base.css`
- Test: `ai-agent-scaffold-web/package.json` 的 `npm run build`

**Interfaces:**
- Consumes: 各页面现有 `v-model`、`@click`、Store 调用和列表渲染。
- Produces: 所有管理页一致的标题、分段操作条、紧凑表单、列表和数据展示外观。

- [ ] **Step 1: 确认不修改页面业务绑定**

逐页保留以下行为：

```vue
@click="toolStore.publishMcp(mcp.mcpId, mcp.currentVersion)"
@click="toolStore.publishSkill(skill.skillId, skill.currentVersion)"
v-model="activeNode.instruction"
@click="saveWorkflow"
```

不得更改 MCP/Skill 发布测试顺序、工作流节点数据结构、上下文/Token 占位数据或总览 Store 调用。

- [ ] **Step 2: 将标题区和操作组改为紧凑工具栏**

在各页面 scoped CSS 中增加统一的局部规则：

```css
.section-header { align-items: center; min-height: 42px; }
.section-header h1 { font-size: clamp(24px, 3vw, 34px); }
.section-header h2 { font-size: 17px; }
.section-header p { margin-top: 4px; font-size: 13px; line-height: 1.55; }
.table-toolbar { min-height: 52px; padding: 10px 14px; }
```

优先使用全局样式；仅在现有 scoped CSS 已覆盖时写页面局部覆盖。

- [ ] **Step 3: 将上下文与 Token 页改为数据摘要样式**

保留原 `StatCard` 与 `FeaturePlaceholder` 组件数据，使用 CSS 让数值块更紧凑：

```css
.stat-card__value { margin-top: 7px; font-size: 23px; }
.placeholder { border-radius: var(--radius-lg); box-shadow: none; }
.placeholder__body { padding: 18px; }
```

不删除 `/context` 或 `/tokens` 路由，不新增数据请求。

- [ ] **Step 4: 统一 MCP、Skill 和工作流的编辑器面**

将发布表单、工具目录、表格卡片和工作流节点属性面板改为同一细边框内容面：

```css
.catalog-item, .auto-tool, .roadmap__item, .identity-list {
  border-radius: 10px;
  border-color: var(--line);
  background: var(--surface-muted);
}

.workflow-canvas, .node-inspector {
  border-radius: 14px;
  box-shadow: none;
}
```

保留工作流画布节点的拖拽、连线 handle、保存、发布和运行按钮。

- [ ] **Step 5: 验证管理功能和构建**

运行：

```bash
npm run build
```

手工检查：MCP 创建/测试/发布、Skill 上传/创建/发布、工作流节点选择/拖拽/保存/发布、上下文与 Token 路由显示均保持可用。

- [ ] **Step 6: 提交管理页视觉改造**

```bash
git add ai-agent-scaffold-web/src/views/DashboardView.vue ai-agent-scaffold-web/src/views/context/ContextInsightView.vue ai-agent-scaffold-web/src/views/tokens/TokenUsageView.vue ai-agent-scaffold-web/src/views/mcp/McpRegistryView.vue ai-agent-scaffold-web/src/views/skills/SkillRegistryView.vue ai-agent-scaffold-web/src/views/workflow/WorkflowBuilderView.vue ai-agent-scaffold-web/src/styles/base.css
git commit -m "style(web): 统一管理页与工作流编辑器视觉"
```

### Task 5: 完成响应式与视觉回归检查

**Files:**
- Modify: `ai-agent-scaffold-web/src/styles/base.css`
- Modify: `ai-agent-scaffold-web/src/views/chat/ChatWorkspaceView.vue`
- Test: `ai-agent-scaffold-web/package.json` 的 `npm run build`

**Interfaces:**
- Consumes: 已完成的全局和聊天样式。
- Produces: 不遮挡固定输入区和会话列的窄屏布局，以及低动效偏好支持。

- [ ] **Step 1: 更新窄屏媒体查询**

在现有移动端规则中使用以下布局，保留 DOM 顺序和事件绑定：

```css
@media (max-width: 840px) {
  .chat-workbench { grid-template-columns: 1fr; }
  .session-rail { max-height: 174px; }
  .chat-page { height: auto; min-height: calc(100vh - 52px); overflow: visible; }
  .chat-stage { min-height: calc(100vh - 246px); }
  .runtime-controls { width: 100%; justify-content: flex-start; overflow-x: auto; }
}
```

- [ ] **Step 2: 检查键盘焦点与禁用态**

确认全局样式未覆盖 `.input:focus`、`.textarea:focus`、`.select:focus`、`.button:disabled`；为 `.nav-link:focus-visible`、`.insight-tab:focus-visible` 与 `.composer-plus:focus-visible` 保留 `outline` 或现有 focus ring。

- [ ] **Step 3: 最终构建与手工验收**

运行：

```bash
npm run build
git diff --check
```

预期：前端构建成功；本计划文件不引入空白错误。检查桌面与 840px 以下视图，确认固定会话列、消息滚动区、输入区与洞察抽屉不重叠。

- [ ] **Step 4: 提交最终响应式修整**

```bash
git add ai-agent-scaffold-web/src/styles/base.css ai-agent-scaffold-web/src/views/chat/ChatWorkspaceView.vue
git commit -m "style(web): 完成精炼工作台响应式修整"
```
