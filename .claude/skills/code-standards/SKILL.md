---
name: code-standards
description: 代码规范审查 — 在代码审查时加载，检查命名、格式、结构、异常处理、日志等规范是否符合项目约定。在用户说"代码规范"、"coding standards"、"命名规范"、"检查格式"时调用，或作为 quality-review 代码审查维度的补充技能。
---

# Code Standards（代码规范）

## 概述

定义跨三平台（微信小程序 C端、Vue 3 B端、Spring Boot 后端）的代码规范体系。聚焦于**可被静态检查的结构性规则**——命名、格式、组织、模式选取。不重复安全审查（`security-audit`）、注释审查（`annotation-guarantee`）、测试审查（`test-guarantee`）的领域。

**本技能定位**：纯方法论——定义规范本身和检查流程。调用方（quality-review 子代理或开发者）提供具体的文件路径、平台上下文、语言约定。

## 核心原则

1. **一致性优于个人偏好**。规范的存在意义是降低认知切换成本，而非追求"最优"风格。
2. **可自动化的尽量自动化**。能用 lint 工具（ESLint、Checkstyle、Prettier）检查的规则优先配置工具，人工审查聚焦于工具无法判断的语义层面。
3. **规范分级**：**必须（MUST）** = 提交前必须满足，违反即阻断；**应该（SHOULD）** = 尽量满足，有合理理由可豁免；**建议（MAY）** = 推荐但非强制。
4. **平台特异规则优先于通用规则**。当通用规则与平台惯例冲突时，以平台惯例为准（如小程序 rpx 优先于 px）。
5. **规范随代码演进**。发现规范不合理时，先改规范再改代码，不要因为"规范这么写的"而保留糟糕的设计。

---

## 第一部分：通用规范（三平台均适用）

### 1.1 命名规范

#### 1.1.1 通用规则

| 元素 | 规范 | 级别 | 示例 |
|------|------|------|------|
| 类/接口/组件名 | PascalCase | **必须** | `UserService`, `LoginView`, `JwtTokenProvider` |
| 方法/函数名 | camelCase | **必须** | `getUserById()`, `handleLogin()` |
| 变量名 | camelCase | **必须** | `userName`, `idleList` |
| 常量 | UPPER_SNAKE_CASE | **必须** | `MAX_RETRY_COUNT`, `DEFAULT_PAGE_SIZE` |
| 布尔变量 | `is*` / `has*` / `can*` / `should*` 前缀 | **应该** | `isActive`, `hasPermission`, `canEdit` |
| 集合变量 | 复数形式或 `*List` / `*Map` 后缀 | **应该** | `users`, `itemList`, `statusMap` |
| 事件处理器 | `on*` / `handle*` 前缀 | **应该** | `onSubmit`, `handleClick` |

#### 1.1.2 禁用的命名

| 禁止 | 原因 | 级别 |
|------|------|------|
| 单字母变量（循环索引 `i`/`j`/`k` 除外） | 无语义 | **必须** |
| 拼音命名 | 非中文团队不可读 | **必须** |
| 拼音英文混用 | 如 `getShouhuoAddress()` | **必须** |
| 双关语/玩笑命名 | 如 `fakeItTillYouMakeIt()` | **应该** |
| 否定式布尔名（除非必要） | `isNotActive` → 用 `!isActive` | **应该** |
| 过度缩写 | `usrCrtDt` → `userCreateDate` | **应该** |

### 1.2 文件与目录命名

| 平台 | 文件命名 | 目录命名 |
|------|----------|----------|
| 通用 | kebab-case（`user-service.ts`） | kebab-case（`user-management/`） |
| Java 类文件 | 与类名一致，PascalCase | 包名全小写，点分隔 |
| Vue 组件文件 | PascalCase（`LoginView.vue`）或 kebab-case | 与路由一致 |

### 1.3 函数/方法规模

| 指标 | 上限 | 级别 |
|------|------|------|
| 单方法行数 | ≤ 50 行 | **应该** |
| 单方法参数个数 | ≤ 5 个 | **应该**（超过时考虑封装为参数对象） |
| 单文件行数 | ≤ 500 行 | **建议** |
| 圈复杂度 | ≤ 10 | **应该** |
| 嵌套层级 | ≤ 3 层 | **应该** |

### 1.4 导入/引用顺序

**通用规则**：导入按"外部依赖 → 内部模块 → 相对路径"分组，组间空行分隔。

```
// 1. 第三方/框架
import React from 'react';
import { ElMessage } from 'element-plus';

// 2. 内部模块（别名路径）
import { userApi } from '@/api/user';
import { useAuthStore } from '@/stores/auth';

// 3. 相对路径
import { formatDate } from '../utils/date';
import type { User } from './types';
```

Java 对应分组：

```java
// 1. JDK
import java.time.LocalDateTime;
import java.util.List;

// 2. 第三方框架
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 3. 项目内部
import com.platform.model.entity.User;
import com.platform.repository.UserRepository;

// 4. 静态导入
import static org.assertj.core.api.Assertions.assertThat;
```

### 1.5 异常处理规范

| 规则 | 级别 | 说明 |
|------|------|------|
| 禁止吞异常（空 catch 块） | **必须** | 至少记录日志或重新抛出 |
| 禁止 `catch (Exception)` 后仅 `e.printStackTrace()` | **必须** | 使用日志框架 |
| 禁止用异常做流程控制 | **必须** | 异常仅用于异常情况 |
| 业务异常使用项目统一异常类 | **必须** | 不使用通用 `RuntimeException("错误")` |
| finally 块不得抛出异常 | **必须** | finally 中的操作必须自己处理异常 |
| 最外层（Controller/入口）必须兜底异常处理 | **必须** | 避免 500 堆栈泄露到前端 |

### 1.6 日志规范

| 场景 | 日志级别 | 说明 |
|------|----------|------|
| 关键业务流程节点 | INFO | 登录、下单、状态变更 |
| 外部 API 调用 | INFO | 记录调用参数和响应码 |
| 异常捕获 | ERROR | 包含完整堆栈和业务上下文 |
| 调试信息 | DEBUG | 开发环境可用，生产环境关闭 |
| 潜在问题但不影响主流程 | WARN | 如降级、重试成功、配置缺失使用默认值 |

