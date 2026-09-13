<template>
  <AppLayout title="敏感词管理" main-class="panel-fill">
        <div class="unified-panel">
          <!-- 筛选行：状态过滤 + 查询 + 新增入口 -->
          <div class="filter-row">
            <el-select
              class="filter-select"
              v-model="filterStatus"
              placeholder="状态"
              style="width: 130px"
              clearable
            >
              <el-option label="启用" :value="STATUS.ENABLED" />
              <el-option label="停用" :value="STATUS.DISABLED" />
            </el-select>
            <el-button type="primary" @click="handleSearch">查询</el-button>
            <div class="filter-spacer" />
            <el-button type="success" @click="openCreate">+ 新增敏感词</el-button>
          </div>

          <!-- 条数 + 列表 -->
          <div class="list-count-row">
            <span style="flex: 1"></span>
            <span class="text-sm text-secondary">共 {{ total }} 条</span>
          </div>
          <div class="table-wrap">
            <el-table :data="list" v-loading="loading" stripe>
              <el-table-column prop="word" label="敏感词" min-width="160" show-overflow-tooltip />
              <el-table-column label="状态" width="110" align="center">
                <template #default="{ row }">
                  <el-tag size="small" :type="statusTagType(row.status)">
                    {{ statusLabel(row.status) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="创建时间" width="170">
                <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
              </el-table-column>
              <el-table-column label="更新时间" width="170">
                <template #default="{ row }">{{ formatTime(row.updatedAt) }}</template>
              </el-table-column>
              <el-table-column label="操作" width="180" align="center">
                <template #default="{ row }">
                  <el-switch
                    v-model="row.status"
                    :active-value="STATUS.ENABLED"
                    :inactive-value="STATUS.DISABLED"
                    :loading="toggleLoadingId === row.id"
                    @change="handleToggle(row)"
                  />
                  <el-button size="small" link type="primary" @click="openEdit(row)">编辑</el-button>
                  <el-button size="small" link type="danger" @click="handleDelete(row)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>

        </div>
  </AppLayout>

  <!-- 新增/编辑敏感词对话框 -->
  <el-dialog
    v-model="dialogVisible"
    :title="editingId === null ? '新增敏感词' : '编辑敏感词'"
    width="480px"
    @closed="resetForm"
  >
    <el-form ref="formRef" :model="form" :rules="formRules" label-width="90px">
      <el-form-item label="敏感词" prop="word">
        <el-input
          v-model="form.word"
          placeholder="请输入敏感词（如：傻逼、fuck）"
          maxlength="100"
          show-word-limit
          @keyup.enter="handleSubmit"
        />
      </el-form-item>
      <el-form-item label="状态" prop="status">
        <el-radio-group v-model="form.status">
          <el-radio :value="STATUS.ENABLED">启用</el-radio>
          <el-radio :value="STATUS.DISABLED">停用</el-radio>
        </el-radio-group>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialogVisible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="handleSubmit">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
/**
 * 敏感词管理视图 — AI 对话输入前置过滤词库管理（仅 super_admin）。
 *
 * 主列表服务端分页，支持按状态过滤；新增/编辑对话框维护词与启停状态，
 * 行内开关一键启停（等价于编辑状态），删除需二次确认。
 * 权限：仅 super_admin（后端每个端点首行校验，前端路由/菜单亦按角色收敛）。
 */
import { ref, reactive, computed, onMounted } from "vue";
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from "element-plus";
import AppLayout from "@/layouts/AppLayout.vue";
import {
  SENSITIVE_WORD_STATUS,
  getSensitiveWords,
  createSensitiveWord,
  updateSensitiveWord,
  deleteSensitiveWord,
  type SensitiveWordDTO,
} from "@/api/admin";

/** 状态常量（与后端 SensitiveWordStatus 对齐） */
const STATUS = SENSITIVE_WORD_STATUS;

// ==================== 列表 ====================

/** 表格加载状态 */
const loading = ref<boolean>(false);
/** 敏感词列表（一次性加载全部） */
const list = ref<SensitiveWordDTO[]>([]);
/** 总条数（一次性加载后取列表长度） */
const total = computed<number>(() => list.value.length);
/** 状态过滤值（空表示全部） */
const filterStatus = ref<string>("");

/** 加载敏感词列表（一次性全部 + 状态过滤） */
async function loadList(): Promise<void> {
  loading.value = true;
  try {
    const res = await getSensitiveWords({
      status: filterStatus.value || undefined,
    });
    list.value = res.data?.data?.content ?? [];
  } catch {
    ElMessage.error("加载敏感词列表失败");
  } finally {
    loading.value = false;
  }
}

/** 查询按钮：重新加载 */
function handleSearch(): void {
  loadList();
}

/** 状态对应的 el-tag 颜色 */
function statusTagType(status: string): "success" | "info" {
  return status === STATUS.ENABLED ? "success" : "info";
}

/** 状态中文标签 */
function statusLabel(status: string): string {
  return status === STATUS.ENABLED ? "启用" : "停用";
}

/** 格式化时间为 YYYY-MM-DD HH:mm（后端返回可能含秒或 T 分隔） */
function formatTime(value?: string): string {
  if (!value) return "";
  return value.replace("T", " ").slice(0, 16);
}

// ==================== 新增 / 编辑 ====================

/** 新增/编辑对话框可见性 */
const dialogVisible = ref<boolean>(false);
/** 表单提交中状态 */
const submitting = ref<boolean>(false);
/** 编辑中的敏感词 ID（null 表示新增） */
const editingId = ref<number | null>(null);
/** 表单引用（触发校验用） */
const formRef = ref<FormInstance | null>(null);
/** 新增/编辑表单数据 */
const form = reactive<{ word: string; status: string }>({
  word: "",
  status: STATUS.ENABLED,
});

/** 表单校验规则（敏感词必填） */
const formRules: FormRules = {
  word: [{ required: true, message: "请输入敏感词", trigger: "blur" }],
};

/** 打开新增对话框 */
function openCreate(): void {
  editingId.value = null;
  dialogVisible.value = true;
}

/** 打开编辑对话框（回填词与状态） */
function openEdit(row: SensitiveWordDTO): void {
  editingId.value = row.id;
  form.word = row.word;
  form.status = row.status;
  dialogVisible.value = true;
}

/** 对话框关闭后重置表单（下次打开时保持干净） */
function resetForm(): void {
  form.word = "";
  form.status = STATUS.ENABLED;
  formRef.value?.clearValidate();
}

/** 提交新增/编辑 */
async function handleSubmit(): Promise<void> {
  if (!formRef.value) return;
  const valid = await formRef.value.validate().catch(() => false);
  if (!valid) return;
  submitting.value = true;
  try {
    if (editingId.value === null) {
      await createSensitiveWord({ word: form.word.trim(), status: form.status });
      ElMessage.success("已新增敏感词");
    } else {
      await updateSensitiveWord(editingId.value, {
        word: form.word.trim(),
        status: form.status,
      });
      ElMessage.success("已保存修改");
    }
    dialogVisible.value = false;
    loadList();
  } catch (err: any) {
    ElMessage.error(err?.response?.data?.message || "保存失败，请稍后重试");
  } finally {
    submitting.value = false;
  }
}

// ==================== 启停切换 / 删除 ====================

/** 行内开关切换中（loading 的敏感词 ID） */
const toggleLoadingId = ref<number | null>(null);

/**
 * 行内开关：一键启停（v-model 已把新状态写入 row.status，此处提交后端；
 * 失败时重新拉取列表，界面回滚为后端实际状态）。
 */
async function handleToggle(row: SensitiveWordDTO): Promise<void> {
  toggleLoadingId.value = row.id;
  try {
    await updateSensitiveWord(row.id, {
      word: row.word,
      status: row.status,
    });
    ElMessage.success(row.status === STATUS.ENABLED ? "已启用" : "已停用");
    loadList();
  } catch (err: any) {
    ElMessage.error(err?.response?.data?.message || "操作失败，请稍后重试");
    loadList();
  } finally {
    toggleLoadingId.value = null;
  }
}

/** 删除敏感词（二次确认） */
async function handleDelete(row: SensitiveWordDTO): Promise<void> {
  const confirm = await ElMessageBox.confirm(
    `删除敏感词「${row.word}」后立即生效，不可恢复。继续？`,
    "删除确认",
    {
      confirmButtonText: "删除",
      cancelButtonText: "取消",
      type: "warning",
    },
  ).catch(() => null);
  if (!confirm) return;
  try {
    await deleteSensitiveWord(row.id);
    ElMessage.success("已删除");
    loadList();
  } catch {
    ElMessage.error("删除失败，请稍后重试");
  }
}

onMounted(() => {
  loadList();
});
</script>

<style scoped>
/* 敏感词管理页样式：沿用知识库/互助记录页的 unified-panel 布局，页面不出现浏览器滚动条 */
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

.filter-spacer {
  flex: 1;
}

/* 条数行（对齐互助记录页「共 N 条」：右内边距与筛选行一致，避免贴边） */
.list-count-row {
  display: flex;
  align-items: center;
  padding: 0 20px 8px;
  flex-shrink: 0;
}

/* 表格撑满剩余高度，表格内部滚动 */
.table-wrap {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}

/* 分页行：底部固定，不随表格滚动 */
.pagination-row {
  display: flex;
  justify-content: flex-end;
  padding: 12px 4px 0;
  flex-shrink: 0;
}
</style>
