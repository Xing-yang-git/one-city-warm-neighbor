<template>
  <AppLayout title="物业运营端" :loading="loading">
    <template #subtitle>{{ todayWithTime }}</template>
    <template #actions="{ logout }">
      <span class="logout-btn" @click="logout" title="退出登录">
        <svg
          width="18"
          height="18"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="1.5"
          stroke-linecap="round"
          stroke-linejoin="round"
        >
          <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" />
          <polyline points="16 17 21 12 16 7" />
          <line x1="21" y1="12" x2="9" y2="12" />
        </svg>
      </span>
    </template>
        <!-- 快速统计 -->
        <div class="quick-stats">
          <div class="quick-stat">
            <span class="qs-value text-orange">{{ stats.pending }}</span>
            <span class="qs-label">待审核住户</span>
          </div>
          <div class="quick-stat">
            <span class="qs-value">{{ stats.idle }}</span>
            <span class="qs-label">在线闲置</span>
          </div>
          <div class="quick-stat">
            <span class="qs-value">{{ stats.pub }}</span>
            <span class="qs-label">本月发布</span>
          </div>
          <div class="quick-stat">
            <span class="qs-value">{{ stats.mau }}</span>
            <span class="qs-label">本月活跃住户</span>
          </div>
        </div>

        <!-- 核心操作 -->
        <div class="section-label-title">核心操作</div>
        <div class="launcher-grid">
          <div class="launch-card" @click="$router.push('/audit')">
            <div
              class="card-icon"
              style="background: rgba(0, 113, 227, 0.08); color: var(--accent)"
            >
              <svg
                width="24"
                height="24"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="1.5"
                stroke-linecap="round"
                stroke-linejoin="round"
              >
                <circle cx="9" cy="7" r="3" />
                <path d="M3 21v-2a4 4 0 0 1 4-4h4a4 4 0 0 1 4 4v2" />
                <circle cx="18" cy="7" r="2.5" />
                <path d="M15 14.5a3.5 3.5 0 0 1 4 3.5v2" />
              </svg>
            </div>
            <div class="card-title">住户管理</div>
            <div class="card-desc">审核工作台 · 住户列表 · 封禁管理</div>
            <div v-if="stats.pending > 0" class="card-badge tag tag-red">{{ stats.pending }} 人待审</div>
          </div>
          <div class="launch-card" @click="$router.push('/content')">
            <div
              class="card-icon"
              style="background: rgba(255, 149, 0, 0.08); color: var(--orange)"
            >
              <svg
                width="24"
                height="24"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="1.5"
                stroke-linecap="round"
                stroke-linejoin="round"
              >
                <path d="M12 20h9" />
                <path
                  d="M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z"
                />
              </svg>
            </div>
            <div class="card-title">内容管理</div>
            <div class="card-desc">巡查 · 违规下架 · 物业代发</div>
          </div>
          <div class="launch-card" @click="$router.push('/dashboard')">
            <div
              class="card-icon"
              style="background: rgba(52, 199, 89, 0.08); color: var(--green)"
            >
              <svg
                width="24"
                height="24"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="1.5"
                stroke-linecap="round"
                stroke-linejoin="round"
              >
                <rect x="3" y="3" width="7" height="7" rx="1" />
                <rect x="14" y="3" width="7" height="4" rx="1" />
                <rect x="14" y="11" width="7" height="10" rx="1" />
                <rect x="3" y="14" width="7" height="7" rx="1" />
              </svg>
            </div>
            <div class="card-title">数据看板</div>
            <div class="card-desc">运营概览 · 互助趋势 · 损坏统计</div>
          </div>
        </div>

        <!-- 数据与记录 -->
        <div class="section-label-title">数据与记录</div>
        <div class="launcher-grid">
          <div class="launch-card" @click="$router.push('/records')">
            <div
              class="card-icon"
              style="background: rgba(0, 113, 227, 0.04); color: var(--accent)"
            >
              <svg
                width="24"
                height="24"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="1.5"
                stroke-linecap="round"
                stroke-linejoin="round"
              >
                <path
                  d="M9 5H7a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2h-2"
                />
                <rect x="9" y="3" width="6" height="4" rx="1" />
                <path d="M9 14l2 2 4-4" />
              </svg>
            </div>
            <div class="card-title">互助记录</div>
            <div class="card-desc">住户互助档案 · 按户查询 · 时间筛选</div>
          </div>
          <div class="launch-card" @click="$router.push('/export')">
            <div
              class="card-icon"
              style="background: rgba(0, 113, 227, 0.04); color: var(--accent)"
            >
              <svg
                width="24"
                height="24"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="1.5"
                stroke-linecap="round"
                stroke-linejoin="round"
              >
                <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
                <polyline points="7 10 12 15 17 10" />
                <line x1="12" y1="15" x2="12" y2="3" />
              </svg>
            </div>
            <div class="card-title">数据导出</div>
            <div class="card-desc">报表导出 · 自动备份 · 导出日志</div>
          </div>
        </div>

        <!-- 系统设置（仅高级管理员及以上可见） -->
        <template v-if="isSeniorAdmin">
        <div class="section-label-title">系统</div>
        <div class="launcher-grid">
          <div class="launch-card" @click="$router.push('/settings')">
            <div
              class="card-icon"
              style="background: rgba(0, 113, 227, 0.04); color: var(--accent)"
            >
              <svg
                width="24"
                height="24"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="1.5"
                stroke-linecap="round"
                stroke-linejoin="round"
              >
                <circle cx="12" cy="12" r="3" />
                <path
                  d="M12 1v2M12 21v2M4.22 4.22l1.42 1.42M18.36 18.36l1.42 1.42M1 12h2M21 12h2M4.22 19.78l1.42-1.42M18.36 5.64l1.42-1.42"
                />
              </svg>
            </div>
            <div class="card-title">系统设置</div>
            <div class="card-desc">权限管理 · 操作日志 · 系统备用</div>
          </div>
        </div>
        </template>
  </AppLayout>
