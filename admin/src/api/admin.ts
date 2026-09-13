/**
 * 管理端 API 模块 — 封装 /api/admin/* 全部接口，严格 TypeScript 类型。
 */

import api, { get, post, put, del } from '../utils/api';
import type { AxiosResponse } from 'axios';

// ============================================================
// 通用类型
// ============================================================

/** 通用列表响应（后端 ListDTO 包装，一次性返回全部数据） */
export interface ListDTO<T> {
  content: T[];
}

// ============================================================
// 审核
// ============================================================

export interface AuditListParams {
  status?: string;
}

export interface AuditCounts {
  pending: number;
  approved: number;
  rejected: number;
  all: number;
}

export interface AuditUserBody {
  approved: boolean;
  reason?: string;
}

/** 审核列表中的用户条目 */
export interface AuditUserDTO {
  id: number;
  name: string;
  phone: string;
  userType: string;
  userRoom: string;
  authStatus: string;
  rejectReason?: string;
  docImages?: string[];
  auditorName?: string;
  createdAt: string;
}

export function getAudits(params: AuditListParams = {}): Promise<AxiosResponse> {
  return get('/api/admin/audits', { status: params.status });
}

export function getAuditCounts(): Promise<AxiosResponse> {
  return get('/api/admin/audits/counts');
}

export function auditUser(userId: number, body: AuditUserBody): Promise<AxiosResponse> {
  return put(`/api/admin/audits/${userId}`, body);
}

// ============================================================
// 内容管理
// ============================================================

export interface ContentListParams {
  status?: string;
  type?: 'idle' | 'help';
  /** 楼栋号（数值筛选） */
  building_no?: number;
  /** 单元号（数值筛选） */
  unit_no?: number;
  search?: string;
}

/** 对方信息（进行中 / 已完成时填充） */
export interface PeerInfo {
  peerName?: string;
  peerRoom?: string;
  peerRating?: number;
}

/** 违规信息 */
export interface ViolationInfo {
  delistReason?: string;
}

export interface ContentItemDTO {
  id: number;
  type: 'idle' | 'help';
  title: string;
  description: string;
  category: string;
  rawStatus: string;
  displayStatus: string;
  publisherName: string;
  publisherRoom: string;
  isProxy: boolean;
  isUrgent?: boolean;
  createdAt: string;
  // 时间线（已完成时填充）
  timeStart?: string;
  timeEnd?: string;
  applyAt?: string;
  approveAt?: string;
  completeAt?: string;
  // 图片（详情展示用）
  images?: string[];
  // 评价（已完成时填充）
  publisherRatingStars?: string;
  publisherRatingScore?: number;
  peerRatingStars?: string;
  peerRatingScore?: number;
  // 互借专属（详情展示用）
  maxDuration?: number;
  durationUnit?: string;
  /** 统一下架原因 */
  delistReason?: string;
  /** 审核员姓名 */
  reviewedByName?: string;
  /** 更新时间 */
  updatedAt?: string;
  /** 审核状态：green / yellow / red / reviewed，审核 tab 详情弹窗展示用 */
  moderationStatus?: string;
  /** 发布类型：LEND / WANTED / HELP */
  postType?: string;
  // 审批信息（待审批时填充）
  /** 审批人姓名 */
  approverName?: string;
  /** 申请人姓名（即发布者） */
  applicantName?: string;
}

export interface ContentOfflineBody {
  targetType: string;
  reasons: string[];
  customReason?: string;
  /** 是否来自审核 tab 的驳回操作，后端据此决定 moderationStatus 的最终值 */
  fromModeration?: boolean;
  /** 乐观锁：管理员打开弹窗时的 updatedAt，ISO 字符串，可选 */
  updatedAt?: string;
}

export interface ContentCounts {
  showing: number;
  pending: number;
  progressing: number;
  completed: number;
  violation: number;
  all: number;
}

export function getContentList(params: ContentListParams): Promise<AxiosResponse> {
  return get('/api/admin/content', params);
}

export function getContentCounts(params?: { status?: string }): Promise<AxiosResponse> {
  return get('/api/admin/content/counts', params as Record<string, unknown> | undefined);
}

