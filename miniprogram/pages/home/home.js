const api = require('../../utils/api');
const auth = require('../../utils/auth');
const { POST_TYPE, STORAGE_KEY, ITEM_CONDITION } = require('../../utils/constants');
const app = getApp();

// 将分类关键词映射到原型风格的 feather 图标
const IDLE_ICONS = [
  'icon-wrench.svg',
  'icon-book.svg',
  'icon-bike.svg',
  'icon-gamepad.svg',
  'icon-heart.svg',
  'icon-monitor.svg',
];

const HELP_ICONS = [
  'icon-wrench.svg',
  'icon-users.svg',
  'icon-download.svg',
  'icon-monitor.svg',
  'icon-book.svg',
  'icon-file-text.svg',
  'icon-heart.svg',
];

function pickIcon(item, index, iconList) {
  // 优先用 item.category 匹配图标，否则按索引轮换（与原型 bgColor 轮换规则一致）
  if (item.category) {
    const cat = item.category;
    if (cat.includes('维修') || cat.includes('修理')) return 'icon-wrench.svg';
    if (cat.includes('书籍') || cat.includes('书') || cat.includes('辅导')) return 'icon-book.svg';
    if (cat.includes('运动') || cat.includes('健身')) return 'icon-bike.svg';
    if (cat.includes('玩具') || cat.includes('游戏')) return 'icon-gamepad.svg';
    if (cat.includes('陪护') || cat.includes('陪伴')) return 'icon-users.svg';
    if (cat.includes('代取') || cat.includes('搬运') || cat.includes('快递')) return 'icon-download.svg';
    if (cat.includes('电脑') || cat.includes('电子') || cat.includes('IT')) return 'icon-monitor.svg';
    if (cat.includes('烹饪') || cat.includes('遛宠')) return 'icon-heart.svg';
  }
  return iconList[index % iconList.length];
}

/**
 * 首页 — 闲置物品/互助求助双 Tab 瀑布流浏览。
 *
 * 功能：LEND/WANTED/HELP 三类型切换、下拉刷新、滚动分页、分类图标映射。
 * 数据缓存：页面级 data 维护列表，避免重复请求。
 */