| 规则 | 级别 | 说明 |
|------|------|------|
| 日志必须包含业务上下文 | **必须** | 如 userId、orderId，不能只有堆栈 |
| 禁止循环中打印 INFO 日志 | **必须** | 避免日志爆炸 |
| 禁止日志中打印敏感信息 | **必须** | 密码、token、身份证号等 |
| 禁止字符串拼接构造日志消息 | **应该** | 使用参数化占位符 `log.info("user {} login", userId)` |

### 1.7 业务常量引用规范

> **规则**：当项目中已定义了业务状态常量（如 `PostStatus`、`BorrowStatus`、`POST_TYPE`）时，所有业务代码**必须**引用该常量，**严禁使用裸字符串字面量**进行比较、赋值或条件判断。

| 规则 | 级别 | 说明 |
|------|------|------|
| 状态比较/赋值必须使用已定义的常量 | **必须** | `STATUS.PENDING` 而非 `'pending'` |
| 模板/JSX 中的条件判断同样适用 | **必须** | `v-if="tab === STATUS.PENDING"` 而非 `v-if="tab === 'pending'"` |
| API 请求参数中的状态值同样适用 | **必须** | `{ status: STATUS.PENDING }` 而非 `{ status: 'pending' }` |
| 常量文件自身是定义的唯一来源（定义内部使用字面量是合法的） | — | `constants.ts` 中 `PENDING: 'pending'` 是定义，不是违规 |

**为什么禁止**：常量集中管理的价值不在于"少敲几个字符"，而在于——
- **重构安全**：后端或产品改状态值（如 `'pending'` → `'waiting'`）时只改一处
- **拼写错误防御**：`'pendng'` `'pening'` 能在编译期发现，裸字符串在运行期才会暴露
- **IDE 支持**：常量引用可跳转、可重命名、可查找引用；裸字符串不行
- **代码意图表达**：`STATUS.PENDING` 的语义比 `'pending'` 清晰得多

```typescript
// ✅ 正确：引用已定义常量
import { STATUS } from '../utils/constants';
if (activeTab.value === STATUS.PENDING) { ... }
const params = { status: STATUS.APPROVED };

// ❌ 错误：裸字符串字面量 — 即使值与常量完全相同也是违规
if (activeTab.value === 'pending') status = STATUS.PENDING;  // 比较裸写 'pending'
if (activeTab.value === 'all') status = STATUS.APPROVED;      // 比较裸写 'all'
```

```java
// ✅ 正确：引用常量类
if (BorrowStatus.PENDING.equals(borrowRequest.getStatus())) { ... }

// ❌ 错误：魔术字符串
if ("pending".equals(borrowRequest.getStatus())) { ... }
```

```javascript
// ✅ 正确：微信小程序中引用 constants
const { STATUS } = require('../../utils/constants');
if (item.status === STATUS.PENDING) { ... }

// ❌ 错误
if (item.status === 'pending') { ... }
```

---

## 第二部分：C端规范（微信小程序）

### 2.1 WXML 模板规范

| 规则 | 级别 | 说明 |
|------|------|------|
| `wx:for` 必须带 `wx:key` | **必须** | 使用唯一字段，不用 `*this`（除非列表项为字符串） |
| 条件渲染优先用 `wx:if`，频繁切换用 `hidden` | **应该** | `wx:if` 有销毁/重建成本 |
| 模板中的业务状态字面量使用 `utils/constants.js` 导出的常量 | **必须** | 如 `{{STATUS.PENDING}}` 而非 `{{'pending'}}` |
| 所有 `<button>` 标签的 WXSS 必须包含 `button::after { border: none; }` | **必须** | 微信默认 button 边框样式 |
| 图片使用 `<image>` 标签，必须带 `mode` 属性 | **应该** | 明确缩放/裁剪策略 |
| 禁止在 WXML 中使用内联 SVG | **必须** | 使用 `<image src="*.svg">` |
| 大段文本使用 `<text>` 而非 `<view>` | **建议** | text 组件支持内联和选中 |

### 2.2 WXSS 样式规范

| 规则 | 级别 | 说明 |
|------|------|------|
| 全局设计 token 定义在 `app.wxss` 的 `page {}` 块 | **必须** | 变量名去掉原型 `--ios-` 前缀，如 `--ios-primary` → `--primary` |
| 页面 WXSS 禁止硬编码颜色值 | **必须** | 使用 `var(--*)` 引用设计 token |
| 原型 px 值转换 rpx：`×2` | **必须** | 16px → 32rpx |
| 禁止使用 `backdrop-filter` | **必须** | 小程序不支持 |
| `@keyframes` 动画定义在 `app.wxss`，不在页面 WXSS | **必须** | 动画全局注册才能生效 |
| WXSS 使用 `/* ─── 区块名 ─── */` 格式的区块标记 | **应该** | 分隔大段样式 |

### 2.3 JS 逻辑规范

| 规则 | 级别 | 说明 |
|------|------|------|
| 页面文件必须有 Page-level JSDoc | **必须** | 说明页面用途、主要功能 |
| 生命周期方法按顺序排列：`onLoad` → `onShow` → `onReady` → `onHide` → `onUnload` → `onPullDownRefresh` → `onReachBottom` | **应该** | 统一查找路径 |
| `setData` 只传必要字段，不传整个 data 对象 | **应该** | 减少渲染开销 |
| API 调用封装在 `utils/api.js` 中 | **必须** | 页面不直接调用 `wx.request` |
| 业务状态字面量引用 `utils/constants.js` | **必须** | 如 `STATUS.PENDING` 而非裸字符串 `'pending'` |
| 禁止在 `console.log` 中打印敏感信息 | **必须** | token、用户手机号等 |
| 工具函数放 `utils/`，页面级辅助函数放页面 JS 顶部（`Page({})` 之外） | **建议** | 便于单元测试 |

---

