/**
 * constants.js 单元测试。
 * 验证 POST_STATUS、POST_TYPE、AUTH_STATUS、BORROW_STATUS、HELP_APPLICATION_STATUS 常量值与后端定义一致。
 */

describe('constants', () => {
  let constants;

  beforeEach(() => {
    // 每次重新 require 以获取干净模块
    jest.resetModules();
    constants = require('../constants');
  });

  // ==================== POST_STATUS ====================

  describe('POST_STATUS', () => {
    it('应包含全部帖子状态', () => {
      expect(constants.POST_STATUS.ONLINE).toBe('online');
      expect(constants.POST_STATUS.DRAFT).toBe('draft');
      expect(constants.POST_STATUS.OFFLINE).toBe('offline');
      expect(constants.POST_STATUS.PENDING_REVIEW).toBe('pending_review');
      expect(constants.POST_STATUS.PENDING).toBe('pending');
      expect(constants.POST_STATUS.ACTIVE).toBe('active');
      expect(constants.POST_STATUS.COMPLETED).toBe('completed');
    });

    it('所有 POST_STATUS 常量值应为小写字符串', () => {
      Object.values(constants.POST_STATUS).forEach(value => {
        expect(value).toBe(value.toLowerCase());
      });
    });
  });

  // ==================== POST_TYPE ====================

  describe('POST_TYPE', () => {
    it('应包含三种发布类型', () => {
      expect(constants.POST_TYPE.LEND).toBe('LEND');
      expect(constants.POST_TYPE.WANTED).toBe('WANTED');
      expect(constants.POST_TYPE.HELP).toBe('HELP');
    });

    it('POST_TYPE 值应为大写字符串', () => {
      Object.values(constants.POST_TYPE).forEach(value => {
        expect(value).toBe(value.toUpperCase());
      });
    });
  });

  // ==================== AUTH_STATUS ====================

  describe('AUTH_STATUS', () => {
    it('应包含全部账户审核状态', () => {
      expect(constants.AUTH_STATUS.PENDING).toBe('pending');
      expect(constants.AUTH_STATUS.APPROVED).toBe('approved');
      expect(constants.AUTH_STATUS.REJECTED).toBe('rejected');
      expect(constants.AUTH_STATUS.REGISTERING).toBe('registering');
      expect(constants.AUTH_STATUS.BANNED).toBe('banned');
    });

    it('所有 AUTH_STATUS 常量值应为小写字符串', () => {
      Object.values(constants.AUTH_STATUS).forEach(value => {
        expect(value).toBe(value.toLowerCase());
      });
    });
  });

  // ==================== BORROW_STATUS ====================

  describe('BORROW_STATUS', () => {
    it('应包含全部借用申请状态', () => {
      expect(constants.BORROW_STATUS.PENDING).toBe('pending');
      expect(constants.BORROW_STATUS.APPROVED).toBe('approved');
      expect(constants.BORROW_STATUS.REJECTED).toBe('rejected');
      expect(constants.BORROW_STATUS.RETURNED).toBe('returned');
      expect(constants.BORROW_STATUS.COMPLETED).toBe('completed');
    });

    it('所有 BORROW_STATUS 常量值应为小写字符串', () => {
      Object.values(constants.BORROW_STATUS).forEach(value => {
        expect(value).toBe(value.toLowerCase());
      });
    });
  });

  // ==================== HELP_APPLICATION_STATUS ====================

  describe('HELP_APPLICATION_STATUS', () => {
    it('应包含全部帮助申请状态', () => {
      expect(constants.HELP_APPLICATION_STATUS.PENDING).toBe('pending');
      expect(constants.HELP_APPLICATION_STATUS.APPROVED).toBe('approved');
      expect(constants.HELP_APPLICATION_STATUS.REJECTED).toBe('rejected');
      expect(constants.HELP_APPLICATION_STATUS.COMPLETED).toBe('completed');
    });

    it('所有 HELP_APPLICATION_STATUS 常量值应为小写字符串', () => {
      Object.values(constants.HELP_APPLICATION_STATUS).forEach(value => {
        expect(value).toBe(value.toLowerCase());
      });
    });
  });

  // ==================== ITEM_CONDITION ====================

  describe('ITEM_CONDITION', () => {
    it('应包含全部物品成色', () => {
      expect(constants.ITEM_CONDITION.LIKE_NEW).toBe('like-new');
      expect(constants.ITEM_CONDITION.NORMAL).toBe('normal');
      expect(constants.ITEM_CONDITION.WORN).toBe('worn');
    });
  });

  // ==================== old STATUS removed ====================

  it('不应再导出旧的混合 STATUS 对象', () => {
    expect(constants.STATUS).toBeUndefined();
  });
});
