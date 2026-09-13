<template>
  <AppLayout title="知识库管理" :main-class="LIST_PAGE_MAIN_CLASS">
        <div class="unified-panel">
          <!-- 筛选行（条数同行显示，底部不留间距） -->
          <div class="filter-row" style="padding-bottom: 0">
            <el-select
              class="filter-select"
              v-model="filterCategory"
              placeholder="分类"
              style="width: 130px"
              clearable
            >
              <el-option
                v-for="opt in categoryOptions"
                :key="opt.value"
                :label="opt.label"
                :value="opt.value"
              />
            </el-select>
            <el-select
              class="filter-select"
              v-model="filterStatus"
              placeholder="状态"
              style="width: 130px"
              clearable
            >
              <el-option label="解析中" value="parsing" />
              <el-option label="就绪" value="ready" />
              <el-option label="失败" value="failed" />
            </el-select>
            <el-input
              class="search-box"
              v-model="filterKeyword"
              placeholder="搜索文件名 / 来源"
              style="width: 210px"
              clearable
              @keyup.enter="handleSearch"
              @clear="handleSearch"
            />
            <el-button
              type="primary"
              :disabled="!selectedDocs.length"
              @click="openDetailFromSelection"
              >详细</el-button
            >
            <el-button type="success" @click="openUpload">上传文档</el-button>
            <span style="flex: 1"></span>
            <span class="text-sm text-secondary"
              >共 {{ filteredDocs.length }} 条</span
            >
          </div>

          <!-- 列表（一次性获取 + 列表内滚动，撑满剩余高度） -->
          <!-- 表格（填充面板剩余高度，内部滚动 — 与互助记录页同结构） -->
          <div class="uf-body">
            <div class="panel">
            <el-table
              :data="filteredDocs"
              v-loading="loading"
              stripe
              height="100%"
              @selection-change="handleSelectionChange"
            >
              <el-table-column type="selection" width="50" />
              <el-table-column
                label="文件名"
                min-width="220"
                show-overflow-tooltip
              >
                <template #default="{ row }">
                  <el-link
                    type="primary"
                    :underline="false"
                    @click="openDetail(row)"
                  >
                    {{ row.fileName }}
                  </el-link>
                </template>
              </el-table-column>
              <el-table-column
                prop="fileType"
                label="类型"
                width="120"
                align="center"
              />
              <el-table-column label="分类" width="120" align="center">
                <template #default="{ row }">
                  <el-tag size="small" type="primary">
                    {{ categoryLabel(row.category) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="120" align="center">
                <template #default="{ row }">
                  <span class="doc-status" :class="statusClass(row.status)">{{ docStatusLabel(row.status) }}</span>
                  <el-tooltip
                    v-if="row.errorMessage"
                    :content="row.errorMessage"
                    placement="top"
                  >
                    <el-icon class="status-warn"><Warning /></el-icon>
                  </el-tooltip>
                </template>
              </el-table-column>
              <el-table-column label="更新时间" width="220">
                <template #default="{ row }">{{
                  formatTime(row.updatedAt)
                }}</template>
              </el-table-column>
            </el-table>
            </div>
          </div>
        </div>
  </AppLayout>

  <!-- 文档详情对话框 -->
  <el-dialog v-model="detailVisible" title="文档详情" width="520px">
    <div v-if="detailDoc" class="detail-body">
      <div class="detail-row">
        <span class="detail-label">文件</span>
        <span class="detail-value">{{ detailDoc.fileName }}</span>
      </div>
      <div class="detail-row">
        <span class="detail-label">分类</span>
        <span class="detail-value">
          <el-tag size="small" type="primary">
            {{ categoryLabel(detailDoc.category) }}
          </el-tag>
        </span>
      </div>
      <div class="detail-row">
        <span class="detail-label">状态</span>
        <span class="detail-value">
          <span class="doc-status">{{ docStatusLabel(detailDoc.status) }}</span>
          <el-tooltip
            v-if="detailDoc.errorMessage"
            :content="detailDoc.errorMessage"
            placement="top"
          >
            <el-icon class="status-warn"><Warning /></el-icon>
          </el-tooltip>
        </span>
      </div>
      <div class="detail-row">
        <span class="detail-label">更新时间</span>
        <span class="detail-value">{{ formatTime(detailDoc.updatedAt) }}</span>
      </div>
    </div>
    <template #footer>
      <div style="display: flex; align-items: center; gap: 8px">
        <!-- 切换仅在多条时显示（对齐内容页统一架构：链接点击仅1条→隐藏，详细按钮多条→显示） -->
        <template v-if="detailList.length > 1">
          <el-button
            :icon="ArrowLeft"
            circle
            :disabled="detailPos === 0"
            @click="detailGo(-1)"
          />
          <span class="text-sm text-secondary"
            >{{ detailPos + 1 }} / {{ detailList.length }}</span
          >
          <el-button
            :icon="ArrowRight"
            circle
            :disabled="detailPos === detailList.length - 1"
            @click="detailGo(1)"
          />
        </template>
        <span style="flex: 1"></span>
        <el-button type="warning" @click="handleDocReupload(detailDoc!)"
          >重新上传</el-button
        >
        <el-button
          v-if="detailDoc && detailDoc.status === DOC_STATUS.FAILED"
          type="success"
          @click="handleDocRetry(detailDoc)"
          >重试</el-button
        >
        <el-button type="danger" @click="handleDocDelete(detailDoc!)"
          >删除</el-button
        >
        <el-button @click="detailVisible = false">关闭</el-button>
      </div>
    </template>
  </el-dialog>

  <!-- 上传文档对话框 -->
  <el-dialog
    v-model="uploadVisible"
    title="上传知识文档"
    width="520px"
    @closed="resetUpload"
  >
    <el-form label-width="90px">
      <el-form-item label="文件" required>
        <el-button
          type="primary"
          plain
          :loading="uploading"
          @click="triggerFileSelect"
        >
          {{ uploadFiles.length ? `已选 ${uploadFiles.length} 个文件` : "选择文件" }}
        </el-button>
        <div v-if="uploadFiles.length" class="upload-file-list">
          <div
            v-for="(f, i) in uploadFiles"
            :key="f.name + i"
            class="upload-file-item"
          >
            <el-icon><Document /></el-icon>
            <span class="upload-file-name" :title="f.name">{{ f.name }}</span>
            <el-button text size="small" @click="removeUploadFile(i)">移除</el-button>
          </div>
        </div>
        <div class="upload-hint">
          支持 md / pdf / docx，可多选；不支持的格式会被自动忽略
        </div>
      </el-form-item>
      <el-form-item label="分类" required>
        <el-select v-model="uploadCategory" class="control-select" style="width: 100%">
          <el-option
            v-for="opt in categoryOptions"
            :key="opt.value"
            :label="opt.label"
            :value="opt.value"
          />
        </el-select>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="uploadVisible = false">取消</el-button>
      <el-button type="primary" :loading="uploading" @click="doUpload"
        >开始上传</el-button
      >
    </template>
  </el-dialog>

  <!-- 隐藏文件选择框（上传 / 重新上传共用） -->
  <input
    ref="fileInput"
    type="file"
    accept=".md,.pdf,.docx"
    multiple
    style="display: none"
    @change="onUploadFileChange"
  />
