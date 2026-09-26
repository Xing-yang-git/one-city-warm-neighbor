# CLAUDE.md — 项目约定与协作机制

> 本文件承载**项目约定层**：不可从代码直接推导的机制、规范与决策原因。
> 结构性事实（模块、页面、实体、接口清单）以 [README.md](README.md) 为唯一来源，不在此重复。
> 个人偏好与对 Claude 的工作反馈存放在用户主目录记忆中，不入仓库。

## 1. 项目速览

一城暖邻 · 社区互助平台 monorepo，三个独立构建、独立部署的平台共享同一业务域：

| 平台 | 目录 | 技术栈 |
|---|---|---|
| C端（微信小程序） | `miniprogram/` | 原生 WXML + WXSS + JavaScript |
| B端（PC 管理端） | `admin/` | Vue 3 + Vite + Element Plus + Pinia + ECharts（JavaScript） |
| 后端 | `server/` | Spring Boot 3.2 + JPA + PostgreSQL + JWT + WebSocket |

**为什么是 monorepo + 独立构建**：三个平台共享领域逻辑，但用户、构建工具、发布节奏都不同。monorepo 保持协调，独立构建保证各自可独立部署。C端与 B端调用同一个 `server/` REST API。

目录结构、页面/实体/接口清单等详见 [README.md](README.md)。

## 2. 设计参考（非强制）

> **本项目已过原型阶段**（2026-09-13 起）。原型不再作为 UI 改动的强制参照，降级为历史设计资料。

历史原型位于：`D:\notegenWordFile\prototype\社区互助闲置平台原型-C端B端`（仅供查阅）

- `c-end/` → C端原型（HTML+CSS+JS），全局 token 在 `c-end/css/ios-ui.css`，带 `--ios-` 前缀
- `b-end/` → B端原型（HTML+CSS+JS），全局 token 在 `b-end/css/b-end.css`，无前缀

**UI 现状基准是现有代码，不是原型。** 新页面、新交互的视觉与结构以**已确认的产品决策 + 设计规范（[code-standards](.claude/skills/code-standards/SKILL.md)）+ 现有页面组件/交互模式**为准，优先复用（如 chat 页输入区、unified-panel 布局），不凭空造新的视觉体系。

**UI 形态、入口位置、交互方式存在多义时，必须先与用户确认**（记录进实现计划），不许凭空发明——这条与原型无关，至今有效（历史教训：曾自创 tabBar 第 4 项导致返工）。

**Token 转换约定（沿用，既有代码已按此实现）**：C端去掉 `--ios-` 前缀；B端直接使用无前缀名。

**如需对照原型**（如复刻某个历史页面细节），可显式调用 `prototype-alignment` 子代理做像素级对齐——按需操作，不再是默认前置步骤。

## 3. Skill-Agent 架构

严格分层：

| 层 | 内容 | 位置 |
|---|---|---|
| **Skill** | 纯方法论，零硬编码路径，跨项目可复用 | `.claude/skills/<name>/SKILL.md`（项目级）或用户主目录（用户级） |
| **Subagent** | 项目上下文：文件路径、token 映射、scope 规则、平台清单 | `.claude/agents/<name>.md` |

**判定标准**：一个 skill 必须能不加修改地复制到其他项目立即使用。允许示例代码和泛化平台名；禁止绝对路径、项目名、具体目录结构。

**调用模式**：子代理开工前必须先通过 Skill 工具加载其技能，技能是方法论，子代理是导航者。

项目级技能：`pixel-perfect-replication`、`annotation-guarantee`、`security-audit`、`code-standards`、`git-commit-standard`、`test-guarantee`、`database-schema-alignment`
用户级技能：`requesting-code-review`、`receiving-code-review`

## 4. 子代理清单

| 子代理 | 触发方式 | 触发关键词 / 条件 | 技能 | 职责 |
|---|---|---|---|---|
| `prototype-alignment` | **按需** | 需显式点名："对齐原型"、"pixel-perfect"、"按原型复刻"（已过原型阶段，不再是 UI 改动默认前置） | pixel-perfect-replication | C端/B端页面与原型像素级对齐（Capture → Compare → Fix → Verify） |
| `quality-review` | 主动 | "审查"、"review"、"安全检查" | security-audit、annotation-guarantee、test-guarantee 等 | 三端五维审查（安全/质量/注释/测试），产出审查通行证 |
| `database-operator` | **自动** | Entity 字段变更、schema.sql 修改，或 "对齐数据库" | database-schema-alignment | JPA Entity 与 PostgreSQL 实际 schema 对齐，生成幂等 DDL（psql 优先，SchemaMigration.java 备选） |
| `git-save` | 被动 | "保存版本"、"提交"、"commit"、"打个点" | git-commit-standard | 审查门禁执行 + Conventional Commit + **文档同步（README/CLAUDE.md）+ 个人记忆同步** |
| `test-generator` | 被动 | "写测试"、"生成测试"、"补测试" | test-guarantee | 生成 AAA 模式单元测试并运行修复至全绿 |