</template>

<!--
  HomeView.vue — 物业运营端入口布局页

  功能：侧边栏导航 + 顶部栏（日期时间 + 用户信息 + 退出登录）+ 子路由出口。
  权限：需管理员 / 超级管理员登录。
-->
<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { useRouter } from "vue-router";

import { useAuthStore } from "@/stores/auth";
import { USER_TYPE } from "@/utils/constants";
import AppLayout from "@/layouts/AppLayout.vue";
import { getAuditCounts, getDashboard, type AuditCounts, type DashboardDTO, type DashboardKpi } from "@/api/admin";

/** 首页快速统计卡片的数据结构 */
interface QuickStats {
  /** 待审核住户数 */
  pending: number;
  /** 在线闲置物品数 */
  idle: number;
  /** 本月发布总数 */
  pub: number;
  /** 本月活跃住户数 */
  mau: number;
}

const router = useRouter();
const authStore = useAuthStore();

/** 首页快速统计加载中（配合 AppLayout v-loading 遮罩） */
const loading = ref<boolean>(false);
/** 首页快速统计数据 — 默认 0，接口返回后填充 */
const stats = reactive<QuickStats>({ pending: 0, idle: 0, pub: 0, mau: 0 });

// super_admin 仅管理平台，自动跳转系统设置页
onMounted(() => {
  if (authStore.user?.userType === USER_TYPE.SUPER_ADMIN) {
    router.replace('/settings');
    return; // 超管无小区业务数据，直接跳转设置页，不请求看板统计
  }
  loadQuickStats();
});

/** 是否为高级管理员及以上 */
const isSeniorAdmin = computed(() => {
  const ut = authStore.user?.userType;
  return ut === USER_TYPE.SUPER_ADMIN || ut === USER_TYPE.SENIOR_ADMIN;
});

/** 当前日期 + 时间，格式：2026年7月22日周四 14:30 */
const todayWithTime = computed(() => {
  const d = new Date();
  const days = ["日", "一", "二", "三", "四", "五", "六"];
  const pad = (n: number): string => String(n).padStart(2, "0");
  return `${d.getFullYear()}年${d.getMonth() + 1}月${d.getDate()}日周${days[d.getDay()]} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
});

/** 从看板 KPI 列表取指定 key 的数值，缺失时返回 0 */
function kpiValue(key: DashboardKpi["key"], kpis: DashboardKpi[]): number {
  return kpis.find((k) => k.key === key)?.value ?? 0;
}

/**
 * 拉取首页快速统计：看板 KPI（在线闲置/本月发布/本月活跃住户）+ 待审核住户数。
 * 统计属非关键路径，失败静默保留默认 0，不影响首页导航入口。
 */
async function loadQuickStats(): Promise<void> {
  loading.value = true;
  try {
    const [dashboardRes, auditRes] = await Promise.all([getDashboard(), getAuditCounts()]);
    const dashboard = dashboardRes.data?.data as DashboardDTO | undefined;
    const audit = auditRes.data?.data as AuditCounts | undefined;
    if (dashboard?.kpis) {
      stats.idle = kpiValue("idle", dashboard.kpis);
      stats.pub = kpiValue("pub", dashboard.kpis);
      stats.mau = kpiValue("mau", dashboard.kpis);
    }
    stats.pending = audit?.pending ?? 0;
  } catch {
    // 统计加载失败不阻断首页：保留默认 0，仅记录日志便于排查
    console.warn("[Home] 快速统计数据加载失败");
  } finally {
    loading.value = false;
  }
}

</script>

<style scoped>
.section-label-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-tertiary);
  text-transform: uppercase;
  letter-spacing: 0.06em;
  margin: 28px 0 12px;
}
.section-label-title:first-of-type {
  margin-top: 0;
}

.launch-card {
  background: var(--surface);
  border-radius: var(--radius);
  padding: 24px;
  box-shadow: var(--shadow);
  cursor: pointer;
  transition:
    transform 150ms var(--ease),
    box-shadow 150ms var(--ease);
  display: flex;
  flex-direction: column;
  gap: 10px;
  text-decoration: none;
  color: inherit;
  position: relative;
  overflow: hidden;
}

.launch-card:hover {
  transform: translateY(-2px);
  box-shadow: var(--shadow-md);
}

.launch-card:active {
  transform: translateY(0);
  box-shadow: var(--shadow);
}

.launch-card .card-icon {
  width: 48px;
  height: 48px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 24px;
  flex-shrink: 0;
}

.launch-card .card-title {
  font-size: 16px;
  font-weight: 600;
  letter-spacing: -0.01em;
}

.launch-card .card-desc {
  font-size: 13px;
  color: var(--text-secondary);
  line-height: 1.55;
}

.launch-card .card-badge {
  position: absolute;
  top: 16px;
  right: 16px;
  font-size: 11px;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: 10px;
}

</style>