</template>

<script setup lang="ts">
/**
 * 知识库管理视图 — AI 助手「小邻」RAG 数据源（上传文档）管理。
 *
 * 主列表展示上传的知识文档（整篇管理）：一次性获取全量、列表内滚动、按文件名/分类/状态过滤、
 * 整篇删除（级联清理全部切片）、失败重试、重新上传替换、解析状态轮询；
 * 不暴露向量概念，管理员仅上传 / 删除整份文档。
 * 权限：ROLE_ADMIN / ROLE_SUPER_ADMIN（走既有 /api/admin/** 鉴权）。
 */
import { ref, computed, onMounted, onUnmounted } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { ArrowLeft, ArrowRight } from "@element-plus/icons-vue";
import AppLayout from "@/layouts/AppLayout.vue";
import { LIST_PAGE_MAIN_CLASS } from "@/layouts/main-classes";
import {
  KNOWLEDGE_CATEGORY,
  KNOWLEDGE_FILE_EXTS,
  importKnowledgeDocuments,
  getKnowledgeDocuments,
  deleteKnowledgeDocument,
  retryKnowledgeDocument,
  type KnowledgeDocumentDTO,
} from "@/api/admin";

/** 文档处理状态（与后端 DocumentStatus 对齐） */
const DOC_STATUS = {
  /** 解析中 */
  PARSING: "parsing",
  /** 就绪 */
  READY: "ready",
  /** 失败 */
  FAILED: "failed",
} as const;

// ==================== 文档列表 ====================