// ============================================================
// AI 内容审核
// ============================================================

/** 审核列表查询参数 */
export interface ModerationListParams {
  status: string;
  moderationStatus?: string;
  moderatedBy?: string;
  type?: string;
  /** 楼栋号（数值筛选） */
  building_no?: number;
  /** 单元号（数值筛选） */
  unit_no?: number;
  search?: string;
}

/** 审核列表条目 DTO */
export interface ModerationItemDTO {
  id: number;
  /** 内容类型：idle / help */
  type: 'idle' | 'help';
  /** 发布类型：LEND / WANTED / HELP */
  postType: string;
  title: string;
  description: string;
  images?: string[];
  publisherName: string;
  publisherRoom: string;
  /** 数据库实际存储的原始状态值（如 online / offline），与后端 ContentItemDTO.rawStatus 对齐 */
  rawStatus: string;
  /** 审核状态：green / yellow / red / reviewed */
  moderationStatus: string;
  /** 审核员姓名，null 表示 AI 审核 */
  reviewedByName: string | null;
  /** 发布时间 */
  createdAt: string;
  /** 统一下架原因 */
  delistReason?: string;
  /** 更新时间 */
  updatedAt?: string;
  /** 最大借出时长（互借） */
  maxDuration?: number;
  /** 时长单位 */
  durationUnit?: string;
  /** 预计开始时间（互助） */
  timeStart?: string;
  /** 预计结束时间（互助） */
  timeEnd?: string;
}

/** AI 审核各状态计数 */
export interface ModerationCounts {
  /** AI 审核通过（绿色） */
  green: number;
  /** 待人工复核（黄色） */
  yellow: number;
  /** AI 审核驳回（红色） */
  red: number;
  /** 已人工复核 */
  reviewed: number;
}

/** 获取审核列表 */
export function getModerationList(params: ModerationListParams): Promise<AxiosResponse> {
  return get('/api/admin/content', params as unknown as Record<string, unknown>);
}

/** 获取 AI 内容审核各状态的数量统计 */
export function getModerationCounts(): Promise<AxiosResponse> {
  return get('/api/admin/content/counts', { status: 'moderation' });
}

/** 审核通过。updatedAt 可选，用于乐观锁版本检查 */
export function approveContent(id: number, type: 'idle' | 'help', updatedAt?: string): Promise<AxiosResponse> {
  const params: Record<string, unknown> = { type };
  if (updatedAt) params.updatedAt = updatedAt;
  return put(`/api/admin/content/${id}/approve`, null, params);
}

/** 违规下架（审核场景，body 字段与 ContentOfflineRequest 对齐） */
export interface ModerationOfflineBody {
  targetType: string;
  reasons: string[];
  customReason?: string;
  /** 是否来自审核 tab 的驳回操作，后端据此决定 moderationStatus 的最终值 */
  fromModeration?: boolean;
  /** 乐观锁：管理员打开弹窗时的 updatedAt，ISO 字符串，可选 */
  updatedAt?: string;
}

export function offlineModerationContent(id: number, body: ModerationOfflineBody): Promise<AxiosResponse> {
  return put(`/api/admin/content/${id}/offline`, body);
}

export function getContentDetail(id: number, type: 'idle' | 'help'): Promise<AxiosResponse> {
  return get(`/api/admin/content/${id}`, { type });
}

export function offlineContent(id: number, body: ContentOfflineBody): Promise<AxiosResponse> {
  return put(`/api/admin/content/${id}/offline`, body);
}

// ============================================================
// 代发
// ============================================================

export interface PublishIdleBody {
  /** 目标住户 ID（管理员代发时指定） */
  userId?: number | null;
  /** 是否为物业代发（管理员代发时设为 true） */
  isProxy?: boolean;
  /** 发布类型：LEND（闲置借出）或 WANTED（需求借入） */
  postType: string;
  /** 物品标题 */
  title: string;
  /** 物品描述（选填） */
  description?: string;
  /** 物品分类 */
  category: string;
  /** 物品成色：like-new / normal / worn */
  condition?: string;
  /** 参考价格（元） */
  price?: number;
  /** 物品图片 URL 的 JSON 数组字符串 */
  images?: string;
  /** 最大借出天数/小时数 */
  maxDuration?: number;
  /** 借出时长单位：day / hour */
  durationUnit?: string;
  /** 借出形式：self_pickup / both */
  pickupMethod?: string;
}

