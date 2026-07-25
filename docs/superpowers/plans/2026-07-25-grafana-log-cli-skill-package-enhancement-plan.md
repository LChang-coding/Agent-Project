# Grafana 日志 Skill 内置 CLI 增量闭环计划

## 需求确认

在现有 `grafana-log-inspector` Skill 已携带四平台 CLI 二进制的基础上，把 CLI
提升为 Skill 的正式、可发现、可安装、可升级组成部分，而不是只能通过 Skill
内部脚本间接调用。同时完善发布清单、自检、安装、帮助文档和隔离验收。

## 本轮目标

1. 保留 Skill 内置 darwin/linux、amd64/arm64 CLI，不依赖项目源码或本机 Go。
2. 增加确定性的 CLI 安装入口，可安装为用户级 `grafana-log` 命令，且不复制
   `codex.md` 或任何凭据。
3. 增加离线自检入口，验证平台、版本、资源清单、二进制哈希和执行能力；远端
   连通性由显式参数控制，避免自检默认访问生产环境。
4. 增加机器可读发布清单，明确 CLI 版本、平台、压缩包、SHA-256 和入口。
5. 精简并补强 `SKILL.md`，明确优先直接执行 CLI、何时安装、何时运行 doctor、
   如何安全处理业务日志。
6. 从隔离 Skill 目录验证直接执行、安装后的裸命令、离线自检、真实只读 doctor
   和一条真实链路查询；重新安装到 `~/.codex/skills`。

## 硬门禁

- 真实地址、账号、密码、Token、SSH 信息继续只存在于本机 `codex.md`。
- Skill、CLI、测试、计划、发布清单和 Git 提交不得含真实凭据。
- 不改远端 Grafana/Loki/Alloy，不新增任何写接口。
- 安装只写用户指定目录或默认 `~/.local/bin`，采用临时文件加原子替换；不能
  覆盖目录，不能静默覆盖非本工具文件。
- 发布脚本失败不能破坏上一版可用 Skill CLI 资源。
- 完成后追加真实执行结果，并以中文信息本地提交；不提交既有日志、对象存储、
  RAG 文档和无关未跟踪文件。

## 执行步骤

1. 设计 Skill 内置 CLI 清单、安装与自检契约。
2. 实现 `install-cli`、`self-test` 及公共平台/哈希校验逻辑。
3. 完善发布脚本和 Skill 指令，补齐脚本自动化测试。
4. 构建四平台资源并验证两次构建哈希一致。
5. 在隔离目录和用户安装目录执行端到端验收。
6. 追加本计划执行记录，精确暂存并中文提交。

## 执行记录

### 2026-07-25 Skill 内置 CLI 契约

- 保留并重建 Skill 内四个真实 CLI 压缩二进制：darwin/amd64、
  darwin/arm64、linux/amd64、linux/arm64。Skill 运行不依赖项目源码、本机 Go
  或远端下载。
- 将平台识别、SHA-256、资源选择、解压缓存统一到
  `scripts/lib/cli-runtime.sh`，`scripts/grafana-log`、`install-cli`、
  `self-test` 使用同一套可信入口，避免三份实现漂移。
- CLI 缓存键包含版本和压缩资源哈希；同版本二进制变化不会复用旧缓存。
- 新增 `assets/bin/RELEASE.json`，机器可读记录 schema、CLI 名称、版本、直接
  入口、安装入口以及四个平台资源和 SHA-256。离线自检会交叉核对
  `VERSION`、`SHA256SUMS` 和 `RELEASE.json`。

### 2026-07-25 安装与自检完善

- 新增 `scripts/install-cli`，默认把真实可执行文件安装到
  `~/.local/bin/grafana-log`，支持 `--bin-dir` 和显式 `--force`。
- 安装采用同目录临时文件、`0700` 权限和原子 `mv`；相同版本重复安装为幂等，
  目标为目录时拒绝，目标内容不同时默认拒绝覆盖。安装过程不读取、不复制、
  不写入 `codex.md`。
- 新增 `scripts/self-test`：默认离线验证四个平台压缩包、发布清单、当前平台
  选择、解压和 CLI 版本；只有显式 `--doctor` 才访问真实 Grafana。
- 新增 `scripts/test-skill-package.sh`，覆盖隔离 Skill、自检、安装、幂等重装、
  陌生文件拒绝覆盖、显式强制升级和压缩包篡改拦截。
- 修正旧版 `SKILL.md` 从目标项目目录执行时使用 `scripts/grafana-log` 相对路径
  可能找不到 Skill 的问题。新版优先安装裸命令，也给出基于
  `${CODEX_HOME:-$HOME/.codex}` 的绝对 Skill 入口。
- 使用 `generate_openai_yaml.py` 重新生成 UI 元数据，使描述和默认提示与
  “内置独立 CLI + 链路诊断”保持一致。

### 2026-07-25 构建与自动化验收

- CLI 发布版本提升到 `v0.2.0`。
- `go test -race ./...`：通过。
- `go vet ./...`：通过。
- 所有 Skill shell 入口执行 `sh -n`：通过。
- `scripts/test-skill-package.sh`：通过，包含真实篡改负例。
- `quick_validate.py skill/grafana-log-inspector`：通过。
- 从工具目录连续执行两次 `make release`，`SHA256SUMS` 完全一致：
  - darwin/amd64：
    `6663844d94b9dcce8241636827941cc34a7fda401de18069c93643171a9cb1a7`
  - darwin/arm64：
    `d7ba0addc4c90268d02bbe675f184855933d2d54271fe2b9e113627beba21dac`
  - linux/amd64：
    `62e8520a6c0fb485f9dca1de4e4b60407f6e4342791466657648d742e5b8836c`
  - linux/arm64：
    `401927bcfd5a86b94f70bedbfe875b5a40ae0469b93f5ca14c83cd5c564068ed`

### 2026-07-25 隔离与真实端到端验收

- 将 Skill 单独复制到 `/tmp`，隔离 HOME 和缓存执行 `self-test`，返回
  `status=ok`、`version=v0.2.0`、当前平台 `darwin/arm64`、四项资源通过。
- 从隔离 Skill 安装到临时 bin 目录，裸命令 `grafana-log --version` 返回
  `v0.2.0`，证明 CLI 已实际包含在 Skill 包内而非依赖项目构建目录。
- 临时安装的 CLI 使用本机 `codex.md` 执行真实只读 `doctor`，得到 Grafana
  `11.0.0`、datasource `loki`、类型 `loki`、访问方式 `proxy`。
- 使用一条既有真实 trace（完整 ID 不进入提交）查询最近 24 小时，得到 40 条
  日志、0 失败、0 降级、0 取消、0 未闭合阶段；最慢阶段为 Run `15545ms`。
- 已把完整 v0.2.0 Skill 更新到 `~/.codex/skills/grafana-log-inspector`，并将
  CLI 安装到 `~/.local/bin/grafana-log`，权限为 `0700`。当前 zsh PATH 已包含
  `~/.local/bin`，可直接执行 `grafana-log`。
- 从最终用户安装位置再次执行 `doctor` 成功。Skill 和 CLI 均不携带真实地址、
  账号或认证值，运行时仍只从目标项目本机 `codex.md` 读取有界配置块。