/** 表格加载状态 */
const loading = ref<boolean>(false);
/** 文档列表（一次性获取全量，前端过滤） */
const docs = ref<KnowledgeDocumentDTO[]>([]);
/** 分类过滤值 */
const filterCategory = ref<string>("");
/** 状态过滤值（parsing/ready/failed） */
const filterStatus = ref<string>("");
/** 文件名/来源关键词过滤 */
const filterKeyword = ref<string>("");

/** 是否轮询解析状态 */
const docPolling = ref<boolean>(false);
/** 轮询定时器 */
let docPollTimer: number | null = null;

/** 分类下拉选项（从常量派生；平台帮助为系统内置、禁止上传，仅保留物业资料分类） */
const categoryOptions = Object.entries(KNOWLEDGE_CATEGORY)
  .filter(([, value]) => value !== KNOWLEDGE_CATEGORY.HELP)
  .map(([, value]) => ({
    label: categoryLabel(value),
    value,
  }));

/** 分类中文标签映射 */
function categoryLabel(value: string): string {
  const map: Record<string, string> = {
    rules: "规章制度",
    service: "服务手册",
    help: "平台帮助",
    guide: "办事指南",
  };
  return map[value] ?? value;
}

/** 文档状态中文标签 */
function docStatusLabel(status: string): string {
  if (status === DOC_STATUS.PARSING) return "解析中";
  if (status === DOC_STATUS.READY) return "就绪";
  if (status === DOC_STATUS.FAILED) return "失败";
  return status;
}

/** 文档状态颜色类：解析中深蓝、就绪绿、失败深红（颜色走 token，见 .status-* 样式，不硬编码） */
function statusClass(status: string): string {
  if (status === DOC_STATUS.READY) return "status-ready";
  if (status === DOC_STATUS.FAILED) return "status-failed";
  return "status-parsing";
}

/** 按分类/状态/关键词前端过滤后的文档列表（条数显示与此一致） */
const filteredDocs = computed<KnowledgeDocumentDTO[]>(() => {
  const kw = filterKeyword.value.trim().toLowerCase();
  return docs.value.filter((d) => {
    if (filterCategory.value && d.category !== filterCategory.value)
      return false;
    if (filterStatus.value && d.status !== filterStatus.value) return false;
    if (
      kw &&
      !(d.fileName ?? "").toLowerCase().includes(kw) &&
      !(d.source ?? "").toLowerCase().includes(kw)
    )
      return false;
    return true;
  });
});

/** 一次性加载全部文档（无分页，列表内滚动） */
async function loadDocs(): Promise<void> {
  loading.value = true;
  try {
    const res = await getKnowledgeDocuments();
    docs.value = res.data?.data?.content ?? [];
    // 没有解析中文档则停止轮询
    if (!docs.value.some((d) => d.status === DOC_STATUS.PARSING)) {
      stopPolling();
    }
  } catch {
    ElMessage.error("加载文档列表失败");
  } finally {
    loading.value = false;
  }
}

/** 查询按钮：重新加载全量（前端过滤即时生效） */
function handleSearch(): void {
  loadDocs();
}

/** 勾选的文档列表（「详细」按钮用，参照互助记录页） */
const selectedDocs = ref<KnowledgeDocumentDTO[]>([]);

/** 勾选变化 */
function handleSelectionChange(rows: KnowledgeDocumentDTO[]): void {
  selectedDocs.value = rows;
}

/** 详情弹窗可见性 */
const detailVisible = ref<boolean>(false);
/** 详情弹窗待切换的文档列表（勾选集合或过滤列表的快照） */
const detailList = ref<KnowledgeDocumentDTO[]>([]);
/** 详情弹窗当前文档在列表中的下标 */
const detailPos = ref<number>(0);
/** 详情弹窗当前展示的文档（由列表与下标派生） */
const detailDoc = computed<KnowledgeDocumentDTO | null>(
  () => detailList.value[detailPos.value] ?? null,
);

/** 从勾选记录打开详情弹窗（勾选集合作为切换列表，无勾选时提示） */
function openDetailFromSelection(): void {
  if (!selectedDocs.value.length) {
    ElMessage.warning("请先勾选文档");
    return;
  }
  detailList.value = [...selectedDocs.value];
  detailPos.value = 0;
  detailVisible.value = true;
}

/** 点击文件名链接打开详情弹窗 — detailList 仅当前 1 条（统一架构：链接点击仅 1 条，详细按钮多条） */
function openDetail(row: KnowledgeDocumentDTO): void {
  detailList.value = [row];
  detailPos.value = 0;
  detailVisible.value = true;
}

/**
 * 详情弹窗左右切换文档。
 * @param delta - 切换偏移（-1 上一个，1 下一个）
 */