export interface PublishHelpBody {
  /** 目标住户 ID（管理员代发时指定） */
  userId?: number | null;
  /** 是否为物业代发（管理员代发时设为 true） */
  isProxy?: boolean;
  /** 求助标题 */
  title: string;
  /** 求助描述（选填） */
  description?: string;
  /** 求助分类 */
  category: string;
  /** 是否紧急 */
  isUrgent?: boolean;
  /** 预计开始时间（格式：yyyy-MM-dd HH:mm） */
  timeStart?: string;
  /** 预计结束时间（格式：yyyy-MM-dd HH:mm） */
  timeEnd?: string;
  /** 图片 URL 的 JSON 数组字符串 */
  images?: string;
}

export function publishIdle(body: PublishIdleBody): Promise<AxiosResponse> {
  return post('/api/idle-items', body);
}

export function publishHelp(body: PublishHelpBody): Promise<AxiosResponse> {
  return post('/api/help-requests', body);
}

// ============================================================
// 住户搜索
// ============================================================

export interface ResidentSearchParams {
  userType?: string;
  /** 楼栋号（数值筛选） */
  building_no?: number;
  /** 单元号（数值筛选） */
  unit_no?: number;
  keyword?: string;
}

export function searchResidents(params: ResidentSearchParams): Promise<AxiosResponse> {
  return get('/api/admin/residents/search', params);
}

// ============================================================
// 运营看板
// ============================================================

/** KPI 单项：key 为 idle(在线闲置)/help(在线求助)/pub(本月发布)/mau(本月活跃)，momChange 为较上月环比% */
export interface DashboardKpi {
  key: 'idle' | 'help' | 'pub' | 'mau';
  value: number;
  momChange: number;
}

/** 趋势图单段：labels 与 publish/completed 一一对应（completed 对应前端图表 series「完成互助」） */
export interface DashboardTrendData {
  labels: string[];
  publish: number[];
  completed: number[];
}

/** 趋势图三段（周/月/季） */
export interface DashboardTrends {
  week: DashboardTrendData;
  month: DashboardTrendData;
  quarter: DashboardTrendData;
}

/** 本月互助完成率：completed 已互助、removed 直接下架、rate 完成率% */
export interface DashboardCompletion {
  completed: number;
  removed: number;
  rate: number;
}

/** 损坏三态统计（damageType 分布） */
export interface DashboardDamage {
  normal: number;
  severe: number;
  broken: number;
}

/** 互助对象排行项：按住户聚合的互助总次数（闲置借入 + 技能接单合并） */
export interface DashboardRankingItem {
  name: string;
  count: number;
}

export interface DashboardDTO {
  kpis: DashboardKpi[];
  trends: DashboardTrends;
  completion: DashboardCompletion;
  damage: DashboardDamage;
  ranking: DashboardRankingItem[];
}

export function getDashboard(): Promise<AxiosResponse> {
  return get('/api/admin/dashboard');
}

// ============================================================
// 互助记录
// ============================================================

/** 互助记录列表查询参数 */
export interface RecordsListParams {
  type?: string;
}

/** 互助记录条目（后端 getRecords 返回的单条记录） */
export interface RecordItemDTO {
  id: number;
  type: 'borrow' | 'help';
  title: string;
  publisher: string;
  peer: string;
  content: string;
  room: string;
  /** 格式化时间 "yyyy-MM-dd HH:mm" */
  timeStart: string;
  timeEnd: string | null;
  status: string;
  createdAt: string;
  // 时间线（5 节点）
  publishedAt: string | null;
  applyAt: string | null;
  approveAt: string | null;
  rating1Label?: string | null;
  rating1Time?: string | null;
  rating2Label?: string | null;
  rating2Time?: string | null;
  // 借用详情（已中文化）
  lendDuration?: string;
  condBefore?: string | null;
  condAfter?: string | null;
  returnStatus?: string | null;
  damageType?: string | null;
  // 评分（1-5 数字）
  pubRatingScore?: number | null;
  pubComment?: string | null;
  peerRatingScore?: number | null;
  peerComment?: string | null;
}

