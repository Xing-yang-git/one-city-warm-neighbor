const api = require('../../utils/api');
const { AUTH_STATUS } = require('../../utils/constants');

/**
 * 手机号登录页 — 手机号 + 密码方式登录。
 *
 * 功能：小区选择、手机号密码输入、同意协议复选框、登录请求。
 * 登录成功后跳转首页或注册页（未注册用户）。
 */
Page({
  data: {
    agreed: false,
    phone: '',
    password: '',
    showPassword: false,
    loading: false,
    tenants: [],
    tenantNames: [],
    selectedTenantIndex: -1,
    selectedTenantId: ''
  },

  onLoad() {
    // 显示 forceRelogin / WS 挤下线时写入的提示消息
    const loginMessage = wx.getStorageSync('loginMessage');
    if (loginMessage) {
      wx.removeStorageSync('loginMessage');
      wx.showToast({ title: loginMessage, icon: 'none', duration: 3000 });
    }

    const token = wx.getStorageSync('token');
    if (token) {
      const userInfo = wx.getStorageSync('userInfo') || {};
      if (userInfo.authStatus) {
        this.routeByStatus(userInfo.authStatus);
        // pending / rejected / banned 不跳转、停留在本页，需要加载小区列表。
        // approved / registering 会 redirectTo / switchTab 离开，loadTenants 多跑一次无害。
        this.loadTenants();
      } else {
        // 老会话无 authStatus：拉一次真实状态；401 时 forceRelogin 已清 token 并停留登录页
        api.get('/api/auth/status')
          .then((d) => {
            this.routeByStatus(d.authStatus || AUTH_STATUS.APPROVED);
            this.loadTenants();
          })
          .catch(() => { this.loadTenants(); });
      }
      return;
    }
    this.loadTenants();
  },

  onHide() {
    // 离开登录页（navigateTo 去注册页等）时清空密码，避免残留
    this.setData({ password: '', showPassword: false });
  },

  onUnload() {
    // 页面销毁（redirectTo/switchTab 离开）时清空密码，避免残留
    this.setData({ password: '', showPassword: false });
  },

  loadTenants() {
    api.get('/api/common/tenants')
      .then((data) => {
        // ListDTO 分页包装：content 为数组；兼容旧的裸数组返回
        const list = data?.content || (Array.isArray(data) ? data : []);
        this.setData({
          tenants: list,
          tenantNames: list.map(t => t.name)
        });
      })
      .catch(() => {
        // 静默失败
      });
  },

  onTenantChange(e) {
    const idx = parseInt(e.detail.value);
    const tenant = this.data.tenants[idx];
    this.setData({
      selectedTenantIndex: idx,
      selectedTenantId: tenant ? tenant.id : ''
    });
  },

  onAgreeChange(e) {
    this.setData({ agreed: e.detail.value.length > 0 });
  },

  onPhoneInput(e) {
    this.setData({ phone: e.detail.value });
  },

  onPasswordInput(e) {
    this.setData({ password: e.detail.value });
  },

  onTogglePassword() {
    this.setData({ showPassword: !this.data.showPassword });
  },

  onLogin() {
    if (!this.data.agreed) {
      wx.showToast({ title: '请先同意用户协议', icon: 'none' });
      return;
    }

    if (!this.data.selectedTenantId) {
      wx.showToast({ title: '请选择小区', icon: 'none' });
      return;
    }

    const phone = this.data.phone.trim();
    if (!phone) {
      wx.showToast({ title: '请输入手机号', icon: 'none' });
      return;
    }
    if (!/^1[3-9]\d{9}$/.test(phone)) {
      wx.showToast({ title: '请输入正确的手机号', icon: 'none' });
      return;
    }

    const password = this.data.password;
    if (!password) {
      wx.showToast({ title: '请输入密码', icon: 'none' });
      return;
    }

    this.setData({ loading: true });

    api.post('/api/auth/phone-login', { phone, password, tenantId: this.data.selectedTenantId })
      .then((data) => {
        this.setData({ loading: false });
        this.handleLoginSuccess(data);
      })
      .catch((err) => {
        this.setData({ loading: false });
        wx.showToast({ title: err.message || '登录失败', icon: 'none' });
      });
  },

  onGoRegister() {
    // 全新注册：标记来源以便注册页清除残留旧 token
    wx.navigateTo({ url: '/pages/register/register?from=signup' });
  },

  /**
   * 按审核状态跳转。
   * 注意：pending / rejected / banned 不在此处跳转审核页——
   * 用户可能是通过审核页左上角返回键回到登录页想换号登录，
   * 跳回去会造成死循环。未审核用户的拦截由业务页 ensureAccess() 负责。
   */
  routeByStatus(status) {
    if (status === AUTH_STATUS.REGISTERING) {
      wx.redirectTo({ url: '/pages/register/register?from=needRegister' });
    } else if (status === AUTH_STATUS.APPROVED) {
      wx.switchTab({ url: '/pages/home/home' });
    }
    // pending / rejected / banned → 留在登录页，用户可以换号登录
  },

  handleLoginSuccess(data) {
    if (data.token) {
      wx.setStorageSync('token', data.token);
      if (data.user) {
        wx.setStorageSync('userInfo', data.user);
        const app = getApp();
        app.globalData.userInfo = data.user;
      }

      const userStatus = data.user ? data.user.authStatus : (data.authStatus || data.status);
      if (data.needRegister) {
        wx.redirectTo({ url: '/pages/register/register?from=needRegister' });
      } else if (userStatus === AUTH_STATUS.PENDING || userStatus === AUTH_STATUS.REJECTED || userStatus === AUTH_STATUS.BANNED) {
        // 登录成功后，未通过审核的用户跳转到审核状态页查看账号状态
        // 与 routeByStatus 的区别：routeByStatus 对此类状态不做跳转（防止用户从审核页
        // 返回换号登录时死循环），但此处是刚完成登录，用户理应看到自己的审核状态
        wx.redirectTo({ url: '/pages/review-status/review-status?state=' + userStatus });
      } else {
        this.routeByStatus(userStatus || AUTH_STATUS.APPROVED);
      }
    } else {
      wx.showToast({ title: '登录失败，请重试', icon: 'none' });
    }
  }
});
