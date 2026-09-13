const api = require('../../utils/api');
const auth = require('../../utils/auth');
const { ACTIVITY_ROLE, CONTENT_TYPE } = require('../../utils/constants');

/**
 * 个人中心页 — 用户信息展示 + 功能入口。
 *
 * 功能：用户头像/姓名/房号展示、评价统计、我的发布/审批/进行中/已完成入口、
 *        消息通知入口、设置入口、退出登录。
 */
Page({
  data: {
    // 业务常量（供 WXML 模板引用）
    CONTENT_TYPE: CONTENT_TYPE,

    statusBarHeight: 44,
    userId: '',
    userName: '',
    avatarText: '',
    roomInfo: '',
    isAuth: false,
    userTypeText: '业主',
    score: 0,
    ratingCount: 0,
    recordTab: CONTENT_TYPE.IDLE,
    idleRecords: [],
    helpRecords: [],
    // 来自后端的统计数据
    lendCount: 0,
    borrowCount: 0,
    borrowReturnRate: 0,
    helpReqCount: 0,
    helpProCount: 0,
    // Loading
    loading: true,

    // 退出登录弹窗
    showLogoutAlert: false,

    // 记录详情弹框
    showRecordSheet: false,       // 互借/互助记录底部弹框
    recordSheetType: '',          // 'idle' | 'help'
    recordSheetItem: null,        // 原始记录数据
    recordSheetParties: null,     // 双方格式化数据 { left: {...}, right: {...} }
  },

  onLoad() {
    if (!auth.ensureAccess()) return;   // 登录/审核门禁：未通过则已跳转
    const sysInfo = wx.getSystemInfoSync();
    this.setData({ statusBarHeight: sysInfo.statusBarHeight || 44 });
    this.loadProfile();
    this.loadRecords();
  },

  onShow() {
    if (!auth.ensureAccess()) return; // 登录/审核门禁：覆盖 tab 切换与后台切回
    const app = getApp();
    app._updateTabBarBadge();   // 切换到"我的"时刷新消息红点
    app.refreshNoticeBadge();   // 从服务端拉最新通知未读数，兜底 WS 推送丢失
    if (this.data.userId) {
      this.loadProfile();
      this.loadRecords();
    }
  },

  // ================================================================
  // 个人资料数据 — 来自 /api/users/profile（真实数据库数据）
  // ================================================================
  async loadProfile() {
    const token = wx.getStorageSync('token');
    if (!token) {
      this.setData({ loading: false, isAuth: false });
      return;
    }
    try {
      const data = await api.get('/api/users/profile');
      this.setData({
        loading: false,
        userId: data.id || '',
        userName: data.name || '用户',
        avatarText: (data.name || '用')[0],
        roomInfo: data.roomInfo || '未绑定',
        isAuth: data.isAuth || false,
        userTypeText: data.userTypeText || '业主',
        score: (data.score || 0).toFixed(1),
        ratingCount: data.ratingCount || 0,
        lendCount: data.lendCount || 0,
        borrowCount: data.borrowCount || 0,
        borrowReturnRate: data.borrowReturnRate != null ? data.borrowReturnRate : 100,
        helpReqCount: data.helpReqCount || 0,
        helpProCount: data.helpProCount || 0
      });
    } catch (e) {
      console.error('Load profile failed:', e);
      this.setData({ loading: false });
    }
  },

  // ================================================================
  // 记录 — 来自 /api/users/completed（真实数据库数据）
  // ================================================================
  async loadRecords() {
    // 互借记录: borrow + lend
    try {
      const [borrowList, lendList] = await Promise.all([
        api.get('/api/users/completed', { role: ACTIVITY_ROLE.BORROW }),
        api.get('/api/users/completed', { role: ACTIVITY_ROLE.LEND })
      ]);
      const allIdle = [
        ...(borrowList?.content || (Array.isArray(borrowList) ? borrowList : [])),
        ...(lendList?.content || (Array.isArray(lendList) ? lendList : []))
      ];
      // 按 completedAt 倒序排列
      allIdle.sort((a, b) => {
        const ta = a.completedAt ? new Date(a.completedAt).getTime() : 0;
        const tb = b.completedAt ? new Date(b.completedAt).getTime() : 0;
        return tb - ta;
      });
      const idleRecords = allIdle.map(item => ({
        id: item.id,
        title: item.title || '',
        type: item.subType || ACTIVITY_ROLE.LEND,
        typeText: item.subType === ACTIVITY_ROLE.BORROW ? '借入' : '借出',
        roleText: item.subType === ACTIVITY_ROLE.BORROW ? '借出者' : '借入者',
        peerName: item.personName || '',
        dateText: this.formatDate(item.completedAt),
        remark: item.myFeedback || item.theirFeedback || '—',
        // 保留原始数据供弹框使用
        _raw: item
      }));
      this.setData({ idleRecords });
    } catch (e) {
      console.error('Load idle records failed:', e);
    }

    // 互助记录: helpReq + helpPro
    try {
      const [helpReqList, helpProList] = await Promise.all([
        api.get('/api/users/completed', { role: ACTIVITY_ROLE.HELP_REQ }),
        api.get('/api/users/completed', { role: ACTIVITY_ROLE.HELP_PRO })
      ]);
      const allHelp = [
        ...(helpReqList?.content || (Array.isArray(helpReqList) ? helpReqList : [])),
        ...(helpProList?.content || (Array.isArray(helpProList) ? helpProList : []))
      ];
      // 按 completedAt 倒序排列
      allHelp.sort((a, b) => {
        const ta = a.completedAt ? new Date(a.completedAt).getTime() : 0;
        const tb = b.completedAt ? new Date(b.completedAt).getTime() : 0;
        return tb - ta;
      });
      const helpRecords = allHelp.map(item => ({
        id: item.id,
        title: item.title || '',
        type: item.subType || ACTIVITY_ROLE.HELP_REQ,
        typeText: item.subType === ACTIVITY_ROLE.HELP_REQ ? '求助' : '帮助',
        roleText: item.subType === ACTIVITY_ROLE.HELP_REQ ? '帮助者' : '求助者',
        peerName: item.personName || '',
        dateText: this.formatDate(item.completedAt),
        remark: item.myFeedback || item.theirFeedback || '—',
        // 保留原始数据供弹框使用
        _raw: item
      }));
      this.setData({ helpRecords });
    } catch (e) {
      console.error('Load help records failed:', e);
    }
  },

  // ================================================================
  // Tab 切换
  // ================================================================
  onRecordTabTap(e) {
    const tab = e.currentTarget.dataset.tab;
    this.setData({ recordTab: tab });
  },

  // ================================================================
  // 记录详情
  // ================================================================
  onOpenRecord(e) {
    const record = e.currentTarget.dataset.record;
    const raw = record._raw;
    if (!raw) return;
    const sheetType = record.type === ACTIVITY_ROLE.BORROW || record.type === ACTIVITY_ROLE.LEND
      ? CONTENT_TYPE.IDLE
      : CONTENT_TYPE.HELP;
    const roomInfo = this.data.roomInfo || '';
    const parties = sheetType === CONTENT_TYPE.IDLE
      ? this._buildIdleParties(raw, roomInfo)
      : this._buildHelpParties(raw, roomInfo);
    this.setData({
      showRecordSheet: true,
      recordSheetType: sheetType,
      recordSheetItem: raw,
      recordSheetParties: parties
    });
  },

  onCloseRecordSheet() {
    this.setData({ showRecordSheet: false, recordSheetItem: null, recordSheetParties: null });
  },

  /** 点击弹框中的住户地址 → 跳转与对方聊天 */
  onTapChatWithUser(e) {
    const userId = e.currentTarget.dataset.userId;
    const userName = e.currentTarget.dataset.userName || '';
    if (!userId) return;
    const myId = auth.getUserId();
    if (!myId || String(myId) === String(userId)) {
      wx.showToast({ title: '不能与自己聊天', icon: 'none' });
      return;
    }
    const ids = [String(myId), String(userId)].sort();
    const sessionId = 'USER_' + ids[0] + '_' + ids[1];
    const name = encodeURIComponent(userName || '用户');
    wx.navigateTo({
      url: `/pages/chat/chat?sessionId=${sessionId}&name=${name}&room=&about=&aboutId=&aboutType=&otherUserId=${userId}`
    });
  },

  /** 互借记录：构建借出方/借入方双方数据 */
  _buildIdleParties(raw, myRoom) {
    const isBorrow = raw.subType === ACTIVITY_ROLE.BORROW;
    const otherId = raw.personId || null;
    // 在 JS 中预格式化评分为字符串，避免 WXML 复杂表达式兼容问题
    const fmtRating = (v) => (v != null ? Number(v).toFixed(1) : null);
    return {
      left: {
        label: '借出方',
        name: isBorrow ? (raw.personName || '') : myRoom,
        personId: isBorrow ? otherId : null,
        rating: isBorrow ? raw.myRating : raw.theirRating,
        ratingText: fmtRating(isBorrow ? raw.myRating : raw.theirRating),
        feedback: isBorrow ? (raw.theirFeedback || '') : (raw.myFeedback || '')
      },
      right: {
        label: '借入方',
        name: isBorrow ? myRoom : (raw.personName || ''),
        personId: isBorrow ? null : otherId,
        rating: isBorrow ? raw.theirRating : raw.myRating,
        ratingText: fmtRating(isBorrow ? raw.theirRating : raw.myRating),
        feedback: isBorrow ? (raw.myFeedback || '') : (raw.theirFeedback || '')
      }
    };
  },

  /** 互助记录：构建帮助方/求助方双方数据 */
  _buildHelpParties(raw, myRoom) {
    const isReq = raw.subType === ACTIVITY_ROLE.HELP_REQ;
    const otherId = raw.personId || null;
    const fmtRating = (v) => (v != null ? Number(v).toFixed(1) : null);
    return {
      left: {
        label: '帮助方',
        name: isReq ? (raw.personName || '') : myRoom,
        personId: isReq ? otherId : null,
        rating: isReq ? raw.myRating : raw.theirRating,
        ratingText: fmtRating(isReq ? raw.myRating : raw.theirRating),
        feedback: isReq ? (raw.theirFeedback || '') : (raw.myFeedback || '')
      },
      right: {
        label: '求助方',
        name: isReq ? myRoom : (raw.personName || ''),
        personId: isReq ? null : otherId,
        rating: isReq ? raw.theirRating : raw.myRating,
        ratingText: fmtRating(isReq ? raw.theirRating : raw.myRating),
        feedback: isReq ? (raw.myFeedback || '') : (raw.theirFeedback || '')
      }
    };
  },

  // ================================================================
  // 退出登录
  // ================================================================
  onLogoutTap() {
    this.setData({ showLogoutAlert: true });
  },

  onCloseLogoutAlert() {
    this.setData({ showLogoutAlert: false });
  },

  onCancelLogout() {
    this.setData({ showLogoutAlert: false });
  },

  preventTouchMove() {},

  onDoLogout() {
    this.setData({ showLogoutAlert: false });
    wx.removeStorageSync('token');
    wx.removeStorageSync('userInfo');
    wx.removeStorageSync('userId');
    wx.showToast({ title: '已退出登录', icon: 'none' });
    setTimeout(() => {
      wx.reLaunch({ url: '/pages/login/login' });
    }, 600);
  },

  // ================================================================
  // 辅助函数
  // ================================================================
  formatDate(timestamp) {
    if (!timestamp) return '';
    const date = new Date(timestamp);
    const Y = date.getFullYear();
    const M = (date.getMonth() + 1).toString().padStart(2, '0');
    const d = date.getDate().toString().padStart(2, '0');
    return Y + '-' + M + '-' + d;
  },

  formatTime(timestamp) {
    if (!timestamp) return '';
    const date = new Date(timestamp);
    const M = (date.getMonth() + 1).toString().padStart(2, '0');
    const d = date.getDate().toString().padStart(2, '0');
    const h = date.getHours().toString().padStart(2, '0');
    const mi = date.getMinutes().toString().padStart(2, '0');
    return M + '-' + d + ' ' + h + ':' + mi;
  }
});