function detailGo(delta: number): void {
  const next = detailPos.value + delta;
  if (next < 0 || next >= detailList.value.length) return;
  detailPos.value = next;
}

/** 格式化时间为 YYYY-MM-DD HH:mm（后端返回可能含秒或 T 分隔） */
function formatTime(value?: string): string {
  if (!value) return "";
  return value.replace("T", " ").slice(0, 16);
}

/** 开始轮询（每 3 秒刷新，直到无解析中文档） */
function startPolling(): void {
  stopPolling();
  docPolling.value = true;
  docPollTimer = window.setInterval(() => {
    loadDocs();
  }, 3000);
}

/** 停止轮询 */
function stopPolling(): void {
  docPolling.value = false;
  if (docPollTimer !== null) {
    clearInterval(docPollTimer);
    docPollTimer = null;
  }
}

// ==================== 文档操作 ====================

/** 删除文档（二次确认，整篇级联清理全部切片） */
async function handleDocDelete(row: KnowledgeDocumentDTO): Promise<void> {
  const confirm = await ElMessageBox.confirm(
    `删除文档《${row.fileName}》将删除其全部知识分块，不可恢复。继续？`,
    "删除确认",
    {
      confirmButtonText: "删除",
      cancelButtonText: "取消",
      type: "warning",
    },
  ).catch(() => null);
  if (!confirm) return;
  try {
    await deleteKnowledgeDocument(row.id);
    ElMessage.success("已删除");
    detailVisible.value = false;
    loadDocs();
  } catch {
    ElMessage.error("删除失败，请稍后重试");
  }
}

/** 重试解析失败文档 */
async function handleDocRetry(row: KnowledgeDocumentDTO): Promise<void> {
  try {
    await retryKnowledgeDocument(row.id);
    ElMessage.success("已重新提交解析");
    loadDocs();
    startPolling();
  } catch {
    ElMessage.error("重试失败，请稍后重试");
  }
}

// ==================== 上传 / 重新上传 ====================

/** 上传对话框可见性 */
const uploadVisible = ref<boolean>(false);
/** 隐藏文件选择框引用 */
const fileInput = ref<HTMLInputElement | null>(null);
/** 待上传文件列表（多选，已过滤不支持类型） */
const uploadFiles = ref<File[]>([]);
/** 上传分类 */
const uploadCategory = ref<string>(KNOWLEDGE_CATEGORY.RULES);
/** 上传中状态 */
const uploading = ref<boolean>(false);
/** 重新上传目标文档（非空表示替换某文档） */
const replacingDoc = ref<KnowledgeDocumentDTO | null>(null);

/** 打开上传对话框 */
function openUpload(): void {
  resetUpload();
  uploadVisible.value = true;
}

/** 重置上传表单 */
function resetUpload(): void {
  uploadFiles.value = [];
  uploadCategory.value = KNOWLEDGE_CATEGORY.RULES;
  replacingDoc.value = null;
  if (fileInput.value) fileInput.value.value = "";
}

/** 触发文件选择 */
function triggerFileSelect(): void {
  fileInput.value?.click();
}

/** 判断文件扩展名是否为知识库支持类型 */
function isSupportedFile(file: File): boolean {
  const name = file.name.toLowerCase();
  return KNOWLEDGE_FILE_EXTS.some((ext) => name.endsWith(ext));
}

/** 移除已选中的某个文件 */
function removeUploadFile(index: number): void {
  uploadFiles.value.splice(index, 1);
}

/** 文件选择变化（上传 / 重新上传共用）：过滤不支持类型，仅保留支持文件 */
function onUploadFileChange(e: Event): void {
  const input = e.target as HTMLInputElement;
  // 先复制文件列表为数组快照，再清空 input——FileList 是 input 的实时视图，
  // 若先清空再读取，length 会变 0 导致文件名丢失（无法显示已选文件）
  const all = Array.from(input.files || []);
  input.value = ""; // 清空以支持重复选择同一批文件
  if (all.length === 0) return;
  const supported = all.filter(isSupportedFile);
  const unsupported = all.filter((f) => !isSupportedFile(f));
  if (unsupported.length > 0) {
    ElMessage.warning(
      `文件类型不支持上传，已忽略：${unsupported.map((f) => f.name).join("、")}`
    );
  }
  uploadFiles.value = supported;
  if (replacingDoc.value && supported.length > 0) {
    // 重新上传：带分类预填，直接替换
    doUpload();
  }
}