export function getRecords(params: RecordsListParams = {}): Promise<AxiosResponse> {
  return get('/api/admin/records', { type: params.type ?? 'all' });
}

// ============================================================
// 小区数据
// ============================================================

export interface UnitData {
  id: number;
  /** 单元号（数值，展示拼 "x单元"） */
  unitNo: number;
}

export interface BuildingData {
  id: number;
  /** 楼栋号（数值，展示拼 "x栋"） */
  buildingNo: number;
  units: UnitData[];
}

export interface CommunityData {
  tenantName: string;
  buildings: BuildingData[];
}

export function getCommunity(): Promise<AxiosResponse> {
  return get('/api/admin/community');
}

export function getBuildings(): Promise<AxiosResponse> {
  return get('/api/admin/buildings');
}

export function getTenants(): Promise<AxiosResponse> {
  return get('/api/admin/tenants');
}

// ============================================================
// 管理员管理
// ============================================================

export interface CreateAdminBody {
  name: string;
  phone: string;
  password: string;
  /** 目标小区ID（super_admin 创建时必须指定） */
  tenantId: number;
  /** 管理员类型：admin 或 senior_admin */
  userType: string;
}

export function getAdmins(): Promise<AxiosResponse> {
  return get('/api/admin/admins');
}

export function createAdmin(body: CreateAdminBody): Promise<AxiosResponse> {
  return post('/api/admin/admins', body);
}

export function deleteAdmin(id: number): Promise<AxiosResponse> {
  return del(`/api/admin/admins/${id}`);
}

// ============================================================
// 个人设置
// ============================================================

export interface UpdateProfileBody {
  name: string;
}

export interface UpdatePasswordBody {
  oldPassword: string;
  newPassword: string;
}

export function updateProfile(body: UpdateProfileBody): Promise<AxiosResponse> {
  return put('/api/admin/profile', body);
}

export function updatePassword(body: UpdatePasswordBody): Promise<AxiosResponse> {
  return put('/api/admin/password', body);
}

// ============================================================
// 操作日志
// ============================================================

export interface OperationLogDTO {
  id: number;
  adminName: string;
  action: string;
  targetType: string;
  targetId: number;
  detail: string;
  createdAt: string;
}

export function getLogs(): Promise<AxiosResponse> {
  return get('/api/admin/logs');
}

// ============================================================
// 数据导出（原生 fetch，POST JSON body，文件下载不走 axios JSON 流）
// ============================================================

/** 导出请求参数 */
export interface ExportParams {
  /** 导出格式，固定 "xlsx" */
  format: 'xlsx';
  /** 筛选开始日期（yyyy-MM-dd），可选 */
  dateStart?: string;
  /** 筛选结束日期（yyyy-MM-dd），可选 */
  dateEnd?: string;
  /** 勾选的导出项目列表：residents / posts / borrows / removals / ratings */
  options: string[];
}

/** 导出日志条目 */
export interface ExportLogItem {
  /** 日志ID */
  id: number;
  /** 操作人姓名 */
  adminName: string;
  /** 导出时间 */
  createdAt: string;
  /** 导出格式 */
  exportFormat: string;
  /** 勾选项目（JSON数组字符串） */
  selectedOptions: string;
  /** 各 Sheet 记录数汇总描述 */
  countSummary: string;
  /** 文件名 */
  fileName: string;
}


/**
 * 执行数据导出，发送 POST 请求到后端生成多 Sheet Excel 文件。
 * @param params - 导出参数（options、日期范围、格式）
 * @returns 包含 Blob 和文件名的对象
 */