Page({
  data: {
    communityName: '社区互助闲置',
    currentTab: 0,
    idleList: [],
    helpList: [],
    page: 0,
    hasMore: true,
    loading: false,
    refreshing: false,
    fabOpen: false,
    listPaddingTop: 100,
    /** + 悬浮按钮位置（px，可长按拖动并记忆） */
    fabX: 0,
    fabY: 0,
    /** fab 菜单位置（跟随 fab，上方左对齐并钳制屏内） */
    fabMenuLeft: 0,
    fabMenuTop: 0,
    windowWidth: 375
  },

  onLoad() {
    if (!auth.ensureAccess()) return;   // 登录/审核门禁：未通过则已跳转
    app.ensureWebSocket(); // 确保全局 WS 已连接（登录后第一时间建立）
    // + 悬浮按钮初始位置：优先记忆值（钳制边界内），否则默认右下角（50=fab 100rpx，16=right 32rpx，120=bottom 240rpx）
    // 拖动边界：X 限制屏内回弹；Y 上不超固定头部（约 100px）、下不超 tabBar（安全区外 50px）
    const sysInfo = wx.getSystemInfoSync();
    const winH = sysInfo.windowHeight;
    const winW = sysInfo.windowWidth;
    const tabBarHeight = (sysInfo.screenHeight - sysInfo.safeArea.bottom) + 50;
    this._fabMinX = 8;
    this._fabMaxX = winW - 58;
    this._fabMinY = 108;
    this._fabMaxY = winH - tabBarHeight - 58;
    const saved = wx.getStorageSync(STORAGE_KEY.HOME_FAB_POS);
    const defX = winW - 66;
    const defY = winH - 170;
    this.setData({
      windowWidth: winW,
      fabX: Math.min(this._fabMaxX, Math.max(this._fabMinX, (saved && typeof saved.x === 'number') ? saved.x : defX)),
      fabY: Math.min(this._fabMaxY, Math.max(this._fabMinY, (saved && typeof saved.y === 'number') ? saved.y : defY))
    });
    // 计算导航栏偏移 + scroll-view 高度
    this.calcLayoutHeights();
    // 获取用户小区名称，优先级：后端 tenantName > 注册时存储 > 兜底值
    try {
      const userInfo = wx.getStorageSync('userInfo');
      if (userInfo && userInfo.tenantName) {
        this.setData({ communityName: userInfo.tenantName });
      } else {
        const communityName = wx.getStorageSync('communityName');
        if (communityName) {
          this.setData({ communityName: communityName });
        } else if (userInfo && userInfo.userRoom) {
          this.setData({ communityName: userInfo.userRoom });
        }
      }
    } catch (e) {
      // 兜底使用默认值
    }
    this._firstShow = true;
    this.loadData();
  },

  onShow() {
    if (!auth.ensureAccess()) return; // 登录/审核门禁：覆盖 tab 切换与后台切回
    app.ensureWebSocket(); // 从后台切回或 tab 切换时确保 WS 连接
    app._updateTabBarBadge(); // 切回 tab 页时刷新消息红点
    app.refreshManageBadge(); // 刷新「管理」tab 待审批红点
    app.refreshNoticeBadge(); // 从服务端拉最新通知未读数，兜底 WS 推送丢失

    // 首次 onShow 与 onLoad 成对触发，onLoad 已调用 loadData，跳过
    if (this._firstShow) {
      this._firstShow = false;
      return;
    }

    // 判断是否需要刷新当前 tab（直接使用模块级 app，函数内重复声明会因 const 提升遮蔽外层导致 undefined）
    const pendingRefresh = app.globalData.pendingHomeRefresh;
    let shouldRefresh = true;

    if (pendingRefresh) {
      // 从发布页返回：仅当发布的类型匹配当前 tab 时才刷新
      // 不匹配时无需刷新——用户切到对应 tab 时 onTabChange 会自动加载
      const refreshTab = pendingRefresh === POST_TYPE.HELP ? 2 : (pendingRefresh === POST_TYPE.WANTED ? 1 : 0);
      shouldRefresh = (refreshTab === this.data.currentTab);
      delete app.globalData.pendingHomeRefresh;
    }
    // 非发布页返回（tab 切换等）：始终刷新

    if (shouldRefresh) {
      this.setData({ page: 0, idleList: [], helpList: [], hasMore: true });
      this.loadData();
    }
  },

  onPullDownRefresh() {
    this.setData({ refreshing: true, page: 0, idleList: [], helpList: [], hasMore: true });
    this.loadData().finally(() => {
      this.setData({ refreshing: false });
      wx.stopPullDownRefresh();
    });
  },

  /**
   * 渲染后测量 .home-top-fixed 实际高度，设为 listPaddingTop，
   * 使滚动区域从固定头部下方开始。所有值单位为 px。
   */
  calcLayoutHeights() {
    const self = this;
    setTimeout(() => {
      wx.createSelectorQuery()
        .select('.home-top-fixed')
        .boundingClientRect()
        .exec((res) => {
          if (res[0] && res[0].height > 0) {
            self.setData({ listPaddingTop: Math.ceil(res[0].height) });
          }
        });
    }, 200);
  },

  onTabChange(e) {
    const tab = parseInt(e.currentTarget.dataset.tab);
    if (tab === this.data.currentTab) return;
    this.setData({ currentTab: tab, page: 0, idleList: [], helpList: [], hasMore: true });
    this.loadData();
  },

  loadData() {
    if (this.data.loading || !this.data.hasMore) return Promise.resolve();

    this.setData({ loading: true });
    // 下拉刷新时跳过居中 loading——原生下拉头已提供视觉反馈，避免双指示器闪烁
    if (!this.data.refreshing) {
      wx.showLoading({ title: '加载中...' });
    }

    let promise;
    if (this.data.currentTab === 2) {
      promise = this.loadHelpList();
    } else {
      const postType = this.data.currentTab === 0 ? POST_TYPE.LEND : POST_TYPE.WANTED;
      promise = this.loadIdleList(postType);
    }

    return promise.finally(() => {
      if (!this.data.refreshing) {
        wx.hideLoading();
      }
      this.setData({ loading: false });
    });
  },

  loadIdleList(postType) {
    const isLend = postType === POST_TYPE.LEND;
    return api.get('/api/idle-items/home', { postType, page: this.data.page })
      .then((data) => {
        const newList = data.content || data.list || data.records || [];
        const bgColors = ['#E8F0FE', '#FFF3E0', '#E8F5E9', '#FCE4EC', '#E3F2FD', '#F3E5F5'];
        const items = newList.map((item, index) => ({
          ...item,
          bgColor: bgColors[index % bgColors.length],
          icon: pickIcon(item, index, IDLE_ICONS),
          durationLabel: this.formatDuration(item.maxDuration, item.durationUnit, isLend ? '可借' : '需借'),
          conditionLabel: isLend ? this.formatCondition(item.condition) : null,
          timeAgo: this.formatTime(item.createTime || item.createdAt)
        }));
        this.setData({
          idleList: this.data.page === 1 ? items : this.data.idleList.concat(items),
          hasMore: newList.length >= 10,
          page: this.data.page + 1
        });
      })
      .catch((err) => {
        wx.showToast({ title: err.message, icon: 'none' });
      });
  },

  formatDuration(maxDuration, durationUnit, prefix) {
    if (!maxDuration) return prefix;
    const unitLabel = durationUnit === 'hour' ? '小时' : '天';
    return prefix + maxDuration + unitLabel;
  },

  formatCondition(condition) {
    const map = {
      [ITEM_CONDITION.LIKE_NEW]: '几乎全新',
      [ITEM_CONDITION.NORMAL]: '正常使用痕迹',
      good: '正常使用痕迹',   // 历史遗留取值（无写入方），仅作展示兜底
      [ITEM_CONDITION.WORN]: '有明显磨损'
    };
    return map[condition] || condition || '';
  },

  loadHelpList() {
    return api.get('/api/help-requests/home', { page: this.data.page })
      .then((data) => {
        const newList = data.content || data.list || data.records || [];
        const bgColors = ['#FFEDED', '#FFF3E0', '#E3F2FD', '#F3E5F5', '#E8F5E9'];
        const items = newList.map((item, index) => ({
          ...item,
          bgColor: bgColors[index % bgColors.length],
          icon: pickIcon(item, index, HELP_ICONS),
          timeAgo: this.formatTime(item.createTime || item.createdAt)
        }));
        this.setData({
          helpList: this.data.page === 1 ? items : this.data.helpList.concat(items),
          hasMore: newList.length >= 10,
          page: this.data.page + 1
        });
      })
      .catch((err) => {
        wx.showToast({ title: err.message, icon: 'none' });
      });
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
    if (minutes < 60) return `${minutes}分钟前`;
    if (hours < 24) return `${hours}小时前`;
    if (days < 7) return `${days}天前`;
    return '7天前';
  },

  onCardTap(e) {
    const item = e.currentTarget.dataset.item;
    wx.navigateTo({ url: `/pages/idle-detail/idle-detail?id=${item.id}` });
  },

  onHelpCardTap(e) {
    const item = e.currentTarget.dataset.item;
    wx.navigateTo({ url: `/pages/help-detail/help-detail?id=${item.id}` });
  },

  onSearchTap() {
    wx.navigateTo({ url: '/pages/search/search' });
  },

  /** + 悬浮按钮拖动开始 */
  onFabTouchStart(e) {
    const t = e.touches[0];
    this._fabDrag = { startX: t.clientX, startY: t.clientY, baseX: this.data.fabX, baseY: this.data.fabY, moved: false };
  },

  /** 拖动中：实时更新位置并钳制边界（左右屏内回弹、上不超列表区、下不超 tabBar） */
  onFabTouchMove(e) {
    if (!this._fabDrag) return;
    const t = e.touches[0];
    const dx = t.clientX - this._fabDrag.startX;
    const dy = t.clientY - this._fabDrag.startY;
    if (Math.abs(dx) > 4 || Math.abs(dy) > 4) this._fabDrag.moved = true;
    const nextX = Math.min(this._fabMaxX, Math.max(this._fabMinX, this._fabDrag.baseX + dx));
    const nextY = Math.min(this._fabMaxY, Math.max(this._fabMinY, this._fabDrag.baseY + dy));
    this.setData({ fabX: nextX, fabY: nextY });
  },

  /** 拖动结束：移动则记忆位置；未移动视为轻点 → 展开/收起发布菜单 */
  onFabTouchEnd() {
    if (!this._fabDrag) return;
    const dragged = this._fabDrag.moved;
    if (dragged) {
      try {
        wx.setStorageSync(STORAGE_KEY.HOME_FAB_POS, { x: this.data.fabX, y: this.data.fabY });
      } catch (e) { /* 存储失败不阻断 */ }
    }
    this._fabDrag = null;
    if (!dragged) this.onFabTap();
  },

  /** 点击 + 按钮：展开/收起发布菜单 */
  onFabTap() {
    if (!this.data.fabOpen) {
      // 计算菜单位置：fab 上方左对齐，钳制在屏内（估算菜单宽 140px / 高 150px + 12px 间隙）
      const left = Math.max(8, Math.min(this.data.fabX, this.data.windowWidth - 140 - 8));
      const top = Math.max(8, this.data.fabY - 162);
      this.setData({ fabMenuLeft: left, fabMenuTop: top });
    }
    this.setData({ fabOpen: !this.data.fabOpen });
  },

  onCloseFab() {
    this.setData({ fabOpen: false });
  },

  /**
   * 返回手势/返回键处理（Android 返回键 + iOS 侧滑返回）：
   * 发布菜单打开时先关闭菜单并拦截路由回退——本页为 tabBar 根页面，
   * 菜单开着时返回手势会被当成根页面返回直接退出小程序。
   */
  onPublishIdle() {
    this.setData({ fabOpen: false });
    wx.navigateTo({ url: `/pages/publish-idle/publish-idle?type=${POST_TYPE.LEND}` });
  },

  onPublishBorrow() {
    this.setData({ fabOpen: false });
    wx.navigateTo({ url: `/pages/publish-idle/publish-idle?type=${POST_TYPE.WANTED}` });
  },

  onPublishHelp() {
    this.setData({ fabOpen: false });
    wx.navigateTo({ url: `/pages/publish-idle/publish-idle?type=${POST_TYPE.HELP}` });
  }
});