## 第三部分：B端规范（Vue 3 + TypeScript）

### 3.1 SFC 结构规范

Vue 单文件组件的区块顺序（推荐）：

```vue
<!-- 文件级注释：组件用途、权限、依赖 -->
<script setup lang="ts">
// 1. 导入（第三方 → 内部模块 → 相对路径）
// 2. 类型定义（Props / Emits / 局部类型）
// 3. 组合式函数（composables）
// 4. 响应式状态（ref / reactive）
// 5. 计算属性（computed）
// 6. 监听器（watch）
// 7. 生命周期（onMounted / onUnmounted）
// 8. 方法
</script>

<template>
<!-- 区块标记注释 -->
</template>

<style scoped>
/* 区块标记注释 */
</style>
```

| 规则 | 级别 | 说明 |
|------|------|------|
| `<style scoped>` 必须出现在每个组件中 | **必须** | 防止样式泄露到其他组件 |
| `<script setup lang="ts">` 为默认脚本格式 | **必须** | 不使用 Options API（除非历史遗留） |
| `v-for` 必须带 `:key` | **必须** | key 使用唯一 ID，不用 index |
| `v-if` 和 `v-for` 不得同时出现在同一元素 | **必须** | 先用 computed 过滤 |
| 逻辑较多的模板用 `v-if` 而非 `v-show` | **应该** | 频繁切换（如 tab）用 v-show |

### 3.2 TypeScript 规范

| 规则 | 级别 | 说明 |
|------|------|------|
| Props 必须有类型定义（`defineProps<T>()` 或运行时声明） | **必须** | 不使用无类型 Props |
| Emits 必须有类型定义 | **必须** | `defineEmits<{...}>()` |
| 禁止 `any`（无合理理由时） | **必须** | 如确需使用，添加注释说明原因 |
| Composables 命名以 `use*` 开头 | **必须** | 如 `useAuth`, `useItemList` |
| 类型和接口使用 PascalCase | **应该** | `UserInfo`, `LoginForm` |
| 类型导入使用 `import type { ... }` | **应该** | 编译后会被完全擦除 |
| 函数参数必须有显式类型注解 | **必须** | 禁止无类型的函数参数（如 `function foo(x)` → `function foo(x: string)`） |
| 函数返回值应有类型注解（尤其是导出函数） | **应该** | `export function getList(): Promise<Item[]>` |
| 响应式变量应有显式类型注解 | **应该** | `ref<string>('')` 优于 `ref('')`（当类型无法从初始值推断时） |
| Reactive 对象应有 interface 定义其结构 | **应该** | `reactive<FormData>({...})` 而非裸对象 |
| B端项目中的 `.js` 文件应逐步迁移为 `.ts`（admin 已配置 `tsconfig` + `strict: true`） | **应该** | `.js` 文件不受 tsconfig 管理，导入时引发"隐式 any"错误；C端（小程序）无 tsconfig，不适用此规则 |
| `as const` 应用于字面量常量对象 | **建议** | `export const STATUS = {...} as const` 可推导出字面量联合类型 |

#### 3.2.1 TypeScript 类型注解示例

```typescript
// ✅ 正确：函数参数有类型，返回值有类型
async function fetchContent(params: ContentListParams): Promise<void> {
  const res = await getContentList(params);
  tableData.value = res.data?.content || [];
}

// ❌ 错误：函数参数无类型
async function fetchContent(params) {
  const res = await getContentList(params);
  tableData.value = res.data?.content || [];
}

// ✅ 正确：响应式变量有显式类型
const loading = ref<boolean>(false);
const tableData = ref<ContentRow[]>([]);
const activeTab = ref<string>('show');

// ❌ 错误：响应式变量类型模糊
const data = ref();        // 类型为 Ref<undefined>
const items = ref([]);     // 类型为 Ref<never[]>
```

### 3.3 B端注释规范

> 注释覆盖率与质量标准由 `annotation-guarantee` 技能统一管理。本节仅定义 B端特有的**结构性注释规则**——即类型定义、变量声明、函数签名等代码元素上**必须携带注释**的结构要求。

| 规则 | 级别 | 说明 |
|------|------|------|
| 所有 `interface` / `type` 定义必须有 JSDoc 注释说明其用途 | **必须** | `/** 登录表单数据结构 */ interface LoginForm {...}` |
| 所有导出函数必须有 JSDoc 注释（含 `@param` / `@returns`，描述用中文） | **必须** | 解释函数做什么（why），而非重复函数名（what） |
| Store 的 state / getters / actions 每个字段和方法都应有注释 | **应该** | state 字段说明含义，action 说明业务语义 |
| 组件内关键响应式变量（`ref` / `reactive`）应有行内注释 | **应该** | 说明该变量的业务用途，如 `/** 当前激活的标签页 */` |
| 复杂计算属性（`computed`）应有注释说明其计算逻辑 | **应该** | 特别是涉及多字段聚合或筛选的 computed |
| Props 的每个字段应有行内注释说明含义 | **应该** | `/** 卡片标题 */ title: { type: String, required: true }` |
| API DTO interface / type 的每个字段应有行内注释说明含义 | **必须** | 包括 `admin/src/api/` 和 `admin/src/types/` 下定义的数据结构 |
| 注释语言统一使用中文 | **必须** | 遵循 CLAUDE.md §7 注释规范，技术术语保留英文原名 |

```typescript
// ✅ 正确：类型、函数、变量均有注释；DTO 字段有行内注释
/** 审核列表行数据结构 */
interface AuditRow {
  /** 用户 ID */
  id: number;
  /** 住户姓名 */
  name: string;
  /** 房号（如 "3栋2单元1502"） */
  room: string;
}

/** 通用分页响应 */
interface PageDTO<T> {
  /** 当前页数据列表 */
  content: T[];
  /** 数据总条数 */
  totalElements: number;
  /** 总页数 */
  totalPages: number;
  /** 当前页码（从 0 开始） */
  currentPage: number;
  /** 每页条数 */
  size: number;
}

/**
 * 根据筛选条件拉取审核列表并转换为行数据。
 * @param status - 审核状态过滤
 * @returns 转换后的行数据列表
 */
async function loadAuditList(status: string): Promise<AuditRow[]> {
  const res = await getAudits({ status });
  return (res.data?.content || []).map(toAuditRow);
}

// 响应式状态
/** 当前激活的标签页 key */
const activeTab = ref<string>('pending');
/** 各标签页的计数 */
const tabCounts = reactive<TabCounts>({ pending: 0, approved: 0, rejected: 0 });
```

