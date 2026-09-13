const api = require('../../utils/api');
const { AUTH_STATUS } = require('../../utils/constants');

/**
 * 用户注册页 — 多步骤注册流程（微信授权 → 小区选择 → 身份信息 → 完成）。
 *
 * 功能：小区/楼栋/单元/房间级联选择、身份类型选择（业主/租户）、
 *        姓名和手机号填写、证件照片上传、提交审核。
 */
Page({
  data: {
    currentStep: 0,
    searchKeyword: '',
    tenants: [],
    filteredTenants: [],
    selectedTenant: '',
    selectedTenantObj: null,
    building: '',
    unit: '',
    room: '',
    residentType: 'owner',
    nickPreview: '1栋1单元1001号(业主)',
    phone: '',
    password: '',
    passwordConfirm: '',
    showPassword: false,
    showPasswordConfirm: false,
    realName: '',
    docImages: [],
    docLabel: '上传认证资料',
    docHint: '请上传清晰完整的房产证照片（支持拍照或相册选取）'
  },

  onLoad(options) {
    // 全新注册（非 needRegister 流程）时清除残留旧 token，
    // 防止同设备上一位用户的 token 导致后端覆写其数据。
    // needRegister 流程（来自 wxLogin 预创建）需保留 token 以更新已有用户。
    if (!options || options.from !== 'needRegister') {
      wx.removeStorageSync('token');
      wx.removeStorageSync('userInfo');
      const app = getApp();
      if (app) {
        app.globalData.token = '';
        app.globalData.userInfo = null;
        if (app._socketTask) {
          try { app._socketTask.close({}); } catch (e) {}
          app._socketTask = null;
          app.globalData.socketOpen = false;
        }
      }
    }
    this.loadTenants();
  },

  loadTenants() {
    wx.showLoading({ title: '加载中...' });
    api.get('/api/common/tenants')
      .then((data) => {
        wx.hideLoading();
        // ListDTO 分页包装：content 为数组；兼容旧的裸数组返回
        const list = data?.content || (Array.isArray(data) ? data : []);
        this.setData({ tenants: list, filteredTenants: list });
      })
      .catch((err) => {
        wx.hideLoading();
        wx.showToast({ title: err.message, icon: 'none' });
      });
  },

  onSearchInput(e) {
    const keyword = e.detail.value.toLowerCase();
    this.setData({ searchKeyword: e.detail.value });
    const filtered = this.data.tenants.filter(t =>
      t.name.toLowerCase().includes(keyword) || (t.address || '').toLowerCase().includes(keyword)
    );
    this.setData({ filteredTenants: filtered });
  },

  onClearSearch() {
    this.setData({
      searchKeyword: '',
      filteredTenants: this.data.tenants
    });
  },

  onSelectTenant(e) {
    const tenantId = e.currentTarget.dataset.tenantId;
    const item = this.data.filteredTenants.find(t => t.id === tenantId);
    if (!item) return;
    // 存储小区名称供首页展示
    wx.setStorageSync('communityName', item.name);
    this.setData({
      selectedTenant: tenantId,
      selectedTenantObj: item,
      searchKeyword: item.name
    });
  },

  onBuildingInput(e) {
    this.setData({ building: e.detail.value });
    this.updateNick();
  },

  onUnitInput(e) {
    this.setData({ unit: e.detail.value });
    this.updateNick();
  },

  onRoomInput(e) {
    this.setData({ room: e.detail.value });
    this.updateNick();
  },

  onSetType(e) {
    const type = e.currentTarget.dataset.type;
    const docLabel = type === 'owner' ? '上传认证资料' : '上传租赁合同';
    const docHint = type === 'owner'
      ? '请上传清晰完整的房产证照片（支持拍照或相册选取）'
      : '请上传清晰完整的租赁合同照片，需包含租期起止日期';
    this.setData({ residentType: type, docLabel, docHint });
    this.updateNick();
  },

  updateNick() {
    const b = this.data.building || '1';
    const u = this.data.unit || '1';
    const r = this.data.room || '1001';
    const typeLabel = this.data.residentType === 'owner' ? '业主' : '租客';
    this.setData({ nickPreview: `${b}栋${u}单元${r}号(${typeLabel})` });
  },

  // ── Step 3: Phone + Password ──
  onPhoneInput(e) {
    this.setData({ phone: e.detail.value });
  },

  onPasswordInput(e) {
    this.setData({ password: e.detail.value });
  },

  onPasswordConfirmInput(e) {
    this.setData({ passwordConfirm: e.detail.value });
  },

  onTogglePassword() {
    this.setData({ showPassword: !this.data.showPassword });
  },

  onTogglePasswordConfirm() {
    this.setData({ showPasswordConfirm: !this.data.showPasswordConfirm });
  },

  onNameInput(e) {
    this.setData({ realName: e.detail.value });
  },

  onAddImage() {
    wx.chooseImage({
      count: 3 - this.data.docImages.length,
      sizeType: ['compressed'],
      sourceType: ['album', 'camera'],
      success: (res) => {
        const newImages = this.data.docImages.concat(res.tempFilePaths);
        this.setData({ docImages: newImages.slice(0, 3) });
      }
    });
  },

  onPreviewImage(e) {
    const index = e.currentTarget.dataset.index;
    wx.previewImage({
      current: this.data.docImages[index],
      urls: this.data.docImages
    });
  },

  onDeleteImage(e) {
    const index = e.currentTarget.dataset.index;
    const images = this.data.docImages;
    images.splice(index, 1);
    this.setData({ docImages: images });
  },

  onPrevStep() {
    if (this.data.currentStep > 0) {
      this.setData({ currentStep: this.data.currentStep - 1 });
    }
  },

  onNextStep() {
    const { currentStep, selectedTenant, building, unit, room,
            phone, password, passwordConfirm,
            realName, docImages, residentType } = this.data;

    // Step 1 validation
    if (currentStep === 0) {
      if (!selectedTenant) {
        wx.showToast({ title: '请选择小区', icon: 'none' });
        return;
      }
      this.setData({ currentStep: 1 });
      return;
    }

    // Step 2 validation
    if (currentStep === 1) {
      if (!building || !/^\d{1,2}$/.test(building) || parseInt(building) < 1 || parseInt(building) > 99) {
        wx.showToast({ title: '请填写正确的栋号（1-99）', icon: 'none' });
        return;
      }
      if (!unit || !/^\d{1}$/.test(unit) || parseInt(unit) < 1 || parseInt(unit) > 9) {
        wx.showToast({ title: '请填写正确的单元号（1-9）', icon: 'none' });
        return;
      }
      if (!room || !/^\d{1,4}$/.test(room) || parseInt(room) < 1 || parseInt(room) > 9999) {
        wx.showToast({ title: '请填写正确的房号（1-9999）', icon: 'none' });
        return;
      }
      this.updateNick();
      this.setData({ currentStep: 2 });
      return;
    }

    // Step 3 validation: Phone + Password
    if (currentStep === 2) {
      const phoneTrim = phone.trim();
      if (!phoneTrim) {
        wx.showToast({ title: '请输入手机号', icon: 'none' });
        return;
      }
      if (!/^1[3-9]\d{9}$/.test(phoneTrim)) {
        wx.showToast({ title: '请输入正确的手机号', icon: 'none' });
        return;
      }
      if (!password || password.length < 8 || password.length > 20) {
        wx.showToast({ title: '密码需要8-20位', icon: 'none' });
        return;
      }
      if (!/[a-zA-Z]/.test(password) || !/[0-9]/.test(password)) {
        wx.showToast({ title: '密码需要包含字母和数字', icon: 'none' });
        return;
      }
      if (password !== passwordConfirm) {
        wx.showToast({ title: '两次密码输入不一致', icon: 'none' });
        return;
      }
      this.setData({ currentStep: 3 });
      return;
    }

    // Step 4: Submit
    if (currentStep === 3) {
      if (!realName.trim()) {
        wx.showToast({ title: '请填写真实姓名', icon: 'none' });
        return;
      }
      if (docImages.length === 0) {
        wx.showToast({ title: '请上传证件照片', icon: 'none' });
        return;
      }

      wx.showLoading({ title: '上传证件照...' });

      // 先上传全部证件图片，再携带 URL 提交
      const uploadTasks = docImages.map(filePath => api.upload('/api/common/upload', filePath));
      Promise.all(uploadTasks)
        .then((urls) => {
          wx.showLoading({ title: '提交中...' });
          return api.post('/api/auth/register', {
            tenantId: selectedTenant,
            buildingNo: Number(building),
            unitNo: Number(unit),
            room: room,
            phone: phone.trim(),
            password: password,
            name: realName.trim(),
            userType: residentType,
            docImages: urls
          });
        })
        .then((data) => {
          wx.hideLoading();
          // 保存公开注册响应中的 token 和用户信息
          if (data.token) {
            wx.setStorageSync('token', data.token);
          }
          if (data.user) {
            wx.setStorageSync('userInfo', data.user);
            const app = getApp();
            app.globalData.userInfo = data.user;
          }
          wx.showToast({ title: '提交成功，请等待物业审核', icon: 'success' });
          setTimeout(() => {
            wx.redirectTo({ url: '/pages/review-status/review-status?state=' + AUTH_STATUS.PENDING });
          }, 1200);
        })
        .catch((err) => {
          wx.hideLoading();
          wx.showToast({ title: err.message || '提交失败', icon: 'none' });
        });
    }
  }
});
