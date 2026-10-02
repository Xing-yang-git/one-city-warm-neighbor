const api = require('../../utils/api');
const auth = require('../../utils/auth');
const { POST_TYPE, HELP_APPLICATION_STATUS } = require('../../utils/constants');

/**
 * 求助详情页 — 求助信息展示 + 接单操作入口。
 *
 * 功能：求助信息展示、接单按钮/接单历史查看、
 *        发布者操作面板（下架/删除/修改）、完成确认、用户评价入口。
 */
Page({
  data: {
    item: {},
    images: [],
    showSheet: false,
    showHistorySheet: false,
    historyData: {},
    helpNote: '',
    hasApplied: false,      // 当前用户是否已申请帮忙（禁用按钮）
    submittingHelp: false   // 提交中防重复点击
  },

  onLoad(options) {
    if (!auth.ensureAccess()) return;   // 登录/审核门禁：未通过则已跳转
    const id = options.id;
    if (!id) {
      wx.showToast({ title: '参数错误', icon: 'none' });
      return;
    }
    // 从服务通知"待回应"卡片进入时，按钮应禁用
    const fromNotice = options.fromNotice || '';
    if (fromNotice === 'pending') {
      this.setData({ hasApplied: true });
    }
    this.loadItem(id);
  },

  onShow() {
    if (!auth.ensureAccess()) return; // 登录/审核门禁：覆盖 tab 切换与后台切回
  },

  loadItem(id) {
    wx.showLoading({ title: '加载中...' });
    api.get(`/api/help-requests/${id}`)
      .then((data) => {
        wx.hideLoading();
        const item = this.formatItem(data);
        const historyData = this.buildHistoryData(data);
        // 记录帖子的更新时间，用于操作前检测冲突
        const itemUpdatedAt = data.updatedAt || '';
        // 按钮禁用状态由后端返回的本用户申请状态决定，重新进入页面后仍然生效
        this.setData({
          item,
          images: item.images || [],
          historyData,
          itemUpdatedAt,
          hasApplied: this._isApplyBlocked(item.userApplyStatus)
        });
      })
      .catch((err) => {
        wx.hideLoading();
        wx.showToast({ title: err.message, icon: 'none' });
      });
  },

  formatItem(item) {
    // 解析 images（JSON 字符串 → 数组），相对路径补全 baseUrl，与 idle-detail 一致
    let imageList = [];
    if (item.images) {
      try {
        imageList = typeof item.images === 'string' ? JSON.parse(item.images) : item.images;
        if (!Array.isArray(imageList)) imageList = [];
        imageList = imageList.slice(0, 9);
      } catch (e) {
        imageList = [];
      }
    }
    const app = getApp();
    const baseUrl = (app && app.globalData && app.globalData.baseUrl) ? app.globalData.baseUrl : '';
    imageList = imageList.map(url => {
      if (url && url.startsWith('/uploads/') && baseUrl) {
        return baseUrl + url;
      }
      return url;
    });

    return {
      ...item,
      images: imageList,
      timeAgo: this.formatTime(item.createdAt),
      // 评分格式对齐 idle-detail：始终一位小数（如 5.0）
      ratingText: item.rating != null ? item.rating.toFixed(1) : null,
      // 预计时间范围显示文本
      timeRangeText: item.timeStart && item.timeEnd
        ? this.formatDateTime(item.timeStart) + ' ~ ' + this.formatDateTime(item.timeEnd)
        : ''
    };
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

  // 将 ISO 日期时间字符串格式化为 "M/d HH:mm" 展示格式
  formatDateTime(dt) {
    if (!dt) return '';
    const d = new Date(dt);
    if (isNaN(d.getTime())) return '';
    const M = d.getMonth() + 1;
    const DD = d.getDate();
    const h = d.getHours().toString().padStart(2, '0');
    const mi = d.getMinutes().toString().padStart(2, '0');
    return M + '/' + DD + ' ' + h + ':' + mi;
  },


  onContactTap() {
    const { item } = this.data;
    const userId = auth.getUserId() || (wx.getStorageSync('userInfo') || {}).id || '';

    // 防止与自己聊天
    if (userId && item.userId && String(userId) === String(item.userId)) {
      wx.showModal({
        title: '提示',
        content: '这是您自己发布的求助，无法联系自己',
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
      url: `/pages/chat/chat?sessionId=${localSessionId}&name=${name}&about=${about}&room=${room}&aboutId=${item.id}&aboutType=${POST_TYPE.HELP}&otherUserId=${otherUserId}`
    });
  },

  onHelpTap() {
    const { item } = this.data;
    const userId = auth.getUserId() || (wx.getStorageSync('userInfo') || {}).id || '';

    // 自己发布的求助不可操作
    if (userId && item.userId && String(userId) === String(item.userId)) {
      wx.showModal({
        title: '提示',
        content: '这是您自己发布的求助，无法进行交互',
        showCancel: false,
        confirmText: '知道了'
      });
      return;
    }

    // 操作前重新拉取，检测浏览期间帖子是否被发布者更新
    this._checkHelpFreshness(() => {
      this.setData({ showSheet: true, helpNote: '' });
    });
  },

  /**
   * 判断「我来帮忙」按钮是否应禁用。
   * 已申请/已通过/已完成均禁用；已拒绝不禁用——被拒绝后允许重新申请，
   * 与后端返回的 userApplyStatus 口径一致。
   * @param {string} userApplyStatus 后端返回的当前用户申请状态
   * @returns {boolean} true 表示应禁用按钮
   */
  _isApplyBlocked(userApplyStatus) {
    return userApplyStatus === HELP_APPLICATION_STATUS.PENDING
      || userApplyStatus === HELP_APPLICATION_STATUS.APPROVED
      || userApplyStatus === HELP_APPLICATION_STATUS.COMPLETED;
  },

  /** 重新拉取求助帖，检测是否在浏览期间被发布者更新；通过则执行回调 */
  _checkHelpFreshness(onFresh) {
    const id = this.data.item.id;
    if (!id) return;
    wx.showLoading({ title: '校验中...', mask: true });
    api.get(`/api/help-requests/${id}`)
      .then((data) => {
        wx.hideLoading();
        const newUpdatedAt = data.updatedAt || '';
        const oldUpdatedAt = this.data.itemUpdatedAt || '';
        if (newUpdatedAt !== oldUpdatedAt || data.status !== this.data.item.status) {
          wx.showModal({
            title: '帖子已更新',
            content: '帖子信息已被发布者修改，请重新确认相关信息',
            showCancel: false,
            confirmText: '知道了',
            success: () => {
              const item = this.formatItem(data);
              const historyData = this.buildHistoryData(data);
              // 刷新后按钮状态由最新数据里的申请状态决定，不作无条件重置
              this.setData({
                item,
                images: item.images || [],
                historyData,
                itemUpdatedAt: newUpdatedAt,
                hasApplied: this._isApplyBlocked(item.userApplyStatus)
              });
            }
          });
          return;
        }
        onFresh();
      })
      .catch((err) => {
        wx.hideLoading();
        wx.showToast({ title: err.message || '网络异常', icon: 'none' });
      });
  },

  onCloseSheet() {
    this.setData({ showSheet: false, helpNote: '' });
  },

  onHelpNoteInput(e) {
    this.setData({ helpNote: e.detail.value });
  },

  onSubmitHelp() {
    if (this.data.submittingHelp || this.data.hasApplied) return;
    const { item, helpNote } = this.data;

    this.setData({ submittingHelp: true });
    wx.showLoading({ title: '提交中...' });

    api.post(`/api/help-requests/${item.id}/apply`, { note: helpNote })
      .then(() => {
        wx.hideLoading();
        // 申请成功：关闭弹层、禁用「我来帮忙」按钮，不自动跳转聊天（需联系可点“联系他”）
        this.setData({ showSheet: false, helpNote: '', hasApplied: true, submittingHelp: false });
        wx.showToast({ title: '已申请，等待对方确认', icon: 'success' });
      })
      .catch((err) => {
        wx.hideLoading();
        this.setData({ submittingHelp: false });
        wx.showToast({ title: err.message, icon: 'none' });
        // 操作失败（如帖子已下架/已被别人申请），刷新数据
        this.loadItem(this.data.item.id);
      });
  },

  onViewProfile() {
    const { item } = this.data;
    if (item.userId) {
      wx.navigateTo({ url: `/pages/profile/profile?userId=${item.userId}` });
    }
  },

  /* ===== 以往记录弹层（仿 idle-detail） ===== */

  buildHistoryData(data) {
    // 解析 userRoom: "1栋2单元301号(业主)" → address + userType
    const userRoom = data.userRoom || '';
    const typeMatch = userRoom.match(/\((.+)\)$/);
    const userType = typeMatch ? typeMatch[1] : '';
    const address = typeMatch ? userRoom.replace(/\(.+\)$/, '') : userRoom;

    const rating = data.rating != null ? data.rating.toFixed(1) : '5.0';

    return {
      address: address || userRoom || '—',
      userType: userType || '居民',
      rating: rating,
      borrowCount: data.borrowCount || 0,
      borrowReturnRate: data.returnRate ? parseInt(data.returnRate) || 0 : 100,
      lendCount: data.lendCount || 0,
      helpReqCount: data.helpCount || 0,
      helpProCount: data.helpedCount || 0
    };
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
  preventTouchMove() {
    // 阻止遮罩层上的触摸事件穿透
  }
});