```typescript
// ❌ 错误：缺少注释和类型
interface AuditRow {
  id: number;
  name: string;
  room: string;
}

function loadAuditList(status) {       // 无返回值类型，参数无类型
  const res = await getAudits({ status });
  return (res.data?.content || []).map(toAuditRow);
}

const activeTab = ref('pending');      // 无注释，无显式类型
```

### 3.4 Element Plus 使用规范

| 规则 | 级别 | 说明 |
|------|------|------|
| 覆盖 Element Plus 内部样式必须用 `:deep()` | **必须** | 否则 scoped 下不生效 |
| 表单使用 `el-form` + `el-form-item`，配合 rules 校验 | **应该** | 不手写校验逻辑 |
| API 调用必须有 loading 和 error 状态 | **必须** | 配合 `v-loading` 或手动管理 |
| `v-html` 使用前必须确认内容已净化 | **必须** | 标记所有 `v-html` 供安全审查 |

### 3.5 状态管理（Pinia）规范

| 规则 | 级别 | 说明 |
|------|------|------|
| Store 文件命名 kebab-case，导出 use 函数 | **应该** | `stores/auth.ts` 导出 `useAuthStore()` |
| actions 中放异步操作，getters 放纯计算 | **必须** | actions 可以有副作用，getters 不能 |
| 不在组件中直接修改 store state | **必须** | 通过 action 修改，保证可追踪 |
| Store 中不存储 UI 状态（如 loading） | **建议** | UI 状态放组件内 |

### 3.6 API 封装规范

| 规则 | 级别 | 说明 |
|------|------|------|
| API 调用必须封装在专用 API 模块中（如 `admin/src/api/`），组件不直接导入 `get/post/put/del` | **必须** | 对照 C端 §2.3 "API 调用封装在 utils/api.js 中" |
| API 模块按业务域拆分文件（如 `api/auth.ts`、`api/admin.ts`），每个文件导出语义化函数 | **应该** | 如 `login(phone, password)` 而非 `post('/api/auth/login', data)` |
| API 函数的请求参数和返回值必须有 TypeScript 类型 | **必须** | 不写 `any`，不给 `data` 直接标 `object` |

```typescript
// ✅ 正确：专用 API 模块 + 类型定义
// api/admin.ts
interface ContentListParams {
  statusTab?: string
  type?: 'idle' | 'help'
  building?: string
  unit?: string
  search?: string
  page: number
  size: number
}

interface ContentItemDTO {
  id: number
  type: string
  title: string
  // ...
}

import { get } from '@/utils/api'
export function getContentList(params: ContentListParams) {
  return get<PageDTO<ContentItemDTO>>('/api/admin/content', params)
}
```

```typescript
// ❌ 错误：组件内直接调 get/post，参数无类型
const params = { statusTab: tab, page: 0, size: 10 }
const res = await get('/api/admin/content', params)
```

**模块目录结构**：

```
admin/src/api/
  auth.ts        // login, logout, getStatus
  admin.ts       // getDashboard, getContentList, auditUser, ...
  index.ts       // 统一 re-export
```

### 3.7 共享类型规范

| 规则 | 级别 | 说明 |
|------|------|------|
| API 请求/响应 DTO 类型统一定义在 `admin/src/types/` 目录 | **应该** | 与后端 DTO 字段一一对应，避免散落在各组件中 |
| 跨模块共享的分页/通用类型抽到 `types/common.ts` | **应该** | 如 `PageDTO<T>`、`Result<T>` |
| 类型定义使用 `interface`（优先）或 `type`，命名 PascalCase | **应该** | `ContentListParams`、`ContentItemDTO` |

---

## 第四部分：后端规范（Spring Boot + Java）

### 4.1 分层架构规范

```
controller/   → 薄层：参数校验、调用 service、组装响应
service/      → 业务逻辑：事务边界、编排多个 repository
repository/   → 数据访问：纯 JPA 操作，无业务逻辑
model/entity/ → 持久化实体：纯数据结构，无业务逻辑
model/dto/    → 数据传输对象：对外 API 的契约
config/       → Spring 配置类
security/     → 认证授权基础设施
common/       → 跨层共享：Result、异常、常量
```

| 规则 | 级别 | 说明 |
|------|------|------|
| Controller 不包含业务逻辑 | **必须** | 仅负责参数解析、调用 service、返回响应 |
| Entity 不泄露到 Controller 层 | **必须** | Controller 返回 DTO，不返回 Entity |
| Service 层标注 `@Transactional(readOnly = true)` 或按写操作标注 `@Transactional` | **必须** | 写操作（insert/update/delete）必须带事务 |
| Repository 接口不写业务判断 | **必须** | 仅数据访问方法 |
| 禁止在 Controller 中使用 `try-catch` | **必须** | 统一交给 GlobalExceptionHandler |

### 4.2 命名规范

| 元素 | 规范 | 示例 |
|------|------|------|
| Controller 类 | `*Controller` | `IdleController`, `AuthController` |
| Service 接口 | `*Service` | `IdleService` |
| Service 实现 | `*ServiceImpl` | `IdleServiceImpl` |
| Repository 接口 | `*Repository` | `UserRepository` |
| Entity 类 | 表名对应 PascalCase | `User`, `IdleItem` |
| DTO 类 | `*DTO` / `*Request` / `*Response` | `LoginRequest`, `IdleItemDTO` |
| 配置类 | `*Config` | `SecurityConfig`, `CorsConfig` |
| 常量类 | `*Constants` 或 `*Status` | `PostStatus`, `BorrowStatus`, `PostType` |
| Controller URL | 复数名词，小写连字符 | `/api/idle-items`, `/api/users` |

