<template>
  <AppLayout title="数据导出">
    <!-- 导出选项 -->
    <div class="panel">
      <div class="panel-header">
        <span class="panel-title">导出内容（可多选）</span>
      </div>
      <div style="padding: 32px">
        <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 12px">
          <el-checkbox v-model="exportOptions.residents"
            >住户清单（含认证状态、互助统计）</el-checkbox
          >
          <el-checkbox v-model="exportOptions.posts"
            >发布记录（闲置借出/需求借入/技能求助合并）</el-checkbox
          >
          <el-checkbox v-model="exportOptions.borrows"
            >互借记录 + 互助记录（闲置物品借用 & 技能求助交易）</el-checkbox
          >
          <el-checkbox v-model="exportOptions.removals"
            >内容下架记录（物业操作日志）</el-checkbox
          >
          <el-checkbox v-model="exportOptions.ratings">评分数据</el-checkbox>
        </div>
        <div class="dv-divider"></div>
        <div style="display: flex; gap: 24px; align-items: flex-end">
          <div>
            <div class="text-sm text-secondary mb-8">时间范围</div>
            <el-date-picker
              v-model="dateRange"
              type="daterange"
              range-separator="~"
              start-placeholder="开始日期"
              end-placeholder="结束日期"
              format="YYYY-MM-DD"
              value-format="YYYY-MM-DD"
            />
          </div>
          <div>
            <div class="text-sm text-secondary mb-8">导出格式</div>
            <span style="font-size: 14px; color: #333">Excel (.xlsx)</span>
          </div>
        </div>
        <div class="mt-16" style="display: flex; gap: 8px">
          <el-button type="primary" :loading="exporting" @click="confirmExport">
            <el-icon><Download /></el-icon> 立即导出
          </el-button>
        </div>
      </div>
    </div>

    <!-- 导出日志（固定高度，约6条，内部滚动） -->
    <div class="panel">
      <div class="panel-header">
        <span class="panel-title">导出日志</span>
        <el-button :loading="exportingLogs" @click="handleExportLogs"
          >导出日志</el-button
        >
      </div>
      <div style="overflow: hidden; border-radius: 0 0 16px 16px">
        <div style="max-height: 298px; overflow-y: auto">
          <el-table
            :data="exportLogs"
            style="width: 100%"
            v-loading="loadingLogs"
          >
            <el-table-column label="时间" width="175">
              <template #default="{ row }">
                {{ fmtTime(row.createdAt) }}
              </template>
            </el-table-column>
            <el-table-column prop="adminName" label="操作人" width="130" />
            <el-table-column label="导出项目" width="250">
              <template #default="{ row }">
                {{ row.selectedOptions }}
              </template>
            </el-table-column>
            <el-table-column label="状态" width="80">
              <template #default>
                <span class="badge badge-success">成功</span>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </div>
    </div>
  </AppLayout>
</template>

<!--
  ExportView.vue — 数据导出管理

  功能：选择导出项（住户/帖子/借用/帮助/下架/评价）、设置日期范围筛选、导出 Excel 文件、查看导出历史。
  权限：需管理员 / 超级管理员登录。
-->
<script setup lang="ts">
import { ref, reactive, onMounted } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { Download } from "@element-plus/icons-vue";

import { exportData, getExportLogs, exportExportLogs } from "@/api/admin";
import type { ExportLogItem } from "@/api/admin";
import AppLayout from "@/layouts/AppLayout.vue";

/** 导出数据的时间范围（可选，不选则导出全部） */
const dateRange = ref<string[] | null>(null);

/** 导出内容选项（5项可多选，默认勾选前三项） */
const exportOptions = reactive({
  residents: false,
  posts: false,
  borrows: false,
  removals: false,
  ratings: false,
});

/** 是否正在执行导出 */
const exporting = ref<boolean>(false);

/** 历史导出日志 */
const exportLogs = ref<ExportLogItem[]>([]);

/** 日志加载状态 */
const loadingLogs = ref<boolean>(false);

/** 导出日志按钮 loading */
const exportingLogs = ref<boolean>(false);

/**
 * 根据选中的日期范围生成确认弹窗的描述文本。
 */
function buildDateDescription(): string {
  const start = dateRange.value?.[0];
  const end = dateRange.value?.[1];

  if (start && end) {
    return `${start} 至 ${end}`;
  }
  if (start && !end) {
    return `${start}（包括）至至今`;
  }
  if (!start && end) {
    return `截止至 ${end}`;
  }
  return "全部";
}

/**
 * 弹出确认对话框。用户确认后执行实际导出。
 */
async function confirmExport(): Promise<void> {
  const selected = (Object.entries(exportOptions) as [string, boolean][])
    .filter(([, v]) => v)
    .map(([k]) => k);
  if (!selected.length) {
    ElMessage.warning("请至少选择一项导出内容");
    return;
  }

  const dateDesc = buildDateDescription();
  let message: string;
  if (dateDesc === "全部") {
    message = `已选择 ${selected.length} 项导出内容，确认全部导出？`;
  } else {
    message = `已选择 ${selected.length} 项导出内容，时间范围：${dateDesc}，确认导出？`;
  }

  try {
    await ElMessageBox.confirm(message, "确认导出", {
      confirmButtonText: "导出",
      cancelButtonText: "取消",
      type: "info",
    });
    await doExport();
  } catch {
    // 用户取消
  }
}

/**
 * 执行数据导出。
 * 调用后端导出接口，触发浏览器下载，并根据返回数据量提示用户。
 */
async function doExport(): Promise<void> {
  const selected = (Object.entries(exportOptions) as [string, boolean][])
    .filter(([, v]) => v)
    .map(([k]) => k);
  if (!selected.length) {
    ElMessage.warning("请至少选择一项导出内容");
    return;
  }

  exporting.value = true;

  try {
    const { blob, fileName } = await exportData({
      format: "xlsx",
      dateStart: dateRange.value?.[0] || undefined,
      dateEnd: dateRange.value?.[1] || undefined,
      options: selected,
    });

    // 刷新日志检查最新导出是否所有勾选项数据均为 0
    await loadExportLogs();
    const latest = exportLogs.value[0];
    if (latest && !latest.countSummary) {
      ElMessage.warning("没有查询到数据，换个时间范围试试呢");
    } else {
      const downloadUrl = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = downloadUrl;
      link.download = fileName;
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);
      URL.revokeObjectURL(downloadUrl);
      ElMessage.success("导出完成，文件已开始下载");
    }
  } catch {
    ElMessage.error("导出失败，请稍后重试");
  } finally {
    exporting.value = false;
  }
}

/** 加载导出日志 */
async function loadExportLogs(): Promise<void> {
  loadingLogs.value = true;
  try {
    const res = await getExportLogs();
    exportLogs.value = res.data?.data?.content || [];
  } catch (err: any) {
    console.error("加载导出日志失败:", err);
  } finally {
    loadingLogs.value = false;
  }
}

/** 格式化导出时间为 YYYY-MM-DD HH:mm */
function fmtTime(ts?: string): string {
  if (!ts) return "";
  const d = new Date(ts);
  if (Number.isNaN(d.getTime())) return ts.substring(0, 16);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")} ${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
}

/** 导出导出日志为 Excel 文件 */
async function handleExportLogs(): Promise<void> {
  exportingLogs.value = true;
  try {
    await exportExportLogs();
    ElMessage.success("导出日志已导出");
  } catch (err: any) {
    ElMessage.error(err.message || "导出失败");
  } finally {
    exportingLogs.value = false;
  }
}

onMounted(() => {
  loadExportLogs();
});
</script>