/** 文档行的重新上传：记录目标文档后触发文件选择 */
function handleDocReupload(row: KnowledgeDocumentDTO): void {
  replacingDoc.value = row;
  uploadCategory.value = row.category;
  triggerFileSelect();
}

/** 执行上传（多文件；含批次去重、同名替换确认、单文件失败隔离） */
async function doUpload(): Promise<void> {
  if (uploadFiles.value.length === 0) {
    ElMessage.warning("请先选择支持的文件");
    return;
  }
  uploading.value = true;
  try {
    let files: File[];
    const replaceNames = new Set<string>();
    if (replacingDoc.value) {
      // 重新上传：仅替换目标文档，取第一个支持的文件
      files = uploadFiles.value.slice(0, 1);
      if (files[0]) {
        replaceNames.add(files[0].name);
      }
    } else {
      // 批次内重复文件名去重（保留第一个）
      const seen = new Set<string>();
      const unique: File[] = [];
      for (const f of uploadFiles.value) {
        if (seen.has(f.name)) continue;
        seen.add(f.name);
        unique.push(f);
      }
      const droppedDups = uploadFiles.value.length - unique.length;
      if (droppedDups > 0) {
        ElMessage.warning(`批次内存在重复文件名，已忽略 ${droppedDups} 个重复项`);
      }
      files = unique;
      // 与现有文档同名 → 一次弹窗列出全部同名文件确认替换
      const sameNames = files
        .filter((f) => docs.value.some((d) => d.fileName === f.name))
        .map((f) => f.name);
      if (sameNames.length > 0) {
        const confirm = await ElMessageBox.confirm(
          `检测到同名文档已存在（${sameNames.join("、")}），继续上传将删除旧文档全部知识分块并替换，是否继续？`,
          "替换确认",
          {
            confirmButtonText: "确认替换",
            cancelButtonText: "取消",
            type: "warning",
          },
        ).catch(() => null);
        if (!confirm) return;
        sameNames.forEach((n) => replaceNames.add(n));
      }
    }
    const { success, failed } = await importKnowledgeDocuments(
      files,
      uploadCategory.value,
      replaceNames,
    );
    if (failed.length === 0) {
      ElMessage.success(`已受理 ${success} 个文件，开始解析`);
    } else {
      ElMessage.warning(`成功 ${success} 个，失败 ${failed.length} 个：${failed.join("、")}`);
    }
    uploadVisible.value = false;
    detailVisible.value = false;
    resetUpload();
    loadDocs();
    startPolling();
  } catch (err: any) {
    ElMessage.error(
      err?.response?.data?.message || "上传失败，请检查文件类型与大小",
    );
  } finally {
    uploading.value = false;
  }
}

onMounted(() => {
  loadDocs();
});

onUnmounted(() => {
  stopPolling();
});
</script>

<style scoped>
/* 知识库管理页样式：文档列表一次性加载 + 列表内滚动，页面不出现浏览器滚动条 */
.unified-panel {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.filter-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
  flex-wrap: wrap;
  flex-shrink: 0;
}

/* 状态警告图标 */
.status-warn {
  margin-left: 4px;
  color: var(--orange);
  vertical-align: middle;
}

/* 列表状态列文字 — 按状态着色（解析中/失败深红、就绪绿），颜色走设计 token 不硬编码 */
.doc-status {
  font-weight: 500;
}

/* 解析中：深蓝（处理中，走主强调色 token） */
.status-parsing {
  color: var(--accent);
}

/* 就绪：绿 */
.status-ready {
  color: var(--green);
}

/* 失败：深红 */
.status-failed {
  color: var(--red);
}

/* 文档详情弹窗 */
.detail-body {
  padding: 4px 0;
}

.detail-row {
  display: flex;
  align-items: flex-start;
  padding: 8px 0;
  border-bottom: 0.5px solid var(--border-soft);
}

.detail-row:last-child {
  border-bottom: none;
}

.detail-label {
  width: 72px;
  flex-shrink: 0;
  color: var(--text-tertiary);
  font-size: 13px;
}

.detail-value {
  color: var(--text);
  font-size: 13px;
  word-break: break-all;
}

/* 上传提示 */
.upload-hint {
  margin-top: 6px;
  font-size: 12px;
  color: var(--text-tertiary);
}

/* 多选文件列表 */
.upload-file-list {
  /* 独占一行：已选文件列表换行到按钮下方，不与「选择文件」按钮同行 */
  width: 100%;
  margin-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.upload-file-item {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--text);
}

.upload-file-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

</style>