### 4.3 REST API 设计规范

| 规则 | 级别 | 说明 |
|------|------|------|
| 所有 API 响应使用统一的 `Result<T>` 包装 | **必须** | 禁止直接返回裸对象 |
| URL 使用名词复数 | **必须** | `GET /api/idle-items` 而非 `/api/getIdleItems` |
| HTTP 方法语义正确 | **必须** | GET=查询, POST=创建, PUT=全量更新, PATCH=部分更新, DELETE=删除 |
| 分页接口使用统一的 `PageDTO` | **必须** | 分页参数：`page`, `size` |
| 请求体带 `@Valid` / `@Validated` 校验 | **必须** | 配合 JSR-303 注解 `@NotNull`, `@NotBlank` |
| 禁止在 URL 中暴露内部 ID 时不做鉴权 | **必须** | 每个资源操作需验证归属 |

### 4.4 数据库访问规范

| 规则 | 级别 | 说明 |
|------|------|------|
| JPA 查询使用参数绑定（`?1` / `:name`），禁止字符串拼接 | **必须** | 防止 SQL 注入 |
| Entity 关联默认 `FetchType.LAZY` | **必须** | 显式声明 `@EntityGraph` 用于 eager 场景 |
| 写操作（INSERT/UPDATE/DELETE）的 Repository 方法加 `@Modifying` | **必须** | |
| `Optional` 用于可能为 null 的查询 | **应该** | `findById()` 返回 Optional，不用 `!= null` 判断 |
| `equals()` 和 `hashCode()` 如用于 Set/HashMap 必须正确实现 | **必须** | 基于业务主键，不基于数据库自增 ID |
| 禁止在实体中写业务逻辑 | **必须** | 实体只承载数据和简单字段约束 |

### 4.5 Bean 与依赖注入规范

| 规则 | 级别 | 说明 |
|------|------|------|
| 优先构造器注入，禁用字段注入（`@Autowired` 在字段上） | **必须** | 构造器注入便于测试和不可变性保证 |
| `@Service` / `@Repository` / `@Controller` 等构造型注解不重复 | **应该** | 一个类一个构造型 |
| 配置属性使用 `@ConfigurationProperties` 而非散落的 `@Value` | **建议** | 类型安全，IDE 支持更好 |

### 4.6 WebSocket 规范

| 规则 | 级别 | 说明 |
|------|------|------|
| WebSocket 握手阶段完成认证 | **必须** | 不在 connect 后才校验身份 |
| 连接生命周期管理完整 | **必须** | onOpen / onClose / onError 都需处理 |
| 消息格式统一（JSON），包含消息类型字段 | **必须** | 如 `{"type": "CHAT", "payload": {...}}` |

### 4.7 DTO 字段注释规范

> DTO 是对外 API 的契约，其字段含义直接影响前后端协作。每个字段必须携带 Javadoc 注释说明其业务含义。

| 规则 | 级别 | 说明 |
|------|------|------|
| DTO 类（`*DTO` / `*Request` / `*Response`）的每个字段必须有 Javadoc 注释 | **必须** | 说明字段的业务含义，如 `/** 借用方姓名 */` |
| 枚举值或可穷举的字符串字段必须注明取值范围 | **必须** | `/** 状态：pending(待审批) / approved(已通过) / rejected(已驳回) */` |
| 可能有歧义的单位（金额、时长等）必须注明单位 | **必须** | `/** 单次最多借出天数 */`（标明是天而非小时） |
| 注释语言统一使用中文 | **必须** | 遵循 CLAUDE.md §7 注释规范，技术术语保留英文原名 |

```java
// ✅ 正确：每个字段有 Javadoc 说明
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BorrowResponseDTO {
    /** 借用记录 ID */
    private Long id;
    /** 被借用物品 ID */
    private Long idleId;
    /** 被借用物品标题 */
    private String idleTitle;
    /** 状态：pending(待审批) / approved(已同意) / rejected(已拒绝) / returned(已归还) */
    private String status;
    /** 损坏类型：none(无损坏) / minor(轻微) / major(严重)；仅归还时由借出方填写 */
    private String damageType;
    /** 是否按时归还（null 表示尚未归还） */
    private Boolean isOnTime;
    /** 归还照片 URL 列表（JSON 数组字符串） */
    private String returnPhotos;
    /** 借入申请创建时间 */
    private LocalDateTime createdAt;
}
```

```java
// ❌ 错误：字段无任何注释，调用方无法理解字段含义
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BorrowResponseDTO {
    private Long id;
    private Long idleId;
    private String idleTitle;
    private String status;         // 什么状态？有哪些取值？
    private String damageType;     // 含义不详
    private Boolean isOnTime;      // 什么时候为 null？
    private String returnPhotos;   // URL？路径？JSON？
    private LocalDateTime createdAt;
}
```

### 4.8 表字段名常量规范

> **规则**：每个 JPA Entity 对应的数据库表，必须有一个独立的常量类（命名 `{EntityName}Column`），集中管理该表所有字段名。Entity 的 `@Column(name = ...)` / `@JoinColumn(name = ...)` / `@UniqueConstraint(columnNames = {...})` 注解中禁止使用裸字符串，必须引用对应的常量。