export async function exportData(params: ExportParams): Promise<{ blob: Blob; fileName: string }> {
  const token = localStorage.getItem('admin_token');
  const response = await fetch('/api/admin/exports', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${token}`,
    },
    body: JSON.stringify({
      options: params.options,
      dateStart: params.dateStart || null,
      dateEnd: params.dateEnd || null,
      format: params.format,
    }),
  });

  if (!response.ok) {
    throw new Error(`导出失败: HTTP ${response.status}`);
  }

  const blob = await response.blob();
  // 从 Content-Disposition 响应头提取后端生成的文件名（兼容 RFC 5987 filename*= 格式）
  const disposition = response.headers.get('Content-Disposition');
  const match = disposition?.match(/filename\*?=(?:UTF-8'')?(.+)/i);
  const fileName = match ? decodeURIComponent(match[1]) : `export.xlsx`;

  return { blob, fileName };
}

/**
 * 查询导出日志列表（分页）。
 * @param params - 分页参数
 * @returns AxiosResponse，data 为分页日志数据
 */
export function getExportLogs(): Promise<AxiosResponse> {
  return get('/api/admin/exports/logs');
}

/**
 * 导出导出日志为 Excel 文件，触发浏览器下载。
 */
export async function exportExportLogs(): Promise<void> {
  const token = localStorage.getItem('admin_token');
  const response = await fetch('/api/admin/exports/logs/export', {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!response.ok) {
    const err = await response.json().catch(() => ({ message: '导出失败' }));
    throw new Error(err.message || '导出失败');
  }
  const blob = await response.blob();
  const disposition = response.headers.get('Content-Disposition');
  const match = disposition?.match(/filename\*?=(?:UTF-8'')?(.+)/i);
  const fileName = match ? decodeURIComponent(match[1]) : '导出日志.xlsx';
  const url = window.URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = fileName;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  window.URL.revokeObjectURL(url);
}

/**
 * 导出操作日志为 Excel 文件，触发浏览器下载。
 */
export async function exportOperationLogs(): Promise<void> {
  const token = localStorage.getItem('admin_token');
  const response = await fetch('/api/admin/logs/export', {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!response.ok) {
    const err = await response.json().catch(() => ({ message: '导出失败' }));
    throw new Error(err.message || '导出失败');
  }
  const blob = await response.blob();
  const disposition = response.headers.get('Content-Disposition');
  const match = disposition?.match(/filename\*?=(?:UTF-8'')?(.+)/i);
  const fileName = match ? decodeURIComponent(match[1]) : '操作日志.xlsx';
  const url = window.URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = fileName;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  window.URL.revokeObjectURL(url);
}


// ============================================================
// 知识库（AI 助手「小邻」RAG 数据源）
// ============================================================

/** 知识条目分类（与后端 KnowledgeCategory 对齐） */
export const KNOWLEDGE_CATEGORY = {
  /** 规章制度 */
  RULES: 'rules',
  /** 服务手册 */
  SERVICE: 'service',
  /** 平台帮助 */
  HELP: 'help',
  /** 办事指南 */
  GUIDE: 'guide'
} as const;

/** 知识库当前支持上传的文件扩展名（TXT/Excel/CSV 暂时禁用，与后端对齐） */
export const KNOWLEDGE_FILE_EXTS = ['.md', '.pdf', '.docx'] as const;

/** 知识库源文档 DTO（前端实际使用的字段；后端不再传递 tenantId/tags/chunkCount/createdAt） */
export interface KnowledgeDocumentDTO {
  /** 源文档 ID */
  id: number;
  /** 分类：rules/service/help/guide */
  category: string;
  /** 原始文件名（含扩展名） */
  fileName: string;
  /** 文件类型：md/txt/pdf/docx/xlsx/csv */
  fileType: string;
  /** 展示来源名（默认文件名去扩展名，问答引用出处） */
  source?: string;
  /** 处理状态：parsing(解析中)/ready(就绪)/failed(失败可重试) */
  status: string;
  /** 失败原因 / 部分内容未处理警告 */
  errorMessage?: string;
  /** 更新时间 */
  updatedAt: string;
}

/** 文档列表查询参数（一次性返回全部，无分页） */
export interface KnowledgeDocumentListParams {
  /** 状态过滤：parsing/ready/failed */
  status?: string;
}

/** 上传知识文档（multipart：文件 + 分类 + 是否替换） */
export function importKnowledgeDocument(params: {
  /** 上传文件 */
  file: File;
  /** 分类：rules/service/help/guide */
  category: string;
  /** 同名文档是否替换（替换先删旧文档） */
  replace?: boolean;
}): Promise<AxiosResponse> {
  const form = new FormData();
  form.append('file', params.file);
  form.append('category', params.category);
  form.append('replace', String(!!params.replace));
  return api.post('/api/admin/knowledge/documents/import', form, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 60000
  });
}

/**
 * 批量上传知识文档 — 逐个复用单文件接口，返回成功数与会话失败文件名。
 *
 * @param files       待上传文件列表（已过滤不支持类型）
 * @param category    分类：rules/service/help/guide
 * @param replaceNames 需替换同名旧文档的文件名集合
 * @return 成功数 + 失败文件名列表（单文件失败不影响其余）
 */
export async function importKnowledgeDocuments(
  files: File[],
  category: string,
  replaceNames: Set<string>
): Promise<{ success: number; failed: string[] }> {
  const results = await Promise.allSettled(
    files.map((file) =>
      importKnowledgeDocument({ file, category, replace: replaceNames.has(file.name) })
    )
  );
  let success = 0;
  const failed: string[] = [];
  results.forEach((r, i) => {
    if (r.status === 'fulfilled') {
      success++;
    } else {
      failed.push(files[i].name);
    }
  });
  return { success, failed };
}

/** 文档列表（一次性返回全部） */
export function getKnowledgeDocuments(params: KnowledgeDocumentListParams = {}): Promise<AxiosResponse> {
  return get('/api/admin/knowledge/documents', {
    status: params.status
  });
}

/** 删除文档（清库全量：切片 + 文档行 + 落盘文件） */
export function deleteKnowledgeDocument(id: number): Promise<AxiosResponse> {
  return del(`/api/admin/knowledge/documents/${id}`);
}

/** 重试解析失败文档 */
export function retryKnowledgeDocument(id: number): Promise<AxiosResponse> {
  return post(`/api/admin/knowledge/documents/${id}/retry`);
}

// ============================================================
// 敏感词管理（仅 super_admin）
// ============================================================

/** 敏感词状态（与后端 SensitiveWordStatus 对齐） */
export const SENSITIVE_WORD_STATUS = {
  /** 启用 */
  ENABLED: 'ENABLED',
  /** 停用 */
  DISABLED: 'DISABLED',
} as const;

/** 敏感词列表查询参数（一次性返回全部，无分页） */
export interface SensitiveWordListParams {
  /** 状态过滤：ENABLED/DISABLED，缺省查全部 */
  status?: string;
}

/** 敏感词 DTO（与后端 SensitiveWordDTO 对齐） */
export interface SensitiveWordDTO {
  /** 敏感词 ID */
  id: number;
  /** 敏感词原文 */
  word: string;
  /** 状态：ENABLED(启用)/DISABLED(停用) */
  status: string;
  /** 创建时间 */
  createdAt: string;
  /** 更新时间 */
  updatedAt: string;
}

/** 新增/编辑敏感词请求体 */
export interface SensitiveWordBody {
  /** 敏感词原文（必填） */
  word: string;
  /** 状态：ENABLED(启用)/DISABLED(停用)，新增时缺省 ENABLED */
  status?: string;
}

/** 敏感词列表（一次性返回全部） */
export function getSensitiveWords(
  params: SensitiveWordListParams = {},
): Promise<AxiosResponse> {
  return get('/api/admin/sensitive-words', {
    status: params.status,
  });
}

/** 新增敏感词（词重复返回业务错误） */
export function createSensitiveWord(
  body: SensitiveWordBody,
): Promise<AxiosResponse> {
  return post('/api/admin/sensitive-words', body);
}

/** 编辑敏感词（word/status） */
export function updateSensitiveWord(
  id: number,
  body: SensitiveWordBody,
): Promise<AxiosResponse> {
  return put(`/api/admin/sensitive-words/${id}`, body);
}

/** 删除敏感词 */
export function deleteSensitiveWord(id: number): Promise<AxiosResponse> {
  return del(`/api/admin/sensitive-words/${id}`);
}
