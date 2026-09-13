const api = require('../../utils/api');
const auth = require('../../utils/auth');
const { POST_TYPE, CONTENT_TYPE } = require('../../utils/constants');

/**
 * 搜索页 — 闲置物品/互助求助关键词搜索。
 *
 * 功能：关键词输入搜索、搜索历史记录、LEND/WANTED/HELP 类型切换、
 *        搜索结果列表（租户隔离）、分页加载。
 */
Page({
  data: {
    // 业务常量（供 WXML 模板引用）
    CONTENT_TYPE: CONTENT_TYPE,

    keyword: '',
    autoFocus: true,
    history: [],
    searching: false,
    currentFilter: 'all',
    idleResults: [],
    helpResults: [],
    filteredResults: [],
    searchKeyword: ''
  },

  onLoad() {
    if (!auth.ensureAccess()) return;   // 登录/审核门禁：未通过则已跳转
    this.loadHistory();
  },

  onShow() {
    if (!auth.ensureAccess()) return; // 登录/审核门禁：覆盖 tab 切换与后台切回
  },

  loadHistory() {
    const history = wx.getStorageSync('searchHistory') || [];
    this.setData({ history });
  },

  saveHistory(keyword) {
    if (!keyword.trim()) return;
    let history = this.data.history.filter(h => h !== keyword);
    history.unshift(keyword);
    if (history.length > 5) history = history.slice(0, 5);
    wx.setStorageSync('searchHistory', history);
    this.setData({ history });
  },

  onKeywordInput(e) {
    const raw = e.detail.value;
    // 去除首尾空格，合并连续空格
    const keyword = raw.trim().replace(/\s+/g, ' ');
    this.setData({ keyword });

    // 清空节流计时器
    if (this._searchTimer) { clearTimeout(this._searchTimer); this._searchTimer = null; }

    // 输入为空时清除搜索结果
    if (!keyword) {
      this.setData({ searching: false, filteredResults: [] });
      return;
    }

    // 纯无意义字符（仅有标点/特殊符号，不含中文英文数字）不触发搜索
    if (!/[a-zA-Z0-9一-龥]/.test(keyword)) {
      return;
    }

    // 1s 节流后实时搜索
    this._searchTimer = setTimeout(() => {
      this.onSearch();
    }, 1000);
  },

  onClearKeyword() {
    if (this._searchTimer) { clearTimeout(this._searchTimer); this._searchTimer = null; }
    this._searchSeq = (this._searchSeq || 0) + 1; // 使进行中的请求失活
    this.setData({ keyword: '', searching: false, filteredResults: [] });
  },

  onSearch() {
    const keyword = this.data.keyword.trim();
    if (!keyword) return;

    // 取消排队中的输入节流
    if (this._searchTimer) { clearTimeout(this._searchTimer); this._searchTimer = null; }

    // 递增序列号，忽略旧请求的响应
    const seq = (this._searchSeq || 0) + 1;
    this._searchSeq = seq;

    this.saveHistory(keyword);
    this.setData({ searching: true, searchKeyword: keyword });

    wx.showLoading({ title: '搜索中...' });

    const bgColorsIdle = ['#E8F0FE', '#FFF3E0', '#E8F5E9', '#FCE4EC', '#E3F2FD'];
    const bgColorsHelp = ['#FFEDED', '#FFF3E0', '#E3F2FD', '#F3E5F5'];

    Promise.all([
      api.get('/api/idle-items/search', { keyword, postType: POST_TYPE.LEND, page: 0, size: 20 }).catch(() => ({ content: [] })),
      api.get('/api/idle-items/search', { keyword, postType: POST_TYPE.WANTED, page: 0, size: 20 }).catch(() => ({ content: [] })),
      api.get('/api/help-requests/search', { keyword, page: 0, size: 20 }).catch(() => ({ content: [] }))
    ]).then(([lendData, wantedData, helpData]) => {
      // 忽略过期响应：序列号不匹配说明有更新的搜索已发起
      if (this._searchSeq !== seq) return;

      wx.hideLoading();

      const lendList = (lendData.content || lendData.list || lendData.records || []).map((item, i) => ({
        ...item,
        type: CONTENT_TYPE.IDLE,
        bgColor: bgColorsIdle[i % bgColorsIdle.length],
        subtitle: `闲置借出 · ${item.userRoom || ''}`,
        titleSegments: this.highlightKeyword(item.title, keyword)
      }));

      const wantedList = (wantedData.content || wantedData.list || wantedData.records || []).map((item, i) => ({
        ...item,
        type: CONTENT_TYPE.IDLE,
        bgColor: bgColorsIdle[(i + lendList.length) % bgColorsIdle.length],
        subtitle: `需求借入 · ${item.userRoom || ''}`,
        titleSegments: this.highlightKeyword(item.title, keyword)
      }));

      const idleList = [...lendList, ...wantedList];

      const helpList = (helpData.content || helpData.list || helpData.records || []).map((item, i) => ({
        ...item,
        type: CONTENT_TYPE.HELP,
        bgColor: bgColorsHelp[i % bgColorsHelp.length],
        subtitle: `求助 · ${item.userRoom || ''}${item.isUrgent ? ' · 紧急' : ''}`,
        titleSegments: this.highlightKeyword(item.title, keyword)
      }));

      this.setData({
        idleResults: idleList,
        helpResults: helpList,
        currentFilter: 'all'
      });
      this.applyFilter();
    }).catch((err) => {
      if (this._searchSeq !== seq) return;
      wx.hideLoading();
      wx.showToast({ title: err.message || '搜索失败', icon: 'none' });
    });
  },

  onQuickSearch(e) {
    const keyword = e.currentTarget.dataset.keyword;
    this.setData({ keyword });
    this.onSearch();
  },

  onFilterChange(e) {
    this.setData({ currentFilter: e.currentTarget.dataset.filter });
    this.applyFilter();
  },

  applyFilter() {
    const { currentFilter, idleResults, helpResults } = this.data;
    let filtered;
    if (currentFilter === CONTENT_TYPE.IDLE) {
      filtered = idleResults;
    } else if (currentFilter === CONTENT_TYPE.HELP) {
      filtered = helpResults;
    } else {
      filtered = [...idleResults, ...helpResults];
    }
    this.setData({ filteredResults: filtered });
  },

  /**
   * 将标题中的关键字拆分为高亮/非高亮片段数组
   * @param {string} title 原始标题
   * @param {string} keyword 搜索关键字
   * @returns {Array<{text: string, highlight: boolean}>}
   */
  highlightKeyword(title, keyword) {
    if (!title || !keyword) return [{ text: title || '', highlight: false }];
    const escaped = keyword.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    const regex = new RegExp(`(${escaped})`, 'gi');
    const parts = title.split(regex);
    return parts.filter(Boolean).map(part => ({
      text: part,
      highlight: part.toLowerCase() === keyword.toLowerCase()
    }));
  },

  onResultTap(e) {
    const item = e.currentTarget.dataset.item;
    if (item.type === CONTENT_TYPE.HELP) {
      wx.navigateTo({ url: `/pages/help-detail/help-detail?id=${item.id}` });
    } else {
      wx.navigateTo({ url: `/pages/idle-detail/idle-detail?id=${item.id}` });
    }
  },

  onClearHistory() {
    wx.setStorageSync('searchHistory', []);
    this.setData({ history: [] });
    wx.showToast({ title: '已清空搜索历史', icon: 'success' });
  },

  onCancel() {
    wx.navigateBack();
  },

  /** 页面卸载时清除防抖定时器，防止无效请求 */
  onUnload() {
    if (this._searchTimer) {
      clearTimeout(this._searchTimer);
      this._searchTimer = null;
    }
  }
});