| 规则 | 级别 | 说明 |
|------|------|------|
| 每个数据库表必须有一个对应的字段名常量类 | **必须** | 命名规范：`{EntityName}Column`（如 `UsersColumn`、`IdleItemsColumn`） |
| `@Column(name = ...)` 必须引用常量类中的字段 | **必须** | `@Column(name = UsersColumn.COL_ROOM_ID)` 而非 `@Column(name = "room_id")` |
| `@JoinColumn(name = ...)` 同样适用此规则 | **必须** | `@JoinColumn(name = UsersColumn.COL_ROOM_ID, insertable = false, updatable = false)` |
| `@UniqueConstraint(columnNames = {...})` 同样适用此规则 | **必须** | `@UniqueConstraint(columnNames = {UsersColumn.COL_PHONE, UsersColumn.COL_TENANT_ID})` |
| `@Table(name = ...)` 的 name 属性也使用常量 | **应该** | `@Table(name = UsersColumn.TABLE_NAME)` |
| 常量字段命名：`COL_` + 字段名大写蛇形 | **必须** | `COL_USER_ID`、`COL_AUTH_STATUS`、`COL_CREATED_AT` |
| 常量值必须与数据库 schema 的字段名严格一致 | **必须** | schema.sql 中 `room_id` → `COL_ROOM_ID = "room_id"` |
| 常量类必须有类级 Javadoc，说明对应哪张表 | **必须** | `/** users 表字段名常量，与 db/schema.sql 中的列名保持一致 */` |
| 常量类位置：`model/entity/column/` 包下 | **必须** | 与 Entity 邻近，便于查找 |

**常量类模板**：

```java
/**
 * users 表字段名常量 — 与数据库 schema（db/schema.sql）严格一致。
 * <p>所有使用 users 表字段名的 JPA 注解（@Column、@JoinColumn、@UniqueConstraint）
 * 必须引用本类常量，禁止硬编码字符串。</p>
 */
public final class UsersColumn {
    /** 工具类，禁止实例化 */
    private UsersColumn() {}

    /** 表名 */
    public static final String TABLE_NAME = "users";

    /** 用户 ID（自增主键） */
    public static final String COL_ID = "id";
    /** 房间 ID，外键 → rooms.id */
    public static final String COL_ROOM_ID = "room_id";
    /** 所属小区 ID，外键 → tenants.id */
    public static final String COL_TENANT_ID = "tenant_id";
    /** 微信 openid */
    public static final String COL_OPENID = "openid";
    /** 用户名 */
    public static final String COL_USERNAME = "username";
    /** 密码哈希 */
    public static final String COL_PASSWORD_HASH = "password_hash";
    /** 用户类型：owner(业主) / tenant(租户) / admin(管理员) */
    public static final String COL_USER_TYPE = "user_type";
    /** 真实姓名 */
    public static final String COL_NAME = "name";
    /** 手机号 */
    public static final String COL_PHONE = "phone";
    /** 手机号是否已验证 */
    public static final String COL_PHONE_VERIFIED = "phone_verified";
    /** 头像 URL */
    public static final String COL_AVATAR_URL = "avatar_url";
    /** 认证状态：pending(待审核) / approved(已通过) / rejected(已驳回) */
    public static final String COL_AUTH_STATUS = "auth_status";
    /** 封禁原因 */
    public static final String COL_BANNED_REASON = "banned_reason";
    /** 认证材料图片（JSON 数组） */
    public static final String COL_DOC_IMAGES = "doc_images";
    /** 驳回原因 */
    public static final String COL_REJECT_REASON = "reject_reason";
    /** Token 版本号（C端单会话登录控制） */
    public static final String COL_TOKEN_VERSION = "token_version";
    /** 创建时间 */
    public static final String COL_CREATED_AT = "created_at";
    /** 更新时间 */
    public static final String COL_UPDATED_AT = "updated_at";
}
```

**Entity 引用示例**：

```java
// ✅ 正确：引用常量类
import com.platform.model.entity.column.UsersColumn;

@Entity
@Table(name = UsersColumn.TABLE_NAME, uniqueConstraints = {
    @UniqueConstraint(columnNames = {UsersColumn.COL_PHONE, UsersColumn.COL_TENANT_ID}),
    @UniqueConstraint(columnNames = {UsersColumn.COL_ROOM_ID, UsersColumn.COL_USER_TYPE})
})
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = UsersColumn.COL_ROOM_ID)
    private Long roomId;

    @Column(name = UsersColumn.COL_USER_TYPE, nullable = false, length = 20)
    @Builder.Default
    private String userType = UserType.OWNER;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = UsersColumn.COL_ROOM_ID, insertable = false, updatable = false)
    private Room room;
}

// ❌ 错误：硬编码字段名
@Column(name = "room_id")              // 应引用 UsersColumn.COL_ROOM_ID
@JoinColumn(name = "tenant_id")        // 应引用 UsersColumn.COL_TENANT_ID
@UniqueConstraint(columnNames = {"phone", "tenant_id"})  // 应引用常量
```

**为什么必须这么做**：
- 字段名变更时只改常量类一处，所有 Entity 引用自动同步
- 字段名与 schema.sql 的对应关系一目了然
- IDE 支持：字段名常量可跳转、可重命名、可查找引用
- 避免拼写错误（如 `"rom_id"` 这种只在运行时才会暴露的 typo）

### 4.9 固定值字段常量规范

> **规则**：Entity 中取值范围可穷举的字符串字段（如类型、状态、单位），必须创建对应的常量类，集中管理所有合法取值。Entity 的默认值（`@Builder.Default`）、Service 层比较/赋值等所有引用必须使用常量，禁止裸字符串。

| 规则 | 级别 | 说明 |
|------|------|------|
| 取值可穷举的字符串字段必须有对应的常量类 | **必须** | 如 `durationUnit`（day/week/month）→ `DurationUnit` 常量类 |
| Entity 默认值必须引用常量 | **必须** | `@Builder.Default private String durationUnit = DurationUnit.DAY;` 而非 `= "day";` |
| Service 层比较/赋值必须引用常量 | **必须** | `if (DurationUnit.DAY.equals(req.getDurationUnit()))` 而非 `if ("day".equals(...))` |
| 常量类定义在 `com.platform.common` 包中 | **必须** | 与 PostStatus、PostType 同级 |
| 常量类必须有完整的 Javadoc | **必须** | 类 Javadoc 说明字段所属表及用途；常量 Javadoc 说明中文含义 |
| 常量类的字符串值必须与数据库存储值、前端契约严格一致 | **必须** | 值不可修改；新增取值需前后端同步 |
| 常量类按**业务域**拆分：同一字段的全部取值归入一个类 | **必须** | 如 idle_items.status 与 help_requests.status 共用内容生命周期 → `PostStatus` |
| 同值跨域必须分开定义，禁止「多表共用就合并」 | **必须** | `pending` 同时存在于 PostStatus / AuthStatus / BorrowStatus / HelpApplicationStatus 四个类，按字段归属各自定义，不可互相引用（历史教训：曾因合并为单个 `BizStatus` 导致语义混淆） |
| 单表专用的固定值独立建常量类 | **应该** | 如 pickupMethod 仅 idle_items 使用 → PickupMethod |
| 常量类必须同步到 C端 `utils/constants.js` 和 B端 `utils/constants.ts` | **必须** | B端标记 `as const` 确保字面量类型推断 |

