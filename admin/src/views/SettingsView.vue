<template>
  <AppLayout title="系统设置">
    <!-- 个人信息 -->
    <div class="panel">
      <div class="panel-header">
        <span class="panel-title">个人信息</span>
      </div>
      <div style="padding: 32px">
        <el-form label-width="100px" size="default">
          <el-form-item label="管理员姓名">
            <el-input
              v-model="profile.name"
              class="search-box"
              placeholder="请输入管理员姓名"
              style="width: 260px"
            />
          </el-form-item>
          <el-form-item label="角色">
            <el-tag type="primary">{{ userTypeLabel }}</el-tag>
          </el-form-item>
          <el-form-item>
            <el-button
              type="primary"
              :loading="profileLoading"
              @click="saveProfile"
              >保存修改</el-button
            >
          </el-form-item>
        </el-form>
      </div>
    </div>

    <!-- 修改密码 -->
    <div class="panel">
      <div class="panel-header">
        <span class="panel-title">修改密码</span>
      </div>
      <div style="padding: 32px">
        <el-form
          ref="pwdFormRef"
          :model="pwdForm"
          :rules="pwdRules"
          label-width="100px"
          size="default"
        >
          <el-form-item label="当前密码" prop="oldPassword">
            <el-input
              v-model="pwdForm.oldPassword"
              class="search-box"
              type="password"
              placeholder="请输入当前密码"
              style="width: 260px"
              show-password
            />
          </el-form-item>
          <el-form-item label="新密码" prop="newPassword">
            <el-input
              v-model="pwdForm.newPassword"
              class="search-box"
              type="password"
              placeholder="请输入新密码"
              style="width: 260px"
              show-password
            />
          </el-form-item>
          <el-form-item label="确认新密码" prop="confirmPassword">
            <el-input
              v-model="pwdForm.confirmPassword"
              class="search-box"
              type="password"
              placeholder="请再次输入新密码"
              style="width: 260px"
              show-password
            />
          </el-form-item>
          <el-form-item>
            <el-button
              type="primary"
              :loading="pwdLoading"
              @click="changePassword"
              >修改密码</el-button
            >
          </el-form-item>
        </el-form>
      </div>
    </div>

    <!-- 管理员列表（仅超级管理员可见）-->
    <div v-if="isSuperAdmin" class="panel">
      <div class="panel-header">
        <span class="panel-title">管理员列表</span>
        <el-button type="primary" size="small" @click="openAddAdmin"
          >+ 添加子账号</el-button
        >
      </div>
      <el-table
        :data="adminList"
        style="width: 100%"
        v-loading="adminListLoading"
      >
        <el-table-column prop="name" label="姓名" align="center" />
        <el-table-column label="角色" align="center">
          <template #default="{ row }">
            <span :class="['badge', roleBadge(row.userType)]">{{
              row.userTypeLabel
            }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="tenantName" label="小区" align="center" />
        <el-table-column prop="phone" label="手机" align="center" />
        <el-table-column label="创建时间" align="center" width="160">
          <template #default="{ row }">{{
            formatTime(row.createdAt)
          }}</template>
        </el-table-column>
        <el-table-column label="操作" align="center" width="100">
          <template #default="{ row }">
            <el-button
              v-if="row.userType !== 'super_admin'"
              size="small"
              link
              type="danger"
              :loading="deleting"
              @click="confirmDeleteAdmin(row)"
              >删除</el-button
            >
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 操作日志（仅超级管理员可见，固定高度，约6条，内部滚动） -->
    <div v-if="isSuperAdmin || isSeniorAdmin" class="panel">
      <div class="panel-header">
        <span class="panel-title">操作日志</span>
        <el-button :loading="exportingLogs" @click="handleExportLogs"
          >导出日志</el-button
        >
      </div>
      <div style="overflow: hidden; border-radius: 0 0 16px 16px">
        <div style="max-height: 270px; overflow-y: auto">
          <el-table
            :data="operationLogs"
            style="width: 100%"
            v-loading="logsLoading"
          >
            <el-table-column label="时间" align="center" width="180">
              <template #default="{ row }">{{
                formatTime(row.createdAt)
              }}</template>
            </el-table-column>
            <el-table-column
              prop="adminName"
              label="操作人"
              align="center"
              width="100"
            />
            <el-table-column label="操作" align="center" width="120">
              <template #default="{ row }">
                <el-tag type="primary">{{ actionLabel(row.action) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="detail" label="详情" />
          </el-table>
        </div>
      </div>
    </div>

    <!-- 添加管理员弹窗 -->
    <el-dialog v-model="addAdminVisible" title="添加子账号" width="440px">
      <el-form
        ref="addAdminFormRef"
        :model="addAdminForm"
        :rules="addAdminRules"
        label-width="80px"
        size="default"
      >
        <el-form-item label="姓名" prop="name">
          <el-input v-model="addAdminForm.name" placeholder="请输入姓名" />
        </el-form-item>
        <el-form-item label="手机号" prop="phone">
          <el-input v-model="addAdminForm.phone" placeholder="请输入手机号" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input
            v-model="addAdminForm.password"
            type="password"
            placeholder="请输入初始密码"
            show-password
          />
        </el-form-item>
        <el-form-item label="小区" prop="tenantId">
          <el-select
            v-model="addAdminForm.tenantId"
            placeholder="请选择目标小区"
            style="width: 100%"
          >
            <el-option
              v-for="t in tenantList"
              :key="t.id"
              :label="t.name"
              :value="t.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="角色">
          <el-radio-group v-model="addAdminForm.userType">
            <el-radio value="senior_admin">高级管理员</el-radio>
            <el-radio value="admin">普通管理员</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addAdminVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="addAdminLoading"
          @click="submitAddAdmin"
          >确认添加</el-button
        >
      </template>
    </el-dialog>
  </AppLayout>
</template>

<!--
  SettingsView.vue — 系统设置

  功能：管理员个人信息展示、小区/楼栋/单元/房间层级数据查看、管理员账号增删管理（仅超级管理员）。
  权限：个人信息所有管理员可查看；管理员账号管理仅超级管理员可操作。
-->
<script setup lang="ts">
import { ref, reactive, computed, onMounted } from "vue";
import { ElMessage, ElMessageBox, type FormInstance } from "element-plus";

import { useAuthStore } from "@/stores/auth";
import {
  updateProfile,
  updatePassword,
  getAdmins,
  createAdmin,
  deleteAdmin,
  getLogs,
  getTenants,
  exportOperationLogs,
  type OperationLogDTO,
} from "@/api/admin";
import AppLayout from "@/layouts/AppLayout.vue";
import type { AxiosError } from "axios";
import type { AdminUser } from "@/api/auth";

// --- 本地类型 ---

/** 个人信息表单 */
interface ProfileForm {
  name: string;
}

/** 修改密码表单 */
interface PwdForm {
  oldPassword: string;
  newPassword: string;
  confirmPassword: string;
}

/** 添加管理员表单 */
interface AddAdminForm {
  name: string;
  phone: string;
  password: string;
  tenantId: number | null;
  userType: string;
}

/** 小区下拉选项 */
interface TenantOption {
  id: number;
  name: string;
}

/** 管理员列表行数据 */
interface AdminRow {
  id: number;
  name: string;
  phone?: string;
  userType: string;
  /** 所属小区名称（super_admin 显示 "全部小区"） */
  tenantName?: string;
}

const authStore = useAuthStore();

/** 是否为超级管理员（超级管理员可管理子账号） */
const isSuperAdmin = computed(() => authStore.user?.userType === "super_admin");
/** 是否为高级管理员 */
const isSeniorAdmin = computed(
  () => authStore.user?.userType === "senior_admin",
);
/** 当前用户类型的中文标签 */
const userTypeLabel = computed(() => {
  const map: Record<string, string> = {
    super_admin: "超级管理员",
    senior_admin: "高级管理员",
    admin: "普通管理员",
  };
  return (
    map[authStore.user?.userType || ""] || authStore.user?.userType || "管理员"
  );
});

// ==================== 个人信息 ====================
/** 个人信息表单数据 */
const profile = reactive<ProfileForm>({ name: authStore.user?.name || "" });
/** 保存个人信息 loading */
const profileLoading = ref(false);

/** 保存个人信息到后端 */
async function saveProfile(): Promise<void> {
  profileLoading.value = true;
  try {
    const res = await updateProfile({ name: profile.name });
    const updated = res.data.data as { name: string };
    authStore.login(authStore.token, {
      ...authStore.user,
      name: updated.name,
    } as AdminUser);
    ElMessage.success("个人信息已保存");
  } catch (err) {
    const axiosErr = err as AxiosError<{ message?: string }>;
    ElMessage.error(axiosErr.response?.data?.message || "保存失败");
  } finally {
    profileLoading.value = false;
  }
}

// ==================== 修改密码 ====================
/** el-form 组件引用 */
const pwdFormRef = ref<FormInstance>();
/** 密码表单数据 */
const pwdForm = reactive<PwdForm>({
  oldPassword: "",
  newPassword: "",
  confirmPassword: "",
});
/** 修改密码 loading */
const pwdLoading = ref(false);

/** 自定义校验：确认密码是否与新密码一致 */
const validateConfirmPassword = (
  _rule: any,
  value: string,
  callback: (e?: Error) => void,
) => {
  if (value !== pwdForm.newPassword) {
    callback(new Error("两次输入的新密码不一致"));
  } else {
    callback();
  }
};

/** el-form 密码校验规则 */
const pwdRules = {
  oldPassword: [{ required: true, message: "请输入当前密码", trigger: "blur" }],
  newPassword: [
    { required: true, message: "请输入新密码", trigger: "blur" },
    { min: 6, message: "密码长度不能少于6位", trigger: "blur" },
  ],
  confirmPassword: [
    { required: true, message: "请再次输入新密码", trigger: "blur" },
    { validator: validateConfirmPassword, trigger: "blur" },
  ],
};

/** 提交修改密码 */
async function changePassword(): Promise<void> {
  const valid = await pwdFormRef.value?.validate().catch(() => false);
  if (!valid) return;

  pwdLoading.value = true;
  try {
    await updatePassword({
      oldPassword: pwdForm.oldPassword,
      newPassword: pwdForm.newPassword,
    });
    ElMessage.success("密码修改成功");
    pwdForm.oldPassword = "";
    pwdForm.newPassword = "";
    pwdForm.confirmPassword = "";
  } catch (err) {
    const axiosErr = err as AxiosError<{ message?: string }>;
    ElMessage.error(axiosErr.response?.data?.message || "密码修改失败");
  } finally {
    pwdLoading.value = false;
  }
}

// ==================== 管理员列表 ====================
/** 管理员列表 */
const adminList = ref<AdminRow[]>([]);
/** 管理员列表加载状态 */
const adminListLoading = ref(false);
/** 删除管理员进行中 */
const deleting = ref(false);

/** 从后端获取管理员列表 */
async function fetchAdmins(): Promise<void> {
  adminListLoading.value = true;
  try {
    const res = await getAdmins();
    adminList.value = res.data.data as AdminRow[];
  } catch {
    ElMessage.error("获取管理员列表失败");
  } finally {
    adminListLoading.value = false;
  }
}

/** 角色标签 CSS 映射 */
function roleBadge(userType: string): string {
  if (userType === "super_admin") return "badge-info";
  if (userType === "senior_admin") return "badge-warning";
  return "badge-success";
}

/** 小区列表（super_admin 创建管理员时选择目标小区） */
const tenantList = ref<TenantOption[]>([]);
/** 加载小区列表 */
async function fetchTenants(): Promise<void> {
  try {
    const res = await getTenants();
    tenantList.value = res.data.data || [];
  } catch {
    /* silent */
  }
}

/** 确认并删除指定管理员 */
async function confirmDeleteAdmin(row: AdminRow): Promise<void> {
  try {
    await ElMessageBox.confirm(`确认删除子账号「${row.name}」？`, "提示", {
      confirmButtonText: "删除",
      cancelButtonText: "取消",
      type: "warning",
    });
    deleting.value = true;
    await deleteAdmin(row.id);
    ElMessage.success("已删除");
    fetchAdmins();
  } catch (err) {
    if (err !== "cancel") {
      const axiosErr = err as AxiosError<{ message?: string }>;
      ElMessage.error(axiosErr.response?.data?.message || "删除失败");
    }
  } finally {
    deleting.value = false;
  }
}

// ==================== 添加管理员弹窗 ====================
const addAdminVisible = ref(false);
const addAdminFormRef = ref<FormInstance>();
const addAdminForm = reactive<AddAdminForm>({
  name: "",
  phone: "",
  password: "",
  tenantId: null,
  userType: "admin",
});
const addAdminLoading = ref(false);

const addAdminRules = {
  name: [{ required: true, message: "请输入姓名", trigger: "blur" }],
  phone: [
    { required: true, message: "请输入手机号", trigger: "blur" },
    {
      pattern: /^1[3-9]\d{9}$/,
      message: "请输入正确的手机号",
      trigger: "blur",
    },
  ],
  password: [
    { required: true, message: "请输入初始密码", trigger: "blur" },
    { min: 6, message: "密码长度不能少于6位", trigger: "blur" },
  ],
  tenantId: [{ required: true, message: "请选择小区", trigger: "change" }],
};

function openAddAdmin(): void {
  addAdminForm.name = "";
  addAdminForm.phone = "";
  addAdminForm.password = "";
  addAdminForm.tenantId = null;
  addAdminForm.userType = "admin";
  addAdminVisible.value = true;
}

async function submitAddAdmin(): Promise<void> {
  const valid = await addAdminFormRef.value?.validate().catch(() => false);
  if (!valid) return;

  addAdminLoading.value = true;
  try {
    await createAdmin({
      name: addAdminForm.name,
      phone: addAdminForm.phone,
      password: addAdminForm.password,
      tenantId: addAdminForm.tenantId!,
      userType: addAdminForm.userType,
    });
    addAdminVisible.value = false;
    ElMessage.success(`子账号「${addAdminForm.name}」已添加`);
    fetchAdmins();
  } catch (err) {
    const axiosErr = err as AxiosError<{ message?: string }>;
    ElMessage.error(axiosErr.response?.data?.message || "添加失败");
  } finally {
    addAdminLoading.value = false;
  }
}

// ==================== 操作日志 ====================
const operationLogs = ref<OperationLogDTO[]>([]);
const logsLoading = ref(false);
const exportingLogs = ref(false);

async function fetchLogs(): Promise<void> {
  logsLoading.value = true;
  try {
    const res = await getLogs();
    operationLogs.value = res.data.data.content || [];
  } catch {
    // silent
  } finally {
    logsLoading.value = false;
  }
}

/** 导出操作日志为 Excel */
async function handleExportLogs(): Promise<void> {
  exportingLogs.value = true;
  try {
    await exportOperationLogs();
    ElMessage.success("操作日志已导出");
  } catch (err: any) {
    ElMessage.error(err.message || "导出失败");
  } finally {
    exportingLogs.value = false;
  }
}

function actionLabel(action: string): string {
  const map: Record<string, string> = {
    approve_user: "审核通过",
    reject_user: "驳回审核",
    remove_content: "下架内容",
    proxy_publish_idle: "代发闲置",
    proxy_publish_help: "代发求助",
    create_admin: "添加账号",
    delete_admin: "删除账号",
  };
  return map[action] || action;
}

// ==================== 辅助函数 ====================
/** 格式化时间字符串为 "YYYY-MM-DD HH:mm" 格式 */
function formatTime(t?: string): string {
  if (!t) return "";
  if (typeof t === "string") {
    const d = new Date(t);
    if (Number.isNaN(d.getTime())) return t.substring(0, 16);
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")} ${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
  }
  return "";
}

// ==================== 生命周期 ====================
onMounted(() => {
  profile.name = authStore.user?.name || "";
  if (isSuperAdmin.value) {
    fetchTenants();
    fetchAdmins();
  }
  if (isSuperAdmin.value || isSeniorAdmin.value) {
    fetchLogs();
  }
});
</script>
