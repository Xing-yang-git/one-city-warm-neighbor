<template>
  <AppLayout title="互助记录" :main-class="LIST_PAGE_MAIN_CLASS">
    <div class="unified-panel">
      <!-- 筛选 — 第1行：仅日期选择器 -->
      <div class="filter-row" style="width: 100%; padding-bottom: 2px">
        <div style="width: 40%">
          <el-date-picker
            class="filter-select date-picker-no-arrow"
            v-model="filterDateRange"
            type="daterange"
            range-separator="~"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            format="YYYY-MM-DD"
            value-format="YYYY-MM-DD"
          />
        </div>
      </div>

      <!-- 筛选 — 第2行：其他筛选 + 操作 -->
      <div class="filter-row">
        <el-select
          class="filter-select"
          v-model="filterType"
          placeholder="类型"
          style="width: 110px"
        >
          <el-option label="全部" value="" />
          <el-option label="物品互借" value="borrow" />
          <el-option label="技能互助" value="help" />
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
          placeholder="搜索住户名称或房号..."
          style="width: 210px"
          clearable
        />
        <el-button
          type="primary"
          size="default"
          style="margin-left: 8px"
          :disabled="!selectedRows.length"
          @click="openDetailFromSelection"
          >详细</el-button
        >
        <span style="flex: 1"></span>
        <span class="text-sm text-secondary"
          >共 {{ filteredRecords.length }} 条</span
        >
      </div>

      <!-- 表格（填充面板高度，内部滚动）-->
      <div class="uf-body">
        <div class="panel">
          <el-table
            :data="filteredRecords"
            height="100%"
            style="width: 100%"
            @selection-change="handleSelectionChange"
          >
            <el-table-column type="selection" width="50" />
            <el-table-column label="类型" align="center" width="100">
              <template #default="{ row }">
                <el-tag>{{ row.type === ACTIVITY_ROLE.BORROW ? "互借" : "互助" }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="publisher" label="发布者" />
            <el-table-column prop="peer" label="互助对象" />
            <el-table-column label="内容">
              <template #default="{ row }">
                <span class="content-link" @click="openDetailByRow(row)">{{
                  row.content
                }}</span>
              </template>
            </el-table-column>
            <el-table-column label="时间范围" width="300">
              <template #default="{ row }">
                {{ row.timeStart }} ~ {{ row.timeEnd }}
              </template>
            </el-table-column>
          </el-table>
        </div>
      </div>
    </div>

    <!-- 详情弹窗 -->
    <el-dialog
      v-model="detailVisible"
      title="互助记录详情"
      width="680px"
      top="10vh"
    >
      <div v-if="detailItem">
        <!-- 基本信息 -->
        <div class="detail-row">
          <span class="dl">内容</span
          ><span class="dv">{{ detailItem.content }}</span>
        </div>
        <div class="detail-row">
          <span class="dl">类型</span
          ><span class="dv">
            <el-tag>{{
              detailItem.type === ACTIVITY_ROLE.BORROW ? "互借" : "互助"
            }}</el-tag>
          </span>
        </div>
        <div class="detail-row">
          <span class="dl">小区</span><span class="dv">翠湖花园</span>
        </div>
        <!-- 互助：预计开始 / 预计结束（发布时选填，未填显示 --） -->
        <template v-if="detailItem.type === ACTIVITY_ROLE.HELP">
          <div class="detail-row">
            <span class="dl">预计开始</span
            ><span class="dv">{{ detailItem.timeStart || "--" }}</span>
          </div>
          <div class="detail-row">
            <span class="dl">预计结束</span
            ><span class="dv">{{ detailItem.timeEnd || "--" }}</span>
          </div>
        </template>
        <!-- 互借：借出时长 -->
        <template v-if="detailItem.type === ACTIVITY_ROLE.BORROW">
          <div class="detail-row">
            <span class="dl">借出时长</span
            ><span class="dv">{{ detailItem.lendDuration || "--" }}</span>
          </div>
        </template>

        <div class="dv-divider"></div>

        <!-- 互助双方 + 时间线（左右布局） -->
        <div style="display: flex; gap: 20px">
          <!-- 左侧：互助双方信息 -->
          <div style="flex: 1; min-width: 0">
            <p class="text-sm text-secondary" style="margin-bottom: 8px">
              互助双方
            </p>
            <template v-if="detailItem.type === ACTIVITY_ROLE.BORROW">
              <div class="detail-row">
                <span class="dl">借出方</span>
                <span class="dv"
                  >{{ detailItem.publisher }}
                  <span
                    v-if="detailItem.pubRatingScore"
                    class="rating-tag"
                    style="margin-left: 6px"
                  >
                    <span class="stars">{{
                      toStars(detailItem.pubRatingScore)
                    }}</span>
                    <span class="rlabel">获评</span>
                  </span>
                </span>
              </div>
              <div class="detail-row" style="align-items: flex-start">
                <span class="dl">互助感想</span>
                <span class="dv" style="white-space: pre-wrap">{{
                  detailItem.pubComment || "无"
                }}</span>
              </div>
              <div class="detail-row">
                <span class="dl">借入方</span>
                <span class="dv"
                  >{{ detailItem.peer }}
                  <span
                    v-if="detailItem.peerRatingScore"
                    class="rating-tag"
                    style="margin-left: 6px"
                  >
                    <span class="stars">{{
                      toStars(detailItem.peerRatingScore)
                    }}</span>
                    <span class="rlabel">获评</span>
                  </span>
                </span>
              </div>
              <div class="detail-row" style="align-items: flex-start">
                <span class="dl">互助感想</span>
                <span class="dv" style="white-space: pre-wrap">{{
                  detailItem.peerComment || "无"
                }}</span>
              </div>
            </template>
            <template v-else>
              <div class="detail-row">
                <span class="dl">求助方</span>
                <span class="dv"
                  >{{ detailItem.publisher }}
                  <span
                    v-if="detailItem.pubRatingScore"
                    class="rating-tag"
                    style="margin-left: 6px"
                  >
                    <span class="stars">{{
                      toStars(detailItem.pubRatingScore)
                    }}</span>
                    <span class="rlabel">获评</span>
                  </span>
                </span>
              </div>
              <div class="detail-row" style="align-items: flex-start">
                <span class="dl">互助感想</span>
                <span class="dv" style="white-space: pre-wrap">{{
                  detailItem.pubComment || "无"
                }}</span>
              </div>
              <div class="detail-row">
                <span class="dl">相助方</span>
                <span class="dv"
                  >{{ detailItem.peer }}
                  <span
                    v-if="detailItem.peerRatingScore"
                    class="rating-tag"
                    style="margin-left: 6px"
                  >
                    <span class="stars">{{
                      toStars(detailItem.peerRatingScore)
                    }}</span>
                    <span class="rlabel">获评</span>
                  </span>
                </span>
              </div>
              <div class="detail-row" style="align-items: flex-start">
                <span class="dl">互助感想</span>
                <span class="dv" style="white-space: pre-wrap">{{
                  detailItem.peerComment || "无"
                }}</span>
              </div>
            </template>
          </div>

          <!-- 右侧：互助进度时间线 -->
          <div style="width: 190px; flex-shrink: 0; padding-top: 4px">
            <el-timeline>
              <el-timeline-item
                v-for="node in recordTimelineNodes"
                :key="node.label"
                :color="node.color"
                :hollow="!node.time"
                :timestamp="node.time || '—'"
              >
                {{ node.label }}
              </el-timeline-item>
            </el-timeline>
          </div>
        </div>

        <!-- 互借：物品状况记录 -->
        <template v-if="detailItem.type === ACTIVITY_ROLE.BORROW && detailItem.condBefore">
          <div class="dv-divider"></div>
          <p class="text-sm text-secondary" style="margin-bottom: 8px">
            物品状况记录
          </p>
          <div class="detail-row">
            <span class="dl">借出前</span
            ><span class="dv">{{ detailItem.condBefore }}</span>
          </div>
          <div class="detail-row">
            <span class="dl">归还后</span
            ><span class="dv">{{ detailItem.condAfter }}</span>
          </div>
          <div class="detail-row">
            <span class="dl">归还情况</span
            ><span class="dv">{{ detailItem.returnStatus || "--" }}</span>
          </div>
        </template>

        <div class="dv-divider"></div>
        <p class="text-sm text-secondary">
          评分由双方互评完成：发布者评分为互助对象的评价，互助对象评分为发布者的评价。
        </p>
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
  RecordsView.vue — 操作日志与导出历史

  功能：查看管理员操作日志列表（用户审核/内容下架/代发/管理员增删）、查看导出历史记录。
  权限：需管理员 / 超级管理员登录。
-->
<script setup lang="ts">
import { ref, computed, onMounted } from "vue";
import { ElMessage } from "element-plus";
import { ArrowLeft, ArrowRight } from "@element-plus/icons-vue";

import { useCommunityStore } from "@/stores/community";
import AppLayout from "@/layouts/AppLayout.vue";
import { LIST_PAGE_MAIN_CLASS } from "@/layouts/main-classes";
import { getRecords, type RecordItemDTO, type UnitData } from "../api/admin";
import { ACTIVITY_ROLE } from "@/utils/constants";

const communityStore = useCommunityStore();

onMounted(() => {
  communityStore.fetchCommunityData();
  fetchRecords();
});

/** 搜索关键词（按发布者/接手者/内容匹配） */
const search = ref("");
/** 时间范围筛选 */
const filterDateRange = ref<string[] | null>(null);
/** 类型筛选：'borrow' | 'help' */
const filterType = ref("");
/** 楼栋筛选（数值楼栋号；空串=全部） */
const filterBuilding = ref<number | "">("");
/** 单元筛选（数值单元号；空串=全部） */
const filterUnit = ref<number | "">("");

/** 从后端加载的真实互助记录数据 */
const tableData = ref<RecordItemDTO[]>([]);
/** 加载状态 */
const loading = ref(false);

/** 获取互助记录（合并借还+帮助两种类型） */
async function fetchRecords(): Promise<void> {
  loading.value = true;
  try {
    const res = await getRecords({ type: "all" });
    const pageData = res.data?.data;
    if (pageData?.content) {
      tableData.value = pageData.content as RecordItemDTO[];
    }
  } catch {
    ElMessage.error("加载互助记录失败");
    tableData.value = [];
  } finally {
    loading.value = false;
  }
}

/** 按筛选条件过滤后的记录列表 */
const filteredRecords = computed(() => {
  return tableData.value.filter((item) => {
    if (filterType.value && item.type !== filterType.value) return false;
    if (
      filterBuilding.value &&
      !(item.room || "").startsWith(filterBuilding.value + "栋")
    )
      return false;
    if (filterUnit.value && !matchUnit(item.room || "", filterUnit.value))
      return false;
    // 时间区间筛选：按记录开始日期（精确到日）落在所选起止范围内
    const range = filterDateRange.value;
    if (range && range.length === 2) {
      const [start, end] = range;
      const d = (item.timeStart || "").toString().slice(0, 10);
      if (d < start || d > end) return false;
    }
    if (search.value) {
      const s = search.value.trim();
      if (
        !item.publisher.includes(s) &&
        !item.peer.includes(s) &&
        !item.content.includes(s)
      )
        return false;
    }
    return true;
  });
});

// --- 选择 & 筛选 ---
/** 勾选的行 */
const selectedRows = ref<Record<string, unknown>[]>([]);
/** 根据所选楼栋计算单元筛选选项（UnitData 含 unitNo） */
const filterUnitOptions = computed<UnitData[]>(() => {
  if (!filterBuilding.value) return [];
  const buildingId = communityStore.getBuildingId(filterBuilding.value);
  return buildingId ? communityStore.getUnits(buildingId) : [];
});

/** 重置筛选条件 */
function applyFilters(): void {
  search.value = search.value.replace(/\s+/g, "");
}

/**
 * 单元筛选匹配：按数值单元号拼 "x单元" 后字符串包含判断。
 */
function matchUnit(room: string, unit: number): boolean {
  if (!room || !unit) return false;
  return room.includes(unit + "单元");
}

function handleSelectionChange(rows: any[]): void {
  selectedRows.value = rows;
}

// --- 详情弹窗（勾选后点「详细」打开，支持左右箭头切换） ---
const detailVisible = ref(false);
const detailList = ref<any[]>([]);
const detailPos = ref(0);
const detailItem = computed(() => detailList.value[detailPos.value] || null);

/** 从勾选的记录中打开详情弹窗 */
function openDetailFromSelection(): void {
  if (!selectedRows.value.length) {
    ElMessage.warning("请先勾选记录");
    return;
  }
  detailList.value = [...selectedRows.value];
  detailPos.value = 0;
  detailVisible.value = true;
}

/** 从「内容」列链接打开详情弹窗 — detailList 仅当前 1 条（统一架构：链接点击仅 1 条，详细按钮多条） */
function openDetailByRow(row: RecordItemDTO): void {
  detailList.value = [row];
  detailPos.value = 0;
  detailVisible.value = true;
}

/**
 * 详情弹窗左右翻页。
 * @param delta - 翻页偏移（-1 上一页，1 下一页）
 */
function detailGo(delta: number): void {
  const next = detailPos.value + delta;
  if (next < 0 || next >= detailList.value.length) return;
  detailPos.value = next;
}

// 互助进度时间线（5 节点：发布 → 申请 → 同意 → 归还·评价 → 评价）
const recordTimelineNodes = computed(() => {
  const it = detailItem.value;
  if (!it) return [];
  const isBorrow = it.type === ACTIVITY_ROLE.BORROW;
  const nodes: { label: string; time: string; color: string }[] = [
    { label: "发布时间", time: it.publishedAt || "—", color: "#909399" },
    {
      label: isBorrow ? "借入申请" : "申请主动帮忙",
      time: it.applyAt || "—",
      color: "#409eff",
    },
    {
      label: isBorrow ? "同意借出" : "同意帮助",
      time: it.approveAt || "—",
      color: "#e6a23c",
    },
  ];
  // 归还·评价（第一个评价）
  if (it.rating1Label) {
    nodes.push({
      label: `归还·${it.rating1Label}`,
      time: it.rating1Time || "—",
      color: "#67c23a",
    });
  }
  // 对方评价（第二个评价）
  if (it.rating2Label) {
    nodes.push({
      label: it.rating2Label,
      time: it.rating2Time || "—",
      color: "#f56c6c",
    });
  }
  return nodes;
});

/** 将评分数字 (1-5) 转换为星级字符串 (★★★★★) */
function toStars(score: number | null | undefined): string {
  if (score == null || score < 1 || score > 5) return "";
  return "★★★★★".slice(0, score) + "☆☆☆☆☆".slice(0, 5 - score);
}
</script>

<style scoped>
.rating-tag {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  background: var(--bg);
  border-radius: 4px;
  padding: 1px 6px;
  font-size: 11px;
  color: var(--orange);
  line-height: 1.3;
}
.rating-tag .stars {
  white-space: nowrap;
}
.rating-tag .rlabel {
  font-size: 9px;
  color: var(--text-tertiary);
}
.date-picker-no-arrow {
  background-image: none !important;
  padding-right: 12px !important;
}
/* 内容列链接 — 无下划线（hover 仅加深颜色，不描下划线） */
.content-link {
  color: var(--accent);
  cursor: pointer;
}
.content-link:hover {
  color: var(--accent-hover);
}
/* 发布者是第3列 */
:deep(.el-table__body tr td:nth-child(3)) {
  padding-left: 40px !important;
}
:deep(.el-table__header tr th:nth-child(3)) {
  padding-left: 40px !important;
}
</style>