**常量类模板**（遵循已有 PostStatus/PostType 模式）：

```java
/**
 * 借出时长单位常量 — idle_items.duration_unit 字段的唯一合法取值。
 * <p>与 C端 miniprogram/utils/constants.js 的 DURATION_UNIT 和
 * B端 admin/src/utils/constants.ts 的 DURATION_UNIT 保持一致。</p>
 */
public final class DurationUnit {
    /** 工具类，禁止实例化 */
    private DurationUnit() {}

    /** 按天计算 */
    public static final String DAY = "day";
    /** 按周计算 */
    public static final String WEEK = "week";
    /** 按月计算 */
    public static final String MONTH = "month";
}
```

### 4.10 Controller/Service/Entity 注释结构规范

> **规则**：Controller、Service、Entity 是项目的核心结构层，每个类、每个 public 方法、每个持久化字段都必须有 Javadoc 注释。注释语言统一使用中文（技术术语保留英文原名）。

| 规则 | 级别 | 说明 |
|------|------|------|
| 每个 Controller 类必须有类级 Javadoc | **必须** | `/** 闲置物品管理 REST API — 发布、浏览、搜索、下架、修改、删除 */` |
| 每个 @GetMapping / @PostMapping / @PutMapping / @DeleteMapping 端点方法必须有 Javadoc | **必须** | 含 @param / @return，说明接口用途、权限要求、返回数据结构 |
| 每个 Service 类必须有类级 Javadoc | **必须** | `/** 闲置物品业务逻辑 — 发布、搜索、详情、下架、删除 */` |
| 每个 Service public 方法必须有 Javadoc | **必须** | 含 @param 约束、@return 状态、@throws 场景 |
| 每个 Entity 类必须有类级 Javadoc | **必须** | `/** 闲置物品实体，对应 idle_items 表 */` |
| 每个 Entity 字段必须有 Javadoc | **必须** | `/** 发布用户 ID，外键 → users.id */`；取值可穷举的字段必须注明取值范围 |
| 注释语言统一使用中文 | **必须** | 遵循 CLAUDE.md §7，技术术语保留英文原名 |

```java
// ✅ 正确：Controller 示例
/**
 * 闲置物品管理 REST API。
 *
 * <p>提供 C端闲置物品的完整生命周期管理：
 * <ul>
 *   <li>发布出借/求借物品</li>
 *   <li>首页流浏览（按 postType 筛选、分页）</li>
 *   <li>关键词搜索（租户隔离）</li>
 *   <li>物品详情</li>
 *   <li>下架 / 删除 / 修改</li>
 * </ul>
 *
 * <p>管理员可通过代发功能以目标住户身份发布内容（resolveUserId 逻辑）。</p>
 */
@RestController
@RequestMapping("/api/idle-items")
public class IdleController {

    private final IdleService idleService;

    public IdleController(IdleService idleService) {
        this.idleService = idleService;
    }

    /**
     * 发布闲置物品（出借或求借）。
     *
     * @param req  闲置物品发布请求体（标题、分类、图片、借出时长等）
     * @param auth 当前认证用户
     * @return 创建成功的闲置物品摘要
     */
    @PostMapping
    public Result<?> publish(@Valid @RequestBody IdleItemRequest req, Authentication auth) {
        // ...
    }
}

// ✅ 正确：Entity 示例
/**
 * 闲置物品实体，对应 idle_items 表。
 *
 * <p>支持出借（LEND）和求借（WANTED）两种发布类型。
 * 物品状态流转：online（展示中）→ reserved（已预订）→ returned（已归还）/ offline（已下架）。</p>
 */
@Entity
@Table(name = IdleItemsColumn.TABLE_NAME)
public class IdleItem {
    /** 物品 ID（自增主键） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 发布用户 ID，外键 → users.id */
    @Column(name = IdleItemsColumn.COL_USER_ID, nullable = false)
    private Long userId;

    /** 发布类型：LEND(出借) / WANTED(求借)，引用 {@link PostType} */
    @Column(name = IdleItemsColumn.COL_POST_TYPE, nullable = false, length = 10)
    @Builder.Default
    private String postType = PostType.LEND;

    /** 借出时长单位：day(天) / week(周) / month(月)，引用 {@link DurationUnit} */
    @Column(name = IdleItemsColumn.COL_DURATION_UNIT, nullable = false, length = 10)
    @Builder.Default
    private String durationUnit = DurationUnit.DAY;
}
```

### 4.11 API 路径常量规范

| 规则 | 级别 | 说明 |
|------|------|------|
| API 路径基路径定义为常量 | **应该** | `public static final String API_IDLE_ITEMS = "/api/idle-items";` |
| 路径常量集中放在 `common/ApiPaths.java` | **应该** | 便于全局搜索、重构和路径一致性检查 |

### 4.12 其他集中化常量规范

| 常量类型 | 位置 | 级别 | 说明 |
|----------|------|------|------|
| 分页默认值 | `common/PageDefaults.java` | **应该** | `DEFAULT_PAGE = 0`, `DEFAULT_SIZE = 10` |
| JWT Claim 名称 | `security/JwtClaims.java` | **应该** | `CLAIM_USER_ID = "userId"`, `CLAIM_USER_TYPE = "userType"` |
| 角色名称 | `security/RoleNames.java` | **应该** | `ROLE_ADMIN`, `ROLE_SUPER_ADMIN` |
| WebSocket 消息类型 | `websocket/WsMessageType.java` | **应该** | `CHAT_MESSAGE = "chat_message"` |