**为什么 database-operator 自动触发**：schema 与 Entity 不一致会立即导致运行时错误，不像提交那样可由用户择机决定。
**为什么 git-save 被动**：用户明确要求它不得主动建议提交，只响应显式保存指令。
**安全审查统一归 quality-review**：其他子代理不得重复做安全扫描。

## 5. 审查门禁机制

**任何代码提交前必须通过质量审查。** 契约：

```
quality-review ──写──→ .claude/review-reports/review-result.md ──读──→ git-save
                                                     └──提交成功后删除──┘
```

git-save 的三道门禁（详细流程见 [.claude/agents/git-save.md](.claude/agents/git-save.md)）：

1. **Gate 1 — Assessment**：`PASS` 放行；`PASS WITH WARNINGS` 需用户确认；`FAIL` 硬阻断
2. **Gate 2 — Commit hash**：`reviewed-commit` 必须等于当前 HEAD，否则报告过期
3. **Gate 3 — Scope 覆盖**：通行证 scope 必须覆盖本次提交涉及的全部平台

**白名单例外**（经用户确认可跳过审查）：`*.md`/`*.txt` 文档、安全配置（`.gitignore`、`tsconfig.*`、`vite.config.*`）、`application.yml`（配置项，2026-09-26 起纳入；该文件承载阈值、超时、限流等运行期行为参数，提交者需自行核对改动影响，不再由审查门禁兜底）、`.claude/**`、纯样式格式化。**不在白名单**：`.env`、`schema.sql`、`pom.xml`、`package.json` 及一切逻辑文件。

**一次性通行证**：提交成功后 git-save 删除通行证文件，下次提交需重新审查——防止一份陈旧报告覆盖多次提交。

## 6. Git 规范

