# 附件上下文与 Skill ZIP 字节边界优化计划

## 优化前证据

- 附件上下文查询使用 `SELECT a.*` 且无行数上限，之后才按 Token 预算截断；长会话会先把所有附件正文载入 JVM。
- `ToolPublishService` 与 `ToolGateway` 分别对 ZIP 中的 `SKILL.md` 调用 `readAllBytes()`，恶意或异常压缩包可展开为超大内存对象。

## 执行计划（执行前落盘）

1. 为附件上下文增加独立的最大候选数和累计正文字符/字节硬边界，查询仅返回组装所需列并带 limit；保持最新消息优先、Token 预算与覆盖序号语义。
2. 提取可复用的 ZIP entry 有界读取方法，按流读取 `SKILL.md`，超过上限立即拒绝；两个 Skill 入口统一使用，避免规则漂移。
3. 限制 entry 数/单 entry 展开字节，并覆盖无文件、超限、正常 UTF-8、异常 ZIP 与 zip bomb 风格输入。
4. 回归附件绑定、上下文贡献、Skill 发布与工具网关。

## 验收条件

- 单次附件上下文数据库返回行数有硬上限，JVM 不会先加载无界正文。
- `SKILL.md` 解压读取不再使用 `readAllBytes()`，超过配置上限稳定失败且错误可读。
- 正常附件与 Skill 包功能不变。

## 执行实录

- `ContextPolicyProperties` 新增附件候选 32 行、累计正文 131072 字符的默认硬边界；dev 配置可通过 `AI_CONTEXT_ATTACHMENT_CANDIDATE_LIMIT` 与 `AI_CONTEXT_ATTACHMENT_MAX_CONTENT_CHARS` 调整，其他环境仍有同一代码默认值。
- 附件 SQL 只返回 `asset_id/file_name/sha256/extracted_text` 等组装必要列；内层保持 `sequence_no DESC,a.id DESC` 并 `LIMIT candidateLimit`，外层用窗口累计前序字符数并 `LEFT` 截断边界行，使 JDBC 返回正文总量不超过上限。租户、owner、session、covered/visible 序号与消息有效性条件保持不变。
- `AssetContextContributor` 把两项边界传给仓储，并在领域层再次按累计字符防御，再按原 attachment Token 预算截断、去重和恢复时间正序。
- 新增共享 `SkillPackageReader` 与 `SkillPackageProperties`：默认最多 256 entries、`SKILL.md` 展开最多 1MiB，使用 8KiB buffer 流式读取并严格 UTF-8 解码；扫描完整 ZIP，尾随 entry 不能绕过数量限制。
- `ToolPublishService` 的上传校验/注册/更新和 `ToolGateway` 调用统一使用读取器，目标工具链已无 `ZipInputStream.readAllBytes()`；20MiB 压缩包上传总大小限制保持。
- 测试覆盖附件候选/累计边界、序号参数、Mapper 必要列/LIMIT/窗口/排序、正常 UTF-8、缺文件、尾随 entry 超限、高压缩比展开超限、损坏 ZIP、非法 UTF-8，以及 Skill 发布、网关、会话压缩和资产回归。
- 实现阶段 Java 17 clean reactor 回归 29/29 通过。主审将调度与附件合并执行无并行 clean reactor：共 35/35 通过，0 failure / 0 error / 0 skip，六模块 `BUILD SUCCESS`，总耗时 13.281s；目标文件 `git diff --check` 通过。