---

## 第五部分：审查执行流程

### 5.1 本技能在 quality-review 中的集成点

本技能在 quality-review 工作流的 **Step 3（Code Review）** 阶段加载，补充 `requesting-code-review` 技能的审查清单。

```
quality-review 工作流:

Step 2: Security Audit  ← security-audit 技能
Step 3: Code Review     ← requesting-code-review 技能
                       ← code-standards 技能（本技能）← HERE
Step 4: Annotation      ← annotation-guarantee 技能
Step 4.5: Test          ← test-guarantee 技能
```

### 5.2 审查步骤

1. **识别改动文件所属平台**（miniprogram / admin / server）
2. **加载对应平台的规范清单**（本技能第二~四部分）
3. **逐文件检查**：
   - 命名是否符合规范
   - 文件/目录结构是否符合约定
   - 异常处理和日志是否恰当
   - 平台特有规则是否满足（rpx、Vue 结构、分层架构等）
4. **发现项分级**：
   - 违反 **必须** 级规则 → 至少 Medium
   - 违反 **应该** 级规则 → Info
   - 违反 **建议** 级规则 → Suggestion（不计入阻断计数）

### 5.3 发现项报告格式

```
| # | File:Line | Platform | Rule | Level | Issue |
|---|-----------|----------|------|-------|-------|
| 1 | IdleController.java:23 | server | 构造器注入 | Medium | 字段上使用 @Autowired，应改为构造器注入 |
| 2 | chat.wxss:45 | miniprogram | 颜色 token | Medium | 硬编码 #333，应使用 var(--text-primary) |
```

### 5.4 与其他技能的边界

| 检查项 | 归属技能 | 本技能不重复 |
|--------|----------|-------------|
| 注释覆盖率/质量 | `annotation-guarantee` | ✓ |
| 注释语言（中文） | `annotation-guarantee` + CLAUDE.md §7 | ✓ |
| 类型/函数/变量注释的结构性存在（有或无） | **本技能** §3.3 | — |
| TypeScript 类型注解的结构性存在（有或无） | **本技能** §3.2 | — |
| 安全漏洞 | `security-audit` | ✓ |
| 测试覆盖率/质量 | `test-guarantee` | ✓ |
| UI 像素对齐 | 视觉对齐类技能/子代理（与代码规范无关） | ✓ |
| 数据库 schema 对齐 | `database-schema-alignment` | ✓ |
| 命名/结构/分层/异常/日志 | **本技能** | — |

### 5.5 豁免场景

以下场景可豁免特定规范，但需在审查报告中标注理由：

- 第三方/自动生成的代码（标注路径和生成工具）
- 历史遗留代码（仅在 diff-review 中，如本次未改动则不报；全量审查时仍报告但标记为"历史遗留"）
- 明确标注了 `// NOSONAR` 或 `// standards:disable-next-line` 的行

---

## 附录 A：快速参考卡片

### 命名速查

| | C端 (JS) | B端 (TS/Vue) | 后端 (Java) |
|---|---|---|---|
| 类/组件 | PascalCase | PascalCase | PascalCase |
| 函数/方法 | camelCase | camelCase | camelCase |
| 变量 | camelCase | camelCase | camelCase |
| 常量 | UPPER_SNAKE | UPPER_SNAKE | UPPER_SNAKE |
| 文件名 | kebab-case | PascalCase 或 kebab-case | PascalCase（类文件） |
| 目录名 | kebab-case | kebab-case | 全小写（包名） |

### 平台特有铁律

| 平台 | 铁律 | 级别 |
|------|------|------|
| 通用 | 已定义常量时禁止魔术字符串（用 `STATUS.PENDING` 而非 `'pending'`） | **必须** |
| C端 | 所有 px ×2 → rpx | **必须** |
| C端 | 颜色必须用 `var(--*)` | **必须** |
| C端 | button 必须重置 `::after { border: none }` | **必须** |
| C端 | 禁止 `backdrop-filter` | **必须** |
| C端 | 业务状态字面量引用 `utils/constants.js` | **必须** |
| B端 | `<style scoped>` | **必须** |
| B端 | `v-for` 必须带 `:key` | **必须** |
| B端 | 禁止无理由 `any` | **必须** |
| B端 | 函数参数必须有显式类型注解 | **必须** |
| B端 | interface/type 定义必须有 JSDoc 注释 | **必须** |
| B端 | API DTO interface/type 各字段必须有行内注释 | **必须** |
| B端 | 导出函数必须有 JSDoc 注释（含 @param/@returns） | **必须** |
| B端 | 注释语言统一使用中文 | **必须** |
| B端 | API 调用封装在 `api/` 模块，禁止组件内直接调 `get/post` | **必须** |
| B端 | API 请求/响应必须有 TypeScript 类型定义 | **必须** |
| B端 | 业务状态字面量引用 `utils/constants.ts`，模板中用 `v-if="tab === STATUS.PENDING"` | **必须** |
| 后端 | Controller 不捕获异常 | **必须** |
| 后端 | 不直接返回 Entity | **必须** |
| 后端 | 构造器注入 | **必须** |
| 后端 | SQL 参数绑定 | **必须** |
| 后端 | DTO 类每个字段必须有 Javadoc 注释 | **必须** |
| 后端 | 已定义状态常量时必须引用常量（如 `BorrowStatus.PENDING`；常量按业务域拆分，同一值可在多个域各有一个常量） | **必须** |
| 后端 | Entity 的 @Column/@JoinColumn/@UniqueConstraint 必须引用表字段常量类，禁止硬编码字符串 | **必须** |
| 后端 | 取值可穷举的字段必须定义常量类，Entity 默认值和 Service 比较/赋值必须引用常量 | **必须** |
| 后端 | Controller 类及每个端点方法必须有 Javadoc | **必须** |
| 后端 | Service 类及每个 public 方法必须有 Javadoc | **必须** |
| 后端 | Entity 类及每个字段必须有 Javadoc | **必须** |
| 后端 | 注释语言统一使用中文 | **必须** |
