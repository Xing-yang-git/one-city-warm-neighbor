const api = require('../../utils/api');
const auth = require('../../utils/auth');
const { POST_STATUS, BORROW_STATUS, POST_TYPE } = require('../../utils/constants');

/**
 * 闲置物品详情页 — 物品信息展示 + 借用操作入口。
 *
 * 功能：图片轮播、物品信息展示、借用按钮/借用历史查看、
 *        物主操作面板（下架/删除/修改）、用户评价入口。
 */
Page({
  data: {
    item: {},
    images: [],
    showSheet: false,
    showHistorySheet: false,
    historyData: {
      address: '',
      userType: '',
      rating: '5.0',
      borrowCount: 0,
      borrowReturnRate: 0,
      lendCount: 0,
      helpReqCount: 0,
      helpProCount: 0
    },
    today: '',
    userId: '',
    submittingBorrow: false,  // 借用申请提交中，用于防连点
    borrowForm: {
      description: '',
      durationUnit: 'day',
      durationOptions: [],
      durationIndex: 0
    }
  },

  onLoad(options) {
    if (!auth.ensureAccess()) return;   // 登录/审核门禁：未通过则已跳转
    const id = options.id;
    if (!id) {
      wx.showToast({ title: '参数错误', icon: 'none' });
      return;
    }
    // 设置选择器最小日期为今天
    const now = new Date();
    const today = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
    // 获取当前用户 id：优先 JWT，其次 storage 兜底
    const userId = auth.getUserId() || (wx.getStorageSync('userInfo') || {}).id || '';
    // 从服务通知"待回应"卡片进入时，强制禁用操作按钮
    const fromNotice = options.fromNotice || '';
    this.setData({ today, userId, fromNotice });
    this.loadItem(id);
  },

  onShow() {
    if (!auth.ensureAccess()) return; // 登录/审核门禁：覆盖 tab 切换与后台切回
  },

  loadItem(id) {
    wx.showLoading({ title: '加载中...' });
    api.get(`/api/idle-items/${id}`)
      .then((data) => {
        wx.hideLoading();
        const item = this.formatItem(data);
        // 从服务通知"待回应"卡片进入时，若后端未返回 userBorrowStatus，强制设为 pending 以禁用按钮
        if (this.data.fromNotice === 'pending' && !item.userBorrowStatus) {
          item.userBorrowStatus = BORROW_STATUS.PENDING;
        }
        const historyData = this.buildHistoryData(data);
        // 记录帖子的更新时间，用于操作前检测冲突
        const itemUpdatedAt = data.updatedAt || '';
        this.setData({ item, images: item.images || [], historyData, itemUpdatedAt });
      })
      .catch((err) => {
        wx.hideLoading();
        wx.showToast({ title: err.message, icon: 'none' });
      });
  },

  formatItem(item) {
    // 解析 images：JSON 字符串转数组，最多 4 张
    let imageList = [];
    if (item.images) {
      try {
        imageList = typeof item.images === 'string' ? JSON.parse(item.images) : item.images;
        if (!Array.isArray(imageList)) imageList = [];
        imageList = imageList.slice(0, 4);
      } catch (e) {
        imageList = [];
      }
    }
    // 为相对路径拼接 baseUrl 前缀
    const app = getApp();
    const baseUrl = (app && app.globalData && app.globalData.baseUrl) ? app.globalData.baseUrl : '';
    imageList = imageList.map(url => {
      if (url && url.startsWith('/uploads/') && baseUrl) {
        return baseUrl + url;
      }
      return url;
    });

    // 评分始终显示一位小数（如 5.0，而非 5）
    const ratingText = item.rating != null ? item.rating.toFixed(1) : null;

    const isWanted = item.postType === POST_TYPE.WANTED;

    return {
      ...item,
      images: imageList,
      isWanted: isWanted,
      durationLabel: this.formatDuration(item.maxDuration, item.durationUnit, isWanted),
      conditionLabel: isWanted ? null : this.formatCondition(item.condition),
      pickupLabel: isWanted ? null : this.formatPickupMethod(item.pickupMethod),
      timeAgo: this.formatTime(item.createdAt),
      priceText: item.price != null ? '¥' + item.price : '未设置',
      ratingText: ratingText,
      lendCount: item.lendCount != null ? item.lendCount : 0,
      returnRate: item.returnRate || '',
      userBorrowStatus: item.userBorrowStatus || null  // null | "pending" | "approved" | "returned" | "rejected"
    };
  },

  buildHistoryData(data) {
    // 解析 userRoom："1栋2单元301号(业主)" → 地址 + 住户类型
    const userRoom = data.userRoom || '';
    const typeMatch = userRoom.match(/\((.+)\)$/);
    const userType = typeMatch ? typeMatch[1] : '';
    const address = typeMatch ? userRoom.replace(/\(.+\)$/, '') : userRoom;

    const rating = data.rating != null ? data.rating.toFixed(1) : '5.0';
    const lendCount = data.lendCount != null ? data.lendCount : 0;
    const returnRate = data.returnRate ? parseInt(data.returnRate) || 0 : 100;

    return {
      address: address || userRoom || '—',
      userType: userType || '居民',
      rating: rating,
      // 五项统计由后端统一口径下发（已完成且被对方评价才计数），不再前端硬编码
      borrowCount: data.borrowCount != null ? data.borrowCount : 0,
      borrowReturnRate: returnRate,
      lendCount: lendCount,
      helpReqCount: data.helpCount != null ? data.helpCount : 0,
      helpProCount: data.helpedCount != null ? data.helpedCount : 0
    };
  },

  formatDuration(maxDuration, durationUnit, isWanted) {
    const prefix = isWanted ? '需借' : '可借';
    const operator = isWanted ? ' ≥' : ' ≤';
    if (!maxDuration) return prefix;
    const unitLabel = durationUnit === 'hour' ? '小时' : '天';
    return prefix + operator + maxDuration + unitLabel;
  },

  formatCondition(condition) {
    const map = {
      'like-new': '几乎全新',
      'normal': '正常使用痕迹',
      'good': '正常使用痕迹',
      'worn': '有明显磨损'
    };
    return map[condition] || condition || '';
  },

  formatPickupMethod(method) {
    const map = {
      'self_pickup': '需自提',
      'both': '自提 / 可送上门'
    };
    return map[method] || method || '';
  },

  formatTime(time) {
    if (!time) return '';
    const now = Date.now();
    const postTime = new Date(time).getTime();
    const diff = now - postTime;
    const minutes = Math.floor(diff / 60000);
    const hours = Math.floor(diff / 3600000);
    const days = Math.floor(diff / 86400000);

    if (minutes < 1) return '刚刚';
    if (minutes < 60) return minutes + '分钟前';
    if (hours < 24) return hours + '小时前';
    if (days < 7) return days + '天前';
    return '7天前';
  },

  onViewHistory() {
    this.setData({ showHistorySheet: true });
  },

  onCloseHistorySheet() {
    this.setData({ showHistorySheet: false });
  },

  /**
   * 返回手势/返回键处理（Android 返回键 + iOS 侧滑返回）：
   * 发布人信息弹层打开时先关闭弹层并拦截路由回退，避免弹层开着时返回手势直接 pop/退出小程序。
   */
  onContactTap() {
    const { item, userId } = this.data;

    // 防止 item 数据未就绪
    if (!item || !item.id || !item.userId) {
      wx.showToast({ title: '物品信息未就绪，请稍后重试', icon: 'none' });
      return;
    }

    // 防止与自己聊天
    if (userId && item.userId && String(userId) === String(item.userId)) {
      wx.showModal({
        title: '提示',
        content: '这是您自己发布的物品，无法联系自己',
        showCancel: false,
        confirmText: '知道了'
      });
      return;
    }

    // 直接跳转聊天页（会话 ID 按用户对生成，同一对用户始终共享同一会话）
    const otherUserId = item.userId;
    const ids = [userId, otherUserId].sort();
    const localSessionId = 'USER_' + ids[0] + '_' + ids[1];
    const name = encodeURIComponent(item.userRoom || item.userName || '用户');
    const about = encodeURIComponent(item.title || '');
    const room = encodeURIComponent(item.userRoom || '');
    wx.navigateTo({
      url: `/pages/chat/chat?sessionId=${localSessionId}&name=${name}&about=${about}&room=${room}&aboutId=${item.id}&aboutType=IDLE&otherUserId=${otherUserId}`
    });
  },

  onBorrowTap() {
    const { item, userId } = this.data;

    // 已被借出 / 已被预定 / 已申请 / 已通过 / 已归还 — 不可重复操作
    if (item.status === POST_STATUS.ACTIVE || item.status === POST_STATUS.PENDING ||
        item.userBorrowStatus === BORROW_STATUS.PENDING || item.userBorrowStatus === BORROW_STATUS.APPROVED ||
        item.userBorrowStatus === BORROW_STATUS.RETURNED) return;

    // 自己发布的物品/需求不可操作
    if (userId && item.userId && String(userId) === String(item.userId)) {
      wx.showModal({
        title: '提示',
        content: item.isWanted ? '这是您自己发布的需求，无法进行交互' : '这是您自己发布的闲置物品，无法进行交互',
        showCancel: false,
        confirmText: '知道了'
      });
      return;
    }

    // 操作前重新拉取帖子数据，检测是否在浏览期间被发布者更新
    this._checkItemFreshness(() => this._showBorrowSheet());
  },

  /** 重新拉取帖子，检测是否在浏览期间被发布者更新；通过则执行回调 */
  _checkItemFreshness(onFresh) {
    const id = this.data.item.id;
    if (!id) return;
    wx.showLoading({ title: '校验中...', mask: true });
    api.get(`/api/idle-items/${id}`)
      .then((data) => {
        wx.hideLoading();
        const newUpdatedAt = data.updatedAt || '';
        const oldUpdatedAt = this.data.itemUpdatedAt || '';
        // 帖子被更新或状态变更（不再是在线）→ 拦截
        if (newUpdatedAt !== oldUpdatedAt || data.status !== this.data.item.status) {
          wx.showModal({
            title: '帖子已更新',
            content: '帖子信息已被发布者修改，请重新确认相关信息',
            showCancel: false,
            confirmText: '知道了',
            success: () => {
              // 刷新页面数据
              const item = this.formatItem(data);
              if (this.data.fromNotice === 'pending' && !item.userBorrowStatus) {
                item.userBorrowStatus = BORROW_STATUS.PENDING;
              }
              const historyData = this.buildHistoryData(data);
              this.setData({
                item, images: item.images || [],
                historyData, itemUpdatedAt: newUpdatedAt
              });
            }
          });
          return;
        }
        // 未变更，执行原操作
        onFresh();
      })
      .catch((err) => {
        wx.hideLoading();
        wx.showToast({ title: err.message || '网络异常', icon: 'none' });
      });
  },

  /** 显示借入/借出申请弹层 */
  _showBorrowSheet() {
    const { item } = this.data;

    // 初始化表单：需求借入的借出意向使用默认时长范围（1~7天 / 1~23小时），
    // 不做特殊限制；闲置借出的借入申请沿用物品本身的时长设置
    const isWanted = item.isWanted;
    const unit = item.durationUnit || 'day';
    const maxDuration = isWanted ? (unit === 'hour' ? 23 : 7) : (item.maxDuration || 7);
    const durationOptions = this.buildDurationOptions(unit, maxDuration);
    // LEND 默认帖子的最大借出时长，WANTED 默认帖子的最小借入时长（均为 item.maxDuration）
    const durationIndex = isWanted
      ? Math.min(Math.max((item.maxDuration || 1) - 1, 0), durationOptions.length - 1)
      : (durationOptions.length - 1);

    this.setData({
      showSheet: true,
      borrowForm: {
        description: '',
        durationUnit: unit,
        durationOptions: durationOptions,
        durationIndex: durationIndex
      }
    });
  },

  // 根据单位和最大值生成时长选项（参照发布页 onUnitTap）
  buildDurationOptions(unit, max) {
    const options = [];
    if (unit === 'day') {
      const cap = Math.min(max, 30);
      for (let i = 1; i <= cap; i++) options.push(i + ' 天');
    } else {
      const cap = Math.min(max, 23);
      for (let i = 1; i <= cap; i++) options.push(i + ' 小时');
    }
    return options;
  },

  onCloseSheet() {
    this.setData({ showSheet: false });
  },

  // 切换时长单位（需求借入的借出意向使用默认范围，不做特殊限制）
  onBorrowUnitTap(e) {
    const unit = e.currentTarget.dataset.value;
    const isWanted = this.data.item.isWanted;
    let max;
    if (isWanted) {
      // 需求借入：借出意向使用默认范围 1~7天 / 1~23小时
      max = unit === 'day' ? 7 : 23;
    } else {
      const itemUnit = this.data.item.durationUnit || 'day';
      const itemMax = this.data.item.maxDuration || 7;
      if (unit === itemUnit) {
        max = itemMax;
      } else if (unit === 'hour') {
        max = Math.min(itemMax * 24, 24);
      } else {
        max = Math.max(1, Math.floor(itemMax / 24));
      }
    }
    const cap = unit === 'day' ? Math.min(max, 30) : Math.min(max, 23);
    const options = [];
    if (unit === 'day') {
      for (let i = 1; i <= cap; i++) options.push(i + ' 天');
    } else {
      for (let i = 1; i <= cap; i++) options.push(i + ' 小时');
    }
    this.setData({
      'borrowForm.durationUnit': unit,
      'borrowForm.durationOptions': options,
      'borrowForm.durationIndex': 0
    });
  },

  // 时长选择器变化（参照发布页 onDurationChange）
  onBorrowDurationChange(e) {
    this.setData({ 'borrowForm.durationIndex': e.detail.value });
  },

  // 说明输入
  onBorrowDescInput(e) {
    this.setData({ 'borrowForm.description': e.detail.value });
  },

  onSubmitBorrow() {
    // 防连点：提交中忽略后续点击（加载提示的遮罩在部分场景不足以保证拦截）
    if (this.data.submittingBorrow) return;
    const { borrowForm, item } = this.data;

    // 校验说明
    if (!borrowForm.description || !borrowForm.description.trim()) {
      wx.showToast({ title: item.isWanted ? '请填写借出说明' : '请填写借用说明', icon: 'none' });
      return;
    }

    // 解析选中时长
    const selected = borrowForm.durationOptions[borrowForm.durationIndex];
    const durationDays = parseInt(selected) || 1;

    this.setData({ submittingBorrow: true });
    wx.showLoading({ title: '提交中...', mask: true });

    api.post('/api/borrow-requests', {
      idleId: item.id,
      durationType: borrowForm.durationUnit,
      durationDays: durationDays,
      startDate: this.data.today,
      note: borrowForm.description.trim()
    })
      .then(() => {
        wx.hideLoading();
        this.setData({ showSheet: false, submittingBorrow: false });
        wx.showToast({ title: item.isWanted ? '借出意向已发送' : '借入申请已发送', icon: 'success' });
        // 本地更新物品状态（通过后端 userBorrowStatus 在重新进入时保持）
        this.setData({ 'item.status': POST_STATUS.PENDING, 'item.userBorrowStatus': BORROW_STATUS.PENDING });
      })
      .catch((err) => {
        wx.hideLoading();
        this.setData({ submittingBorrow: false });
        wx.showToast({ title: err.message, icon: 'none' });
        // 操作失败（如帖子已下架/已被别人申请），刷新数据同步最新状态
        this.loadItem(this.data.item.id);
      });
  },

  onViewProfile() {
    const { item } = this.data;
    if (item.userId) {
      wx.navigateTo({ url: `/pages/profile/profile?userId=${item.userId}` });
    }
  },

  preventTouchMove() {}
});