[Conventional Commits](https://www.conventionalcommits.org/)，**提交主题默认中文**，祈使句，≤ 25 个汉字。

| 改动目录 | scope |
|---|---|
| `miniprogram/**` | `miniprogram` |
| `admin/**` | `admin` |
| `server/**` | `server` |
| 多平台同一功能 | 逗号分隔，如 `admin,server` |
| `.claude/**` | `chore:`（无 scope 或 `chore(.claude):`） |
| 根目录 `*.md` | `docs:`（无 scope） |

**拆分 vs 合并**：默认按平台拆分提交。仅当跨平台改动服务同一功能、拆开会留下破碎中间态、或单独回滚会破坏功能时才合并。一个提交 = 一个完整、可独立审查、可独立回滚的改动。

**分支命名**：`<type>/<short-description>`，小写连字符，如 `feat/rating-system`、`fix/login-npe`。

## 7. 注释规范（2026-07-16 确立）

**所有代码注释一律使用中文**（含 Javadoc/JSDoc 描述、WXML/Vue 模板注释、CSS/WXSS 注释、SQL/YML 注释）：

- 句子主体用中文；JWT、WebSocket、token、Bean、Mock、DTO 等技术术语保留英文原词，不生硬直译
- Javadoc/JSDoc 的 `@param` / `@return` / `@throws` 标签结构保留，描述文字用中文
- 第三方代码（`node_modules/` 等）与被注释掉的代码不受此约束
- 覆盖率与质量标准遵循 `annotation-guarantee` 技能（20%-30%，解释 why 而非 what）

**检测**：quality-review 的注释维度会检查注释语言一致性，发现成段英文注释报 **High**（提交时进入 PASS WITH WARNINGS，需用户确认）。

## 8. 后端重启规则

改动 `server/`（Spring Boot）代码后，**由 Claude 自行编译并重启后端**，绝不让用户手动操作：

1. 编译改动
2. 停掉占用 8080 的旧 java 进程（Windows git bash：`taskkill //PID <pid> //F`）
3. 后台重启：`cd server && mvn -o spring-boot:run`
4. 确认端口就绪 / 启动日志无误后反馈用户

**原因**：后端跑在本地局域网 IP（如 `192.168.31.64:8080`），不重启则小程序打到旧进程，改动等于没生效。前端（小程序/Vue）改动不涉及此流程。

## 9. 记忆维护协议（四层事实来源）

| 层 | 位置 | 承载内容 | 更新时机 |
|---|---|---|---|
| **README.md** | 仓库根 | 结构性事实：模块、页面、实体、接口、启动方式 | git-save 提交前随 diff 同步（Step 2.5） |
| **CLAUDE.md** | 仓库根 | 项目约定与机制（本文件） | 约定/机制变更时随提交同步（Step 2.5） |
| **已知问题台账** | `docs/已知问题台账.md` | 已发现但**未决策修改**的问题（尤其「同值不同域」等不可机械替换项） | quality-review 审查前后读写（Step 0 / Step 5.5） |
| **用户主目录 memory** | `~/.claude/projects/<本项目>/memory/` | 仅个人偏好与对 Claude 的工作反馈 | git-save 提交成功后自省同步（Step 5.5） |

**判定标准**：能从代码推导的（数量、清单、结构）→ README.md；不能从代码推导但属于项目的（约定、机制、决策原因）→ CLAUDE.md；**已知但未决**的问题（含「看着像问题、实际不能改」的误报）→ 台账；只关于用户个人的（沟通语言、工作习惯、反馈）→ memory。

**执行者**：git-save 子代理在每次提交流程中负责前两层同步（结构变了改 README，约定变了改 CLAUDE.md，并随本次提交入库），提交后自省第四层；quality-review 子代理负责台账层——**审查前读**（已登记项不重复上报）、**审查后写**（新发现的未决项登记入账）。这保证文档与问题清单都不再与代码脱节。

## 10. 代码生成规范（2026-07-22 确立）

**生成任何新代码前，确保 `code-standards` 规范已在上下文中。** 这是事前预防机制——让代码生成时就符合规范，而非依赖事后审查返工。

### 加载时机（核心：加载一次，持久复用）

Skill 内容加载后会一直留在会话上下文中，**不需要每轮对话重复加载**。只有在以下情况才需调用 `Skill("code-standards")`：

1. **会话中首次涉及代码生成** — 上下文里还没有规范内容，调用一次加载
2. **上下文被压缩/摘要后** — 规范内容可能被裁剪掉，需重新加载（判断标准：如果你记不清某个平台的铁律细节，说明规范已不在上下文中，此时重新加载）
3. **规范文件本身有更新** — 如果 SKILL.md 最近被修改过，重新加载以获取最新版本

以下场景**需要规范在上下文中**（首次加载后后续任务自动满足）：

| 场景 | 说明 |
|------|------|
| 新建文件 | 任何平台的新 `.java` / `.vue` / `.ts` / `.js` / `.wxml` / `.wxss` 文件 |
| 新增方法/函数 | 在已有文件中添加新的 public 方法或导出函数 |
| 新增组件/页面 | C端新 Page/Component、B端新 View/Component |
| 新增 API 端点 | 后端新 Controller 方法或新 Controller 类 |
| 重构 | 移动/重命名文件、调整分层结构 |

以下场景不需要规范在上下文中（简单操作，规范不相关）：

- 修复单行 bug（如拼写错误、空指针判空）
- 删除代码
- 纯样式微调（调间距、改字号，不涉及 HTML/WXML 结构变更）
- 给已有代码补注释（注释规范另有 `annotation-guarantee`）
- 阅读/搜索/调试代码

### 执行流程

```
用户请求"新增/创建/写一个…"
       │
       ├── 规范已在上下文中？
       │       │
       │       ├── 是 → 直接写代码（规范内容已在对话中）
       │       │
       │       └── 否 → Skill("code-standards")（加载一次，后续复用）
       │
       ▼
  生成的代码自带规范合规性
       │
       ▼
  quality-review（审查时再次验证，形成双保险）
```

### 规范速查（规范已在上下文中时，此处作为快速对照清单；首次加载前请先调用 Skill）

| 平台 | 铁律 |
|------|------|
| 通用 | 命名：类 PascalCase、方法 camelCase、常量 UPPER_SNAKE；禁止拼音和单字母变量 |
| 通用 | 异常：禁止吞异常、禁止 `printStackTrace()`；最外层必须兜底处理 |
| 通用 | 日志：含业务上下文、禁止循环中 INFO、禁止打印敏感信息 |
| 通用 | 常量：已定义常量时必须引用（`STATUS.PENDING`），禁止魔术字符串（`'pending'`） |
| C端 | px ×2 → rpx；颜色必须 `var(--*)`；button 重置 `::after { border: none }`；禁止 `backdrop-filter`；代码逻辑中禁止中文字符串判断（如 `status === '已下架'`），必须使用常量引用（`POST_STATUS.DRAFT`）；常量按业务域分离（POST_STATUS / AUTH_STATUS / BORROW_STATUS 等），禁止混放在单一对象中 |
| B端 | `<style scoped>`；`v-for` 必须 `:key`；Props/Emits 有类型；禁止无理由 `any`；函数参数必须显式类型；interface/type 和导出函数必须有 JSDoc 注释；API DTO interface/type 各字段必须有行内注释；API 封装在 `api/` 模块，请求/响应有类型 |
| 后端 | Controller 不写 try-catch；不直接返回 Entity；构造器注入；SQL 参数绑定；URL 名词复数；DTO 类每个字段必须有 Javadoc 注释；已定义状态常量时必须引用（如 `BizStatus.PENDING`） |

> 完整规范（包括 SHOULD/MAY 级规则、命名示例、代码模板）见 [.claude/skills/code-standards/SKILL.md](.claude/skills/code-standards/SKILL.md)。

### 与审查机制的关系

```
code-standards（事前预防）──生成合规代码──→ quality-review（事后验证）
                                              │
                                              ├── 通过 → git-save 提交
                                              │
                                              └── 不通过 → 返工（违反铁律意味着审查 FAIL）
```

**双重保障**：生成时遵循规范减少返工，审查时再次验证确保无遗漏。规范违规在审查中最低报 Medium（MUST 级违规），严重者阻断提交。
