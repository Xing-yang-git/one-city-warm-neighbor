const api = require('../../utils/api');
const auth = require('../../utils/auth');
const { RATING_TYPE } = require('../../utils/constants');

/**
 * 评价页 — 借用/帮助完成后的互评。
 *
 * 功能：1-5 星评分、文字反馈、评价提交。
 * 评价类型支持 borrow（借用评价）和 help（帮助评价）。
 */
Page({
  data: {
    borrowId: '',
    targetName: '',
    ratingType: RATING_TYPE.BORROW, // 评价类型，取值见 constants.js 的 RATING_TYPE
    ratingTypeText: '',
    overallScore: 0
  },

  onLoad(options) {
    if (!auth.ensureAccess()) return;   // 登录/审核门禁：未通过则已跳转
    const borrowId = options.id || '';
    const targetName = decodeURIComponent(options.name || '用户');
    const ratingType = options.type || RATING_TYPE.BORROW;

    const ratingTypeText = ratingType === RATING_TYPE.HELP ? '帮助评价' : '借出评价';

    this.setData({
      borrowId,
      targetName,
      ratingType,
      ratingTypeText
    });
  },

  onShow() {
    if (!auth.ensureAccess()) return; // 登录/审核门禁：覆盖 tab 切换与后台切回
  },

  onOverallTap(e) {
    const score = parseInt(e.currentTarget.dataset.score);
    this.setData({ overallScore: score });
  },

  async onSubmit() {
    if (this.data.overallScore === 0) {
      wx.showToast({ title: '请选择总体评分', icon: 'none' });
      return;
    }
    wx.showLoading({ title: '提交中' });
    try {
      await api.post('/api/ratings', {
        targetId: this.data.borrowId,
        ratingType: this.data.ratingType,
        overallScore: this.data.overallScore
      });
      wx.hideLoading();
      wx.showToast({ title: '评价成功' });
      setTimeout(() => wx.navigateBack(), 1500);
    } catch (e) {
      wx.hideLoading();
      wx.showToast({ title: e.message || '评价失败', icon: 'none' });
    }
  }
});
