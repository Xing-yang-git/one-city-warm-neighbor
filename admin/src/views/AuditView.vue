<template>
  <AppLayout title="住户管理" :main-class="LIST_PAGE_MAIN_CLASS">
    <div class="unified-panel">
      <!-- 标签页 -->
      <div class="tab">
        <div class="segment-row">
          <button
            v-for="t in tabs"
            :key="t.key"
            class="segment-btn"
            :class="{ active: activeTab === t.key }"
            @click="switchTab(t.key)"
          >
            {{ t.label }}
            <span
              v-if="t.count !== undefined"
              class="count"
              style="margin-left: 4px; opacity: 0.5; font-size: 11px"
              >{{ t.count }}</span
            >
          </button>
        </div>
      </div>

      <!-- 筛选 -->
      <div class="filter-row">
        <el-select
          class="filter-select"
          v-model="filterType"
          placeholder="住户类型"
          style="width: 110px"
        >
          <el-option label="全部" value="" />
          <el-option label="业主" value="业主" />
          <el-option label="租客" value="租客" />
        </el-select>
        <el-select
          class="filter-select"
          v-model="filterBuilding"
          placeholder="楼栋"
          style="width: 110px"
          @change="filterUnit = ''"
        >
          <el-option label="全部" value="" />
          <el-option
            v-for="b in communityStore.buildingOptions"
            :key="b.id"
            :label="b.buildingNo + '栋'"
            :value="b.buildingNo"
          />
        </el-select>
        <el-select
          class="filter-select"
          v-model="filterUnit"
          placeholder="单元"
          style="width: 100px"
          :disabled="!filterBuilding"
        >
          <el-option label="全部" value="" />
          <el-option
            v-for="u in filterUnitOptions"
            :key="u.id"
            :label="u.unitNo + '单元'"
            :value="u.unitNo"
          />
        </el-select>
        <el-input
          class="search-box"
          v-model="search"
          placeholder="搜索后按回车或点击空白处..."
          style="width: 190px"
          clearable
        />
        <button
          v-if="activeTab === 'pending'"
          type="button"
          class="btn-kong"
          style="margin-left: 8px"
          :disabled="!selectedRows.length"
          @click="openApproval"
        >
          审批
        </button>
        <el-button
          v-else-if="activeTab !== 'pending'"
          type="primary"
          size="default"
          style="margin-left: 8px"
          :disabled="!selectedRows.length"
          @click="openResidentDetail"
          >详细</el-button
        >
        <span style="flex: 1"></span>
      </div>

      <!-- 面板主体（填充面板高度，内部滚动）-->
      <div class="uf-body">
        <!-- 加载中 -->
        <div
          v-if="loading"
          class="panel-empty"
          style="padding: 48px; text-align: center; color: var(--text-tertiary)"
        >
          加载中...
        </div>

        <!-- 错误提示 -->
        <div
          v-else-if="
            error &&
            !filteredPending.length &&
            !filteredPassed.length &&
            !filteredRejected.length
          "
          class="panel-empty"
          style="padding: 48px; text-align: center; color: var(--red)"
        >
          加载失败：{{ error }}
          <el-button size="default" style="margin-left: 8px" @click="loadData"
            >重试</el-button
          >
        </div>

        <!-- 表格（仅在非加载且无错误时显示）-->
        <template v-else>
          <!-- 待审核 -->
          <div v-show="activeTab === 'pending'" class="panel">
            <el-table
              v-if="filteredPending.length"
              :data="filteredPending"
              height="100%"
              style="width: 100%"
              @selection-change="handleSelectionChange"
            >
              <el-table-column type="selection" width="50" />
              <el-table-column prop="name" label="申请人" min-width="80" />
              <el-table-column label="房号" min-width="100">
                <template #default="{ row }">
                  <el-link
                    type="primary"
                    :underline="false"
                    @click="openAuditByRoom(row)"
                    >{{ row.room }}</el-link
                  >
                </template>
              </el-table-column>
              <el-table-column label="住户类型" align="center" min-width="60">
                <template #default="{ row }">
                  <el-tag>{{ typeLabel(row.type) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column
                prop="time"
                label="提交时间"
                min-width="120"
                align="center"
              />
              <el-table-column label="状态" align="center" min-width="60">
                <template #default="{ row }">
                  <span class="doc-status-red">{{ row.status || "正常" }}</span>
                </template>
              </el-table-column>
            </el-table>
            <div v-if="!filteredPending.length && !loading" class="panel-empty">
              暂无待审核住户
            </div>
          </div>

          <!-- 全部住户 -->
          <div v-show="activeTab === 'all'" class="panel">
            <el-table
              v-if="filteredPassed.length"
              :data="filteredPassed"
              height="100%"
              style="width: 100%"
              @selection-change="handleSelectionChange"
            >
              <el-table-column type="selection" width="50" />
              <el-table-column prop="name" label="住户" min-width="80" />
              <el-table-column label="房号" min-width="100">
                <template #default="{ row }">
                  <el-link
                    type="primary"
                    :underline="false"
                    @click="openAuditByRoom(row)"
                    >{{ row.room }}</el-link
                  >
                </template>
              </el-table-column>
              <el-table-column label="类型" align="center" min-width="60">
                <template #default="{ row }">
                  <el-tag>{{ typeLabel(row.type) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column
                prop="time"
                label="审核时间"
                align="center"
                min-width="120"
              />
              <el-table-column prop="auditor" label="审核人" min-width="60" />
            </el-table>
            <div v-if="!filteredPassed.length && !loading" class="panel-empty">
              暂无住户
            </div>
          </div>

          <!-- 已驳回 -->
          <div v-show="activeTab === 'rejected'" class="panel">
            <el-table
              v-if="filteredRejected.length"
              :data="filteredRejected"
              height="100%"
              style="width: 100%"
              @selection-change="handleSelectionChange"
            >
              <el-table-column type="selection" width="50" />
              <el-table-column prop="name" label="申请人" min-width="80" />
              <el-table-column label="房号" min-width="100">
                <template #default="{ row }">
                  <el-link
                    type="primary"
                    :underline="false"
                    @click="openAuditByRoom(row)"
                    >{{ row.room }}</el-link
                  >
                </template>
              </el-table-column>
              <el-table-column label="住户类型" align="center" min-width="60">
                <template #default="{ row }">
                  <el-tag>{{ typeLabel(row.type) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column
                prop="time"
                label="驳回时间"
                min-width="120"
                align="center"
              />
              <el-table-column prop="rejectReason" label="驳回原因" />
            </el-table>
            <div
              v-if="!filteredRejected.length && !loading"
              class="panel-empty"
            >
              暂无已驳回记录
            </div>
          </div>
        </template>
      </div>
    </div>

    <!-- 审核弹窗 -->
    <el-dialog
      v-model="auditVisible"
      :title="`审核住户：${auditTarget?.name || ''}`"
      width="480px"
      @closed="onAuditClosed"
    >
      <div v-if="auditTarget">
        <div class="detail-row">
          <span class="dl">小区</span><span class="dv">翠湖花园</span>
        </div>
        <div class="detail-row">
          <span class="dl">房号</span
          ><span class="dv">{{ auditTarget.room }}</span>
        </div>
        <div class="detail-row">
          <span class="dl">手机</span
          ><span class="dv">{{ auditTarget.phone || "未填写" }}</span>
        </div>
        <div class="detail-row">
          <span class="dl">姓名</span
          ><span class="dv">{{ auditTarget.name }}</span>
        </div>
        <div class="detail-row">
          <span class="dl">类型</span
          ><span class="dv">
            <el-tag>{{ typeLabel(auditTarget.type) }}</el-tag>
          </span>
        </div>
        <div class="detail-row" style="align-items: flex-start">
          <span class="dl">{{ docTypeLabel(auditTarget.type) }}</span>
          <span class="dv">
            <span
              v-if="auditTarget.docImages && auditTarget.docImages.length"
              style="display: flex; gap: 8px; flex-wrap: wrap"
            >
              <el-image
                v-for="(img, i) in auditTarget.docImages"
                :key="i"
                :src="img"
                :preview-src-list="auditTarget.docImages"
                :initial-index="i"
                fit="cover"
                preview-teleported
                hide-on-click-modal
                style="
                  width: 80px;
                  height: 80px;
                  border-radius: 8px;
                  cursor: pointer;
                "
              />
            </span>
            <template v-else>无</template>
          </span>
        </div>
        <div class="dv-divider"></div>

        <!-- 驳回原因（点「驳回」时填写） -->
        <div style="margin-top: 4px">
          <div style="margin-bottom: 6px; font-weight: 600">
            驳回原因（驳回时填写，可多选）：
          </div>
          <el-checkbox-group
            v-model="rejectReasons"
            style="
              display: flex;
              flex-direction: column;
              align-items: flex-start;
              gap: 4px;
            "
            @change="rejectError = ''"
          >
            <el-checkbox label="信息不完整" />
            <el-checkbox label="房号与证件不符" />
            <el-checkbox label="非本小区住户" />
          </el-checkbox-group>
          <el-input
            v-model="rejectCustomReason"
            type="textarea"
            class="field-input"
            :rows="3"
            resize="none"
            placeholder="自定义原因..."
            style="margin-top: 8px"
            @input="rejectError = ''"
          />
          <p v-if="rejectError" class="field-error">{{ rejectError }}</p>
        </div>
      </div>
      <template #footer>
        <div style="display: flex; align-items: center; gap: 8px">
          <!-- 切换仅在多条时显示（对齐内容页统一架构：单条打开→隐藏，批量打开→显示） -->
          <template v-if="batchList.length > 1">
            <el-button
              :icon="ArrowLeft"
              circle
              :disabled="batchPos === 0"
              @click="batchGo(-1)"
            />
            <span class="text-sm text-secondary"
              >{{ batchPos + 1 }} / {{ batchList.length }}</span
            >
            <el-button
              :icon="ArrowRight"
              circle
              :disabled="batchPos === batchList.length - 1"
              @click="batchGo(1)"
            />
          </template>
          <span style="flex: 1"></span>
          <el-button @click="auditVisible = false">取消</el-button>
          <el-button
            type="primary"
            :disabled="hasRejectContent"
            :loading="submitting"
            @click="submitAudit(true)"
            >同意</el-button
          >
          <el-button
            type="danger"
            :loading="submitting"
            @click="submitAudit(false)"
            >驳回</el-button
          >
        </div>
      </template>
    </el-dialog>

    <!-- 住户详情弹窗（已通过 / 已驳回） -->
    <el-dialog v-model="detailVisible" title="住户详情" width="480px">
      <div v-if="detailRow">
        <div class="detail-row">
          <span class="dl">姓名</span
          ><span class="dv">{{ detailRow.name || "—" }}</span>
        </div>
        <div class="detail-row">
          <span class="dl">房号</span
          ><span class="dv">{{ detailRow.room || "—" }}</span>
        </div>
        <div class="detail-row">
          <span class="dl">小区</span><span class="dv">翠湖花园</span>
        </div>
        <div class="detail-row">
          <span class="dl">住户类型</span
          ><span class="dv">
            <el-tag>{{ typeLabel(detailRow.type) }}</el-tag>
          </span>
        </div>
        <div class="detail-row">
          <span class="dl">手机</span
          ><span class="dv">{{ detailRow.phone || "未填写" }}</span>
        </div>
        <div class="detail-row">
          <span class="dl">审核结果</span>
          <span class="dv">
            <span class="doc-status-red">{{
              detailTab === "all" ? "已通过" : "已驳回"
            }}</span>
          </span>
        </div>
        <div class="detail-row">
          <span class="dl">{{
            detailTab === "all" ? "审核时间" : "驳回时间"
          }}</span
          ><span class="dv">{{ detailRow.time || "—" }}</span>
        </div>
        <div v-if="detailTab === 'passed'" class="detail-row">
          <span class="dl">审核人</span
          ><span class="dv">{{ detailRow.auditor || "—" }}</span>
        </div>
        <div v-else class="detail-row" style="align-items: flex-start">
          <span class="dl">驳回原因</span
          ><span class="dv" style="white-space: pre-wrap">{{
            detailRow.rejectReason || "—"
          }}</span>
        </div>
        <div class="detail-row" style="align-items: flex-start">
          <span class="dl">{{ docTypeLabel(detailRow.type) }}</span>
          <span class="dv">
            <span
              v-if="detailRow.docImages && detailRow.docImages.length"
              style="display: flex; gap: 8px; flex-wrap: wrap"
            >
              <el-image
                v-for="(img, i) in detailRow.docImages"
                :key="i"
                :src="img"
                :preview-src-list="detailRow.docImages"
                :initial-index="i"
                fit="cover"
                preview-teleported
                hide-on-click-modal
                style="
                  width: 80px;
                  height: 80px;
                  border-radius: 8px;
                  cursor: pointer;
                "
              />
            </span>
            <template v-else>无</template>
          </span>
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
          <el-button @click="detailVisible = false">关闭</el-button>
        </div>
      </template>
    </el-dialog>
  </AppLayout>
</template>

<!--
  AuditView.vue — 住户认证审核管理

  功能：按状态标签页筛选（全部/待审核/已通过/已驳回）、批量审核通过/驳回、查看住户详情。
  状态流转：pending → approved / rejected。
  权限：需管理员 / 超级管理员登录。
-->
<script setup lang="ts">
import { ref, reactive, computed, onMounted } from "vue";
import { ElMessage } from "element-plus";
import { ArrowLeft, ArrowRight } from "@element-plus/icons-vue";

import { useCommunityStore } from "@/stores/community";
import {
  getAudits,
  getAuditCounts,
  auditUser,
  type AuditUserDTO,
  type AuditCounts,
} from "@/api/admin";
import { AUTH_STATUS, USER_TYPE } from "@/utils/constants";
import AppLayout from "@/layouts/AppLayout.vue";
import { LIST_PAGE_MAIN_CLASS } from "@/layouts/main-classes";
import type { AxiosError } from "axios";

// --- 本地类型 ---

/** 审核列表行数据结构 */
interface AuditRow {
  id: number;
  name: string;
  room: string;
  type: string;
  time: string;
  phone: string;
  status: string;
  docImages: string[];
  rejectReason: string;
  auditor: string;
}

/** 各标签页的计数 */
interface TabCounts {
  pending: number;
  approved: number;
  rejected: number;
}

const communityStore = useCommunityStore();

// --- 纯工具函数 ---

/** 后端 userType → 中文显示名映射 */
const TYPE_MAP: Record<string, string> = {
  业主: "业主",
  租客: "租客",
  admin: "管理员",
  super_admin: "超级管理员",
};

/** 将后端用户类型转为中文显示 */
function mapType(userType: string): string {
  return TYPE_MAP[userType] || userType || "—";
}

/** 住户类型中文标签（owner→业主 / tenant→租客），表格与弹窗展示统一使用 */
function typeLabel(type: string): string {
  if (type === USER_TYPE.OWNER) return "业主";
  if (type === USER_TYPE.TENANT) return "租客";
  return type || "—";
}

/** 认证材料名称（tenant→租房合同 / owner→房产证） */
function docTypeLabel(type: string): string {
  if (type === USER_TYPE.TENANT) return "租房合同";
  if (type === USER_TYPE.OWNER) return "房产证";
  return "认证资料";
}

/** 格式化 ISO 日期为可读格式 */
function formatTime(dateStr: string): string {
  if (!dateStr) return "";
  const d = new Date(dateStr);
  if (Number.isNaN(d.getTime())) return dateStr;
  const pad = (n: number): string => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/**
 * 计算审核是否超时（超过 24 小时未处理）。
 * @param dateStr - 创建时间的 ISO 字符串
 * @returns 超时状态文案（正常 / 超时）
 */
function computeOvertime(dateStr: string): string {
  if (!dateStr) return "正常";
  const d = new Date(dateStr);
  if (Number.isNaN(d.getTime())) return "正常";
  const hours = (Date.now() - d.getTime()) / 3600000;
  return hours > 24 ? "超时" : "正常";
}

// --- 标签页 ---
/** 当前激活的标签页 key */
const activeTab = ref<string>("pending");
/** 各标签页的计数（来自后端统计接口） */
const tabCounts = reactive<TabCounts>({ pending: 0, approved: 0, rejected: 0 });

const tabs = computed(() => [
  { key: "pending", label: "待审核", count: tabCounts.pending },
  { key: "rejected", label: "已驳回", count: tabCounts.rejected },
  { key: "all", label: "全部住户", count: tabCounts.approved },
]);

// --- 数据 ---
/** 待审核列表 */
const pendingData = ref<AuditRow[]>([]);
/** 已通过列表 */
const passedData = ref<AuditRow[]>([]);
/** 已驳回列表 */
const rejectedData = ref<AuditRow[]>([]);
/** 列表加载状态 */
const loading = ref(false);
/** 审核操作提交中 */
const submitting = ref(false);
/** 加载错误信息 */
const error = ref("");

/** 加载当前标签页的数据列表 */
async function loadData(): Promise<void> {
  loading.value = true;
  error.value = "";
  try {
    let status: string | undefined;
    if (activeTab.value === "pending") status = AUTH_STATUS.PENDING;
    else if (activeTab.value === "all") status = AUTH_STATUS.APPROVED;
    else if (activeTab.value === "rejected") status = AUTH_STATUS.REJECTED;

    const res = await getAudits({ status });
    const page = res.data?.data;
    const list: AuditRow[] = (page?.content || []).map((u: AuditUserDTO) => {
      return {
        id: u.id,
        name: u.name || "",
        room: u.userRoom || "",
        type: mapType(u.userType),
        time: formatTime(u.createdAt),
        phone: u.phone || "",
        status: computeOvertime(u.createdAt),
        docImages: u.docImages || [],
        rejectReason: u.rejectReason || "",
        auditor: (u as any).auditor || u.auditorName || "",
      };
    });

    if (activeTab.value === "pending") pendingData.value = list;
    else if (activeTab.value === "all") passedData.value = list;
    else if (activeTab.value === "rejected") rejectedData.value = list;
  } catch (e) {
    const err = e as AxiosError<{ message?: string }> | Error;
    error.value =
      "response" in err
        ? err.response?.data?.message || "加载失败"
        : err.message || "加载失败";
    ElMessage.error(error.value);
  } finally {
    loading.value = false;
  }
}

/** 加载各标签页计数 */
async function loadCounts(): Promise<void> {
  try {
    const res = await getAuditCounts();
    const counts: AuditCounts | undefined = res.data?.data;
    if (counts) {
      tabCounts.pending = counts.pending || 0;
      tabCounts.approved = counts.approved || 0;
      tabCounts.rejected = counts.rejected || 0;
    }
  } catch {
    /* 计数加载失败不影响主要功能 */
  }
}

onMounted(() => {
  communityStore.fetchCommunityData();
  loadData();
  loadCounts();
});

// 切换标签页 → 重新加载数据与计数
function switchTab(key: string): void {
  activeTab.value = key;
  selectedRows.value = [];
  loadData();
  loadCounts();
}

// --- 筛选 ---
/** 筛选条件：住户类型 */
const filterType = ref("");
/** 筛选条件：楼栋 */
const filterBuilding = ref<number | "">("");
/** 筛选条件：单元 */
const filterUnit = ref<number | "">("");
/** 搜索关键词 */
const search = ref("");
/** 勾选的行 */
const selectedRows = ref<AuditRow[]>([]);

// 根据所选楼栋计算单元筛选选项
const filterUnitOptions = computed(() => {
  if (!filterBuilding.value) return [];
  const buildingId = communityStore.getBuildingId(filterBuilding.value);
  return buildingId ? communityStore.getUnits(buildingId) : [];
});

function matchUnit(room: string, unit: number): boolean {
  if (!room || !unit) return false;
  return room.includes(unit + "单元");
}

function matchFilter(item: AuditRow): boolean {
  if (filterType.value && typeLabel(item.type) !== filterType.value)
    return false;
  if (filterBuilding.value && !item.room.startsWith(filterBuilding.value + "栋"))
    return false;
  if (filterUnit.value && !matchUnit(item.room, filterUnit.value)) return false;
  if (search.value) {
    const s = search.value.trim();
    if (!item.name.includes(s) && !item.room.includes(s)) return false;
  }
  return true;
}

const filteredPending = computed(() => pendingData.value.filter(matchFilter));
const filteredPassed = computed(() => passedData.value.filter(matchFilter));
const filteredRejected = computed(() => rejectedData.value.filter(matchFilter));

function handleSelectionChange(rows: AuditRow[]): void {
  selectedRows.value = rows;
}

// --- 住户详情（已通过 / 已驳回 tab 的「详细」）---
const detailVisible = ref(false);
const detailList = ref<AuditRow[]>([]);
const detailPos = ref(0);
const detailTab = ref<string>("passed");
const detailRow = computed<AuditRow | null>(
  () => detailList.value[detailPos.value] || null,
);

function openResidentDetail(): void {
  if (!selectedRows.value.length) {
    ElMessage.warning("请先勾选住户");
    return;
  }
  detailList.value = [...selectedRows.value];
  detailPos.value = 0;
  detailTab.value = activeTab.value;
  detailVisible.value = true;
}

function detailGo(delta: number): void {
  const next = detailPos.value + delta;
  if (next < 0 || next >= detailList.value.length) return;
  detailPos.value = next;
}

// --- Approval ---
async function openApproval(): Promise<void> {
  if (!selectedRows.value.length) {
    ElMessage.warning("请先勾选申请人");
    return;
  }
  batchList.value = [...selectedRows.value];
  batchPos.value = 0;
  batchMode.value = true;
  setAuditTarget(batchList.value[0]);
  auditVisible.value = true;
}

/** 点击房号打开审批弹窗（单行审批，不带批量切换） */
function openAuditByRoom(row: AuditRow): void {
  batchList.value = [row];
  batchPos.value = 0;
  batchMode.value = false;
  setAuditTarget(row);
  auditVisible.value = true;
}

function batchGo(delta: number): void {
  const next = batchPos.value + delta;
  if (next < 0 || next >= batchList.value.length) return;
  batchPos.value = next;
  setAuditTarget(batchList.value[next]);
}

// --- 审核弹窗 ---
const auditVisible = ref(false);
const auditTarget = ref<AuditRow | null>(null);
const rejectReasons = ref<string[]>([]);
const rejectCustomReason = ref("");
const rejectError = ref("");

const hasRejectContent = computed(() => {
  return (
    rejectReasons.value.length > 0 || rejectCustomReason.value.trim().length > 0
  );
});

const batchMode = ref(false);
const batchList = ref<AuditRow[]>([]);
const batchPos = ref(0);
const auditActed = ref(false);

function setAuditTarget(row: AuditRow): void {
  auditTarget.value = row;
  rejectReasons.value = [];
  rejectCustomReason.value = "";
  rejectError.value = "";
}

async function submitAudit(approved: boolean): Promise<void> {
  if (!approved) {
    const hasCustom = rejectCustomReason.value.trim().length > 0;
    if (!rejectReasons.value.length && !hasCustom) {
      rejectError.value = "请至少选择一项或填写有效的驳回原因";
      return;
    }
  }
  rejectError.value = "";

  const reasons = [...rejectReasons.value];
  const customText = rejectCustomReason.value.trim();
  if (customText) reasons.push(customText);
  const reason: string | null = approved
    ? null
    : reasons.join("；") || "未说明";

  submitting.value = true;
  try {
    await auditUser(auditTarget.value!.id, {
      approved,
      reason: reason ?? undefined,
    });
    ElMessage.success(approved ? "审核通过" : "已驳回");
    auditActed.value = true;
    if (batchMode.value && batchPos.value < batchList.value.length - 1) {
      batchGo(1);
    } else {
      auditVisible.value = false;
    }
  } catch (e) {
    const err = e as AxiosError<{ message?: string }>;
    ElMessage.error(err.response?.data?.message || "操作失败");
  } finally {
    submitting.value = false;
  }
}

function onAuditClosed(): void {
  batchMode.value = false;
  batchList.value = [];
  batchPos.value = 0;
  if (auditActed.value) {
    auditActed.value = false;
    selectedRows.value = [];
    loadData();
    loadCounts();
  }
}
</script>

<style scoped>
/* 状态列深红文字 — 对齐知识库页状态列的纯文字样式，配色改为深红 */
.doc-status-red {
  color: #c0392b;
  font-weight: 500;
}
</style>
