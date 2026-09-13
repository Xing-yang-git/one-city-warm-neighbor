const api = require("../../utils/api");
const auth = require("../../utils/auth");
const { POST_STATUS, POST_TYPE, BORROW_STATUS, RETURN_STATUS, DAMAGE_TYPE } = require("../../utils/constants");

/**
 * 我的帖子页 — 我发布的 / 审批管理 / 进行中 / 已完成 四 Tab 视图。
 *
 * 功能：主 Tab 切换（publish / approval / inProgress / completed）、
 *        子 Tab（闲置/求助）筛选、审批操作（同意/拒绝）、完成确认、删除帖子、下拉刷新。
 */
Page({
  data: {
    // 全局加载/错误状态
    loading: true,
    loadError: false,
    loadErrorMsg: "",

    // 主 tab：publish | approval | inProgress | completed
    mainTab: "publish",

    // 「我发布的」子 tab
    publishSubTab: POST_STATUS.ONLINE,
    onlineCount: 0,
    offlineCount: 0,
    reviewCount: 0,
    onlinePosts: [],
    offlinePosts: [],
    reviewPosts: [],

    // 「待审批」子 tab
    approvalSubTab: "borrow",
    borrowApprovals: [],
    lendApprovals: [],
    helpApprovals: [],
    approvalCount: 0, // 待审批总数（三个子列表之和），驱动审批 tab 角标

    // 「进行中」子 tab：borrow | lend | helpReq | helpPro
    inProgressSubTab: "borrow",
    inProgressBorrows: [],
    inProgressLends: [],
    inProgressHelpReqs: [],
    inProgressHelpPros: [],

    // 「已完成」子 tab：borrow | lend | helpReq | helpPro
    completedSubTab: "borrow",
    completedBorrows: [],
    completedLends: [],
    completedHelpReqs: [],
    completedHelpPros: [],

    // Drawer
    showDrawer: false,

    // Alerts
    showOfflineAlert: false,
    offlineTargetId: null,
    offlineTargetPostType: null,

    // Approval Sheet
    showApprovalSheet: false,
    approvalSheetItem: null,

    // Progress Sheet (进行中 detail)
    showProgressSheet: false,
    progressSheetItem: null,
    progressSheetType: "",
    progressSheetRating: 0,
    progressSheetFeedback: "",
    progressSheetCondition: "",

    // Completed Sheet (已完成 detail)
    showCompletedSheet: false,
    completedSheetItem: null,
    completedRating: 0, // 补充评价：本方尚未评分时在已完成页评价对方
    completedDamageType: DAMAGE_TYPE.NORMAL,  // 补充物品状况：借出方未填写时在此选择（默认正常损耗）
    completedFeedback: "",

    // 智能生成评价
    _generatingFeedback: false,
    // 待评价提示：各角色已完成列表中是否存在本方尚未评分的记录
    pendingRating: {
      borrow: false,
      lend: false,
      helpReq: false,
      helpPro: false,
    },
    hasPendingRating: false,
    // 下拉刷新状态（四个 tab 共用，同一时刻只显示一个列表）
    refreshing: false,

    // 帖子状态常量（供 WXML 模板引用）
    POST_STATUS: POST_STATUS,

    // Confirmation Alerts
    showConfirmAlert: false,
    confirmAction: "", // 'approve' | 'reject'
    confirmAlertTitle: "",
    confirmAlertBody: "",
    showReturnConfirmAlert: false,
    returnConfirmData: null,
    showOverdueTipAlert: false,
    showSaveConfirmAlert: false,

    // Edit Sheet — mirrors publish-idle form fields
    showEditSheet: false,
    editSource: POST_STATUS.ONLINE,
    editTargetId: null,
    editPostType: POST_TYPE.LEND,
    editImages: [],
    editTitle: "",
    editCategory: "",
    editCustomType: "",
    editPrice: "",
    editDesc: "",
    editDurationUnit: "day",
    editDurationOptions: [
      "1 天",
      "2 天",
      "3 天",
      "4 天",
      "5 天",
      "6 天",
      "7 天",
    ],
    editDurationIndex: 6,
    editPickupMethod: "self_pickup",
    editCondition: "normal",
    editUrgency: "normal",
    // 时间段编辑（HELP 专用）
    editEnableTimeRange: false,
    editTimeStartDate: "",
    editTimeStartHour: 9,
    editTimeEndDate: "",
    editTimeEndHour: 18,
    // 拒绝原因（选填，开关控制）
    enableRejectReason: false,
    rejectReason: "",
    // 键盘弹起高度（用于弹框 textarea 被键盘遮挡时自动上移）
    keyboardHeight: 0,
    sheetPaddingBottom: 0,
    hourOptions: [],
  },

  onLoad() {
    if (!auth.ensureAccess()) return; // 登录/审核门禁：未通过则已跳转
    const app = getApp();
    app.ensureWebSocket(); // 确保全局 WS 已连接（管理页入口也需维持长连接接收服务通知）
    const hours = [];
    for (let i = 0; i < 24; i++) {
      hours.push((i < 10 ? "0" : "") + i + ":00");
    }
    this.setData({ hourOptions: hours });

    // 首次加载：仅加载默认 tab（发布）的数据 + 审批角标，onShow 不再重复触发
    this._initialLoad = true;
    this._loadCurrentTabAndBadge({ showLoading: true });
  },

  onShow() {
    if (!auth.ensureAccess()) return; // 登录/审核门禁：覆盖 tab 切换与后台切回

    // 检查是否有来自服务通知「去评价」的跳转请求
    if (this._handlePendingCompletedTarget()) return;

    // 检查是否有来自服务通知「去审批」的跳转请求
    if (this._handlePendingApprovalTarget()) return;

    const app = getApp();
    app.ensureWebSocket(); // 确保全局 WS 连接
    app._updateTabBarBadge(); // 刷新消息红点
    app.refreshNoticeBadge(); // 从服务端拉最新通知未读数，兜底 WS 推送丢失

    // 跳过首次 onShow（onLoad 已触发数据加载）
    if (this._initialLoad) {
      this._initialLoad = false;
      return;
    }
    // 后续切回 tab 时仅刷新当前 tab 数据 + 审批角标（静默刷新）
    this._loadCurrentTabAndBadge({ isRefresh: true });
  },

  /**
   * 返回手势/返回键处理（Android 返回键 + iOS 侧滑返回）：
   * 任一底部弹层（编辑/审批/进行中/已完成）打开时先关闭对应弹层并拦截路由回退——
   * 本页为 tabBar 根页面，弹层开着时返回手势会被当成根页面返回直接退出小程序。
   */
  /**
   * 处理来自服务通知「去评价」的跳转：切换到已完成 tab，定位到对应子选项并弹出详情弹框。
   * @returns {boolean} 是否消费了 pendingCompletedTarget
   */
  _handlePendingCompletedTarget() {
    const app = getApp();
    const target = app.globalData.pendingCompletedTarget;
    if (!target) return false;

    // 立即清除，防止重复触发
    app.globalData.pendingCompletedTarget = null;

    const { relatedId, type } = target;
    // return_confirm → 借入/借出；help_result → 求助/帮助
    const searchRoles =
      type === "return_confirm" ? ["borrow", "lend"] : ["helpReq", "helpPro"];

    // 切换到已完成 tab 并加载四个角色数据
    this.setData({ mainTab: "completed", loading: true });
    Promise.all([
      this.loadCompleted("borrow"),
      this.loadCompleted("lend"),
      this.loadCompleted("helpReq"),
      this.loadCompleted("helpPro"),
    ])
      .then(() => {
        this.setData({ loading: false });
        const keyMap = {
          borrow: "completedBorrows",
          lend: "completedLends",
          helpReq: "completedHelpReqs",
          helpPro: "completedHelpPros",
        };
        for (const role of searchRoles) {
          const list = this.data[keyMap[role]] || [];
          const item = list.find((i) => String(i.id) === String(relatedId));
          if (item) {
            this.setData({
              completedSubTab: role,
              showCompletedSheet: true,
              completedSheetItem: { ...item },
              completedRating: 0,
              completedDamageType: DAMAGE_TYPE.NORMAL,
              completedFeedback: "",
            });
            return;
          }
        }
        // 未找到匹配项（数据可能已被清理），仅停留在已完成 tab 不做额外提示
      })
      .catch(() => {
        this.setData({ loading: false });
      });

    return true;
  },

  /**
   * 处理来自服务通知「去审批」的跳转：切换到审批 tab，定位到对应子选项并弹出审批弹框。
   * @returns {boolean} 是否消费了 pendingApprovalTarget
   */
  _handlePendingApprovalTarget() {
    const app = getApp();
    const target = app.globalData.pendingApprovalTarget;
    if (!target) return false;

    // 立即清除，防止重复触发
    app.globalData.pendingApprovalTarget = null;

    const { relatedId, type } = target;
    // help_application → 帮助审批；borrow_request → 借入/借出审批（需搜索两个子 tab）
    const searchTypes =
      type === "help_application" ? ["help"] : ["borrow", "lend"];

    // 切换到审批 tab 并加载数据
    this.setData({ mainTab: "approval", loading: true });
    const loadTasks = searchTypes.map((t) => this.loadApprovals(t));
    // 同时加载所有类型以获取匹配项
    if (type !== "help_application") {
      loadTasks.push(this.loadApprovals("help")); // borrow_request 时也加载 help 审批
    } else {
      loadTasks.push(this.loadApprovals("borrow"));
      loadTasks.push(this.loadApprovals("lend"));
    }

    Promise.all(loadTasks)
      .then(() => {
        this.setData({ loading: false });
        const keyMap = {
          borrow: "borrowApprovals",
          lend: "lendApprovals",
          help: "helpApprovals",
        };
        for (const at of searchTypes) {
          const list = this.data[keyMap[at]] || [];
          const item = list.find((i) => String(i.id) === String(relatedId));
          if (item) {
            this.setData({
              approvalSubTab: at,
              showApprovalSheet: true,
              approvalSheetItem: { ...item, approvalType: at },
            });
            return;
          }
        }
        // 未找到匹配项（审批可能已被处理），仅停留在审批 tab
      })
      .catch(() => {
        this.setData({ loading: false });
      });

    return true;
  },

  // 重试加载（用户点击错误提示中的重试按钮，仅重试当前 tab）
  onRetryLoad() {
    this._loadCurrentTabAndBadge({ showLoading: true, loadBadge: false });
  },

  // 下拉刷新：仅刷新当前 tab 数据，完成后收起刷新动画
  onPullRefresh() {
    this.setData({ refreshing: true });
    this._loadCurrentTabAndBadge({ isRefresh: true, loadBadge: false }).finally(
      () => {
        this.setData({ refreshing: false });
      },
    );
  },

  // ================================================================
  // 数据加载（按需懒加载 — 仅加载当前选中 tab 的数据）
  // ================================================================

  /**
   * 加载当前 tab 的数据，可选同时加载审批角标。
   * @param {Object} options
   * @param {boolean} [options.showLoading] — 是否显示全屏 loading（仅首次加载）
   * @param {boolean} [options.isRefresh]   — 静默刷新（不弹 loading）
   * @param {boolean} [options.loadBadge]   — 是否加载审批角标，默认 true；下拉刷新/重试时无需重复加载
   */
  async _loadCurrentTabAndBadge(options = {}) {
    const {
      showLoading = false,
      isRefresh = false,
      loadBadge = true,
    } = options;
    this._lastError = null;

    // 检查登录状态 —— 管理页所有接口都需要 JWT 认证
    const token = wx.getStorageSync("token");
    if (!token) {
      if (!this._loginModalShown) {
        this._loginModalShown = true;
        wx.showModal({
          title: "请先登录",
          content: "管理页需要登录后才能查看您的发布、审批和进行中的记录。",
          cancelText: "稍后",
          confirmText: "去登录",
          success: (res) => {
            this._loginModalShown = false;
            if (res.confirm) {
              wx.navigateTo({ url: "/pages/login/login" });
            }
          },
          fail: () => {
            this._loginModalShown = false;
          },
        });
      }
      this.setData({ loading: false, loadError: true, loadErrorMsg: "未登录" });
      return;
    }

    if (showLoading) {
      this.setData({ loading: true, loadError: false, loadErrorMsg: "" });
    }

    const tab = this.data.mainTab;

    // 当前 tab 的数据
    const tasks = [this._loadDataForTab(tab)];

    // 审批角标：onLoad / onShow 时加载以保证 badge 准确，下拉刷新 / 重试时跳过
    if (loadBadge && tab !== "approval") {
      tasks.push(this._loadApprovalBadge());
    }

    // 已完成待评价黄点：非已完成 tab 时静默刷新（已完成 tab 自身加载已包含；下拉刷新/重试时跳过以减少请求）
    if (loadBadge !== false && tab !== "completed") {
      tasks.push(this._refreshCompletedBadge());
    }

    const results = await Promise.all(tasks);
    const tabResult = results[0];

    this.setData({ loading: false });

    // 首次加载时当前 tab 数据全部失败 → 展示错误提示
    if (showLoading && tabResult && tabResult.allFailed) {
      const errMsg = this._lastError;
      this.setData({ loadError: true, loadErrorMsg: errMsg });
      if (errMsg !== "请先登录") {
        wx.showToast({ title: errMsg, icon: "none", duration: 2500 });
      }
    }
  },

  /**
   * 加载指定主 tab 下所有子 tab 的数据。
   * @returns {{ allFailed: boolean }} 是否所有子请求均失败
   */
  async _loadDataForTab(tab) {
    let tasks;
    switch (tab) {
      case "publish":
        tasks = [this.loadMyPosts(), this.loadReviewPosts()];
        break;
      case "approval":
        tasks = [
          this.loadApprovals("borrow"),
          this.loadApprovals("lend"),
          this.loadApprovals("help"),
        ];
        break;
      case "inProgress":
        tasks = [
          this.loadInProgress("borrow"),
          this.loadInProgress("lend"),
          this.loadInProgress("helpReq"),
          this.loadInProgress("helpPro"),
        ];
        break;
      case "completed":
        tasks = [
          this.loadCompleted("borrow"),
          this.loadCompleted("lend"),
          this.loadCompleted("helpReq"),
          this.loadCompleted("helpPro"),
        ];
        break;
      default:
        return { allFailed: false };
    }
    const results = await Promise.all(tasks);
    const total = results.reduce(
      (sum, r) => sum + (typeof r === "number" ? r : 0),
      0,
    );
    return { allFailed: this._lastError && total === 0 };
  },

  /** 仅加载审批数据（用于更新角标，不影响当前 tab 展示） */
  async _loadApprovalBadge() {
    await Promise.all([
      this.loadApprovals("borrow"),
      this.loadApprovals("lend"),
      this.loadApprovals("help"),
    ]);
  },

  /** 静默刷新已完成 tab 待评价黄点（仅更新 pendingRating/hasPendingRating，不替换列表数据） */
  async _refreshCompletedBadge() {
    const roles = ["borrow", "lend", "helpReq", "helpPro"];
    const results = await Promise.all(
      roles.map((role) =>
        api
          .get("/api/users/completed", { role })
          .then((data) => (data?.content || (Array.isArray(data) ? data : [])))
          .catch(() => []),
      ),
    );
    const pendingRating = {};
    roles.forEach((role, i) => {
      pendingRating[role] = results[i].some((item) => item.myRating == null);
    });
    const hasPendingRating = Object.values(pendingRating).some((v) => v);
    this.setData({ pendingRating, hasPendingRating });
  },

  async loadMyPosts() {
    try {
      const data = await api.get("/api/users/posts");
      const allPosts = data?.content || (Array.isArray(data) ? data : []);
      const onlinePosts = allPosts
        .filter((p) => p.status === POST_STATUS.ONLINE)
        .map((p) => this.formatPostFromDTO(p, POST_STATUS.ONLINE));
      const offlinePosts = allPosts
        .filter((p) => p.status === POST_STATUS.DRAFT)
        .map((p) => this.formatPostFromDTO(p, POST_STATUS.DRAFT));
      this.setData({
        onlinePosts,
        onlineCount: onlinePosts.length,
        offlinePosts,
        offlineCount: offlinePosts.length,
      });
      return onlinePosts.length + offlinePosts.length;
    } catch (e) {
      console.error("Load my posts failed:", e);
      this._lastError = (e && e.message) || "加载发布列表失败";
      return 0;
    }
  },

  /** 加载审核中的帖子列表（AI 内容审核中） */
  async loadReviewPosts() {
    try {
      const data = await api.get("/api/users/posts", { status: "pending_review" });
      const items = (data?.content || (Array.isArray(data) ? data : [])).map((dto) => ({
        id: dto.id,
        postType: dto.type === "idle" ? (dto.postType || POST_TYPE.LEND) : POST_TYPE.HELP,
        title: dto.title || "",
        statusText: "审核中",
      }));
      this.setData({
        reviewPosts: items,
        reviewCount: items.length,
      });
      return items.length;
    } catch (e) {
      console.error("Load review posts failed:", e);
      this._lastError = (e && e.message) || "加载审核列表失败";
      return 0;
    }
  },


  formatPostFromDTO(dto, status) {
    const isIdle = dto.type === "idle";
    const durationNum = dto.maxDuration || 7;
    const durationUnit = dto.durationUnit || "day";
    const durationLabel =
      "可借 ≤" + durationNum + (durationUnit === "hour" ? "小时" : "天");
    const timeOnly = dto.createdAt
      ? this.formatRelativeTime(new Date(dto.createdAt).getTime())
      : "";
    const timeText =
      status === POST_STATUS.DRAFT
        ? dto.updatedAt
          ? this.formatRelativeTime(new Date(dto.updatedAt).getTime()) + "下架"
          : timeOnly
            ? timeOnly + "下架"
            : ""
        : timeOnly
          ? timeOnly + "发布"
          : "";

    // 发布类型中文标签
    const postType = isIdle ? dto.postType || POST_TYPE.LEND : POST_TYPE.HELP;
    const typeLabelMap = {
      [POST_TYPE.LEND]: "闲置借出",
      [POST_TYPE.WANTED]: "需求借入",
      [POST_TYPE.HELP]: "技能求助",
    };
    const typeLabel = typeLabelMap[postType] || "";

    const base = {
      id: dto.id,
      postType: postType,
      title: dto.title,
      category: dto.category || "",
      customType: "",
      description: dto.description || "",
      createTime: dto.createdAt
        ? new Date(dto.createdAt).getTime()
        : Date.now(),
      status: status,
      statusText: status === POST_STATUS.ONLINE ? "在线" : "已下架",
      statusTagClass:
        status === POST_STATUS.ONLINE
          ? "post-status-tag-blue"
          : "post-status-tag-fill",
      durationLabel: durationLabel,
      conditionLabel: isIdle ? this.conditionText(dto.condition) : "",
      typeLabel: typeLabel,
      timeText: timeText,
      isProxy: dto.isProxy,
      images: dto.images || "",
    };

    if (isIdle) {
      return {
        ...base,
        iconBg: "#E8F0FE",
        iconSrc: "../../images/icon-wrench.svg",
        price: dto.price || "",
        maxDuration: durationNum,
        durationUnit: durationUnit,
        pickupMethod: dto.pickupMethod || "self_pickup",
        condition: dto.condition || "normal",
      };
    } else {
      return {
        ...base,
        iconBg: "#FFF3E0",
        iconSrc: "../../images/icon-heart.svg",
        urgency: dto.isUrgent ? "urgent" : "normal",
        timeStart: dto.timeStart || "",
        timeEnd: dto.timeEnd || "",
      };
    }
  },

  conditionText(condition) {
    const map = {
      "like-new": "几乎全新",
      normal: "正常使用痕迹",
      worn: "有明显磨损",
    };
    return map[condition] || "";
  },

  // ================================================================
  // 审批数据
  // ================================================================
  async loadApprovals(type) {
    try {
      const data = await api.get("/api/users/approvals", { type });
      const items = (data?.content || (Array.isArray(data) ? data : [])).map((dto) => ({
        id: dto.id,
        itemTitle: dto.title || "",
        postType: dto.postType || "",
        applicantName: dto.personName || "",
        applicantAddress: dto.personRoom || "",
        applicantType: dto.personType || "",
        applicantUserId: dto.personId || null,
        applicantRating: (dto.personRating || 5).toFixed(1),
        borrowCount: dto.borrowCount || 0,
        borrowReturnRate:
          dto.borrowReturnRate != null ? dto.borrowReturnRate : 100,
        lendCount: dto.lendCount || 0,
        helpReqCount: dto.helpReqCount || 0,
        helpProCount: dto.helpProCount || 0,
        status: dto.status || BORROW_STATUS.PENDING,
        note: dto.note || "",
        maxDuration: dto.maxDuration || 0,
        durationUnit: dto.durationUnit || "day",
        timeStart: dto.timeStart || "",
        timeEnd: dto.timeEnd || "",
      }));
      const keyMap = {
        borrow: "borrowApprovals",
        lend: "lendApprovals",
        help: "helpApprovals",
      };
      this.setData({ [keyMap[type] || "borrowApprovals"]: items });
      this._syncApprovalCount();
      return items.length;
    } catch (e) {
      console.error("Load " + type + " approvals failed:", e);
      this._lastError = (e && e.message) || "加载审批列表失败";
      return 0;
    }
  },

  /**
   * 汇总三个审批子列表 → 审批 tab 角标数，并同步 tabBar「管理」红点。
   * 审批列表只含 pending 记录，列表长度即待审批数。
   */
  _syncApprovalCount() {
    const total =
      this.data.borrowApprovals.length +
      this.data.lendApprovals.length +
      this.data.helpApprovals.length;
    this.setData({ approvalCount: total });
    const app = getApp();
    if (app && app.setManageBadge) app.setManageBadge(total);
  },

  // ================================================================
  // 进行中数据
  // ================================================================
  async loadInProgress(role) {
    try {
      const data = await api.get("/api/users/in-progress", { role });
      const items = (data?.content || (Array.isArray(data) ? data : [])).map((dto) => ({
        id: dto.id,
        personName: dto.personName || "",
        personAddress: dto.personRoom || "",
        personType: dto.personType || "",
        personUserId: dto.personId || null,
        personRating: (dto.personRating || 5).toFixed(1),
        itemTitle: dto.title || "",
        postType: dto.postType || "",
        metaText: dto.metaText || "",
        remainingDays: dto.remainingDays || 0,
        remainingHours: dto.remainingHours || 0,
        expectedReturnDays: dto.expectedReturnDays || 0,
        isOverdue: dto.isOverdue || false,
        roleLabel: dto.roleLabel || "",
        note: dto.note || "",
      }));
      const keyMap = {
        borrow: "inProgressBorrows",
        lend: "inProgressLends",
        helpReq: "inProgressHelpReqs",
        helpPro: "inProgressHelpPros",
      };
      this.setData({ [keyMap[role]]: items });
      return items.length;
    } catch (e) {
      console.error("Load in-progress " + role + " failed:", e);
      this._lastError = (e && e.message) || "加载进行中列表失败";
      return 0;
    }
  },

  // ================================================================
  // 已完成数据
  // ================================================================
  async loadCompleted(role) {
    try {
      const data = await api.get("/api/users/completed", { role });
      const keyMap = {
        borrow: "completedBorrows",
        lend: "completedLends",
        helpReq: "completedHelpReqs",
        helpPro: "completedHelpPros",
      };
      const roleLabelMap = {
        borrow: "借出住户",
        lend: "借走住户",
        helpReq: "帮忙用户",
        helpPro: "求助住户",
      };
      const typeMap = {
        borrow: "borrow",
        lend: "lend",
        helpReq: "helpReq",
        helpPro: "helpPro",
      };
      const items = (data?.content || (Array.isArray(data) ? data : [])).map((dto) =>
        this.formatCompletedItem(
          {
            id: dto.id,
            itemTitle: dto.title,
            helpTitle: dto.title,
            postType: dto.postType || "",
            completedTime: dto.completedAt
              ? new Date(dto.completedAt).getTime()
              : Date.now(),
            isOverdue: dto.isOverdue || false,
            overdueDays: dto.isOverdue
              ? (dto.expectedReturnDays || 0) - (dto.remainingDays || 0)
              : 0,
            myRating: dto.myRating,
            myFeedback: dto.myFeedback || "",
            theirRating: dto.theirRating,
            theirFeedback: dto.theirFeedback || "",
            note: dto.note || "",
          },
          {
            type: typeMap[role],
            personName: dto.personName || "",
            personAddress: dto.personRoom || "",
            personUserId: dto.personId || null,
            personType: dto.personType || "",
            roleLabel: roleLabelMap[role],
          },
        ),
      );
      this.setData({ [keyMap[role]]: items });
      // 更新待评价提示（本方 myRating 为空即待评价）
      const pending = items.some((i) => i.myRating === null);
      const pendingRating = Object.assign({}, this.data.pendingRating, {
        [role]: pending,
      });
      const hasPendingRating = Object.keys(pendingRating).some(
        (k) => pendingRating[k],
      );
      this.setData({ pendingRating, hasPendingRating });
      return items.length;
    } catch (e) {
      console.error("Load completed " + role + " failed:", e);
      this._lastError = (e && e.message) || "加载已完成列表失败";
      return 0;
    }
  },

  // 已完成项通用格式化（列表 + 详情弹层）
  formatCompletedItem(item, opts) {
    const isBorrow = opts.type === "borrow" || opts.type === "lend";
    let borrowMeta;
    if (isBorrow && item.isOverdue) {
      // 小于1天显示小时，否则显示天数；都不足则只显示"超时归还"
      const overdueDays = item.overdueDays || 0;
      const overdueHours = item.remainingHours
        ? Math.abs(item.remainingHours)
        : 0;
      if (overdueDays >= 1) {
        borrowMeta = "超时 " + overdueDays + " 天归还";
      } else if (overdueHours > 0) {
        borrowMeta = "超时 " + overdueHours + " 小时归还";
      } else {
        borrowMeta = "超时归还";
      }
    } else if (isBorrow) {
      borrowMeta = "已于 " + this.formatDate(item.completedTime) + " 归还";
    }
    const metaText = isBorrow
      ? borrowMeta
      : "已于 " + this.formatDate(item.completedTime) + " 结束";
    return {
      id: item.id,
      type: opts.type,
      postType: item.postType || "",
      personName: opts.personName,
      personAddress: opts.personAddress,
      personUserId: opts.personUserId || null,
      personType: opts.personType,
      roleLabel: opts.roleLabel,
      itemTitle: item.itemTitle || item.helpTitle,
      metaText: metaText,
      statusText: item.isOverdue ? "超时归还" : "已完成",
      statusTagClass: item.isOverdue
        ? "post-status-tag-red"
        : "post-status-tag-fill",
      // 已完成详情弹层的字段
      myRating: item.myRating != null ? Number(item.myRating).toFixed(1) : null,
      myFeedback: this.sanitizeFeedback(item.myFeedback),
      theirRating:
        item.theirRating != null ? Number(item.theirRating).toFixed(1) : null,
      theirFeedback: this.sanitizeFeedback(item.theirFeedback),
      note: item.note || "",
      damageType: item.damageType || "",   // 归还后物品状况（空表示待确认）
      isOnTime: item.isOnTime,
    };
  },

  // ================================================================
  // Tab 切换
  // ================================================================
  onMainTabTap(e) {
    const tab = e.currentTarget.dataset.tab;
    if (tab === this.data.mainTab) return; // 重复点击同一 tab，忽略
    this.setData({ mainTab: tab });
    // 切换到目标 tab 时立即加载该 tab 的数据，确保数据始终最新
    this._loadDataForTab(tab);
    // 切换到非已完成 tab 时，静默刷新待评价黄点
    if (tab !== "completed") {
      this._refreshCompletedBadge();
    }
  },

  onPublishSubTap(e) {
    this.setData({ publishSubTab: e.currentTarget.dataset.sub });
  },

  /** 审核中 tab 的 ? 图标：弹出提示说明 */
  onReviewInfoTap(e) {
    // 阻止冒泡到父级 segment-item 的 bindtap
    if (e && e.stopPropagation) e.stopPropagation();
    wx.showModal({
      title: "审核中",
      content: "您发布的内容正在由 AI 系统进行审核，审核完成后会通过服务通知告知您结果。审核期间内容暂不公开展示。",
      showCancel: false,
      confirmText: "我知道了",
    });
  },

  onApprovalSubTap(e) {
    this.setData({ approvalSubTab: e.currentTarget.dataset.sub });
  },

  onInProgressSubTap(e) {
    this.setData({ inProgressSubTab: e.currentTarget.dataset.sub });
  },

  onCompletedSubTap(e) {
    this.setData({ completedSubTab: e.currentTarget.dataset.sub });
  },

  // ================================================================
  // 抽屉面板
  // ================================================================
  onOpenDrawer() {
    this.setData({ showDrawer: true });
  },

  onCloseDrawer() {
    this.setData({ showDrawer: false });
  },

  onFeedBack() {
    this.setData({ showDrawer: false });
    wx.showToast({ title: "功能反馈已收到", icon: "none" });
  },

  // ================================================================
  // 下架流程
  // ================================================================
  onConfirmOffline(e) {
    this.setData({
      showOfflineAlert: true,
      offlineTargetId: e.currentTarget.dataset.id,
      offlineTargetPostType: e.currentTarget.dataset.postType,
    });
  },

  onCloseOfflineAlert() {
    this.setData({
      showOfflineAlert: false,
      offlineTargetId: null,
      offlineTargetPostType: null,
    });
  },

  onCancelOffline() {
    this.setData({
      showOfflineAlert: false,
      offlineTargetId: null,
      offlineTargetPostType: null,
    });
  },

  async onDoOffline() {
    const id = this.data.offlineTargetId;
    const postType = this.data.offlineTargetPostType;
    this.setData({
      showOfflineAlert: false,
      offlineTargetId: null,
      offlineTargetPostType: null,
    });
    if (!id) return;

    // 调后端持久化下架：HELP → /api/help，LEND/WANTED → /api/idle
    const endpoint =
      postType === POST_TYPE.HELP
        ? `/api/help-requests/${id}/delist`
        : `/api/idle-items/${id}/delist`;
    wx.showLoading({ title: "下架中..." });
    try {
      await api.put(endpoint);
      wx.hideLoading();

      // 后端成功后再更新本地列表（在线 → 已下架）
      const onlinePosts = this.data.onlinePosts;
      const idx = onlinePosts.findIndex((p) => p.id === id);
      if (idx >= 0) {
        const item = { ...onlinePosts[idx] };
        item.status = POST_STATUS.DRAFT;
        item.statusText = "已下架";
        item.statusTagClass = "post-status-tag-fill";
        item.timeText = this.formatRelativeTime(Date.now()) + "下架";
        const offlinePosts = [item, ...this.data.offlinePosts];
        onlinePosts.splice(idx, 1);
        this.setData({
          onlinePosts,
          onlineCount: onlinePosts.length,
          offlinePosts,
          offlineCount: offlinePosts.length,
        });
      }
      wx.showToast({ title: "已下架", icon: "success" });
    } catch (err) {
      wx.hideLoading();
      wx.showToast({ title: (err && err.message) || "下架失败", icon: "none" });
    }
  },

  // ================================================================
  // 编辑弹层
  // ================================================================
  onOpenEdit(e) {
    const post = e.currentTarget.dataset.post;
    const type = e.currentTarget.dataset.type;
    const source = e.currentTarget.dataset.source || POST_STATUS.ONLINE;
    if (!post) return;

    const unit = post.durationUnit || "day";
    let durationOptions, durationIndex;
    if (unit === "hour") {
      durationOptions = Array.from({ length: 23 }, (_, i) => i + 1 + " 小时");
      durationIndex = Math.max(0, Math.min((post.maxDuration || 3) - 1, 22));
    } else {
      durationOptions = Array.from({ length: 7 }, (_, i) => i + 1 + " 天");
      durationIndex = Math.max(0, Math.min((post.maxDuration || 7) - 1, 6));
    }

    const postType =
      post.postType || (type === "help" ? POST_TYPE.HELP : POST_TYPE.LEND);

    // 预定义类别列表（与 publish-idle 一致）
    const STANDARD_CATEGORIES = [
      "工具",
      "电子产品",
      "书籍",
      "家居",
      "运动",
      "玩具",
      "服饰",
    ];
    const HELP_CATEGORIES = [
      "维修",
      "陪护",
      "代取",
      "搬运",
      "辅导",
      "遛宠",
      "烹饪",
    ];

    // 如果 category 不是预定义值，则归类为"其他"并将原值作为 customType 回显
    let editCategory = post.category || "";
    let editCustomType = post.customType || "";
    if (postType !== POST_TYPE.HELP) {
      if (editCategory && STANDARD_CATEGORIES.indexOf(editCategory) === -1) {
        editCustomType = editCategory;
        editCategory = "其他";
      }
    } else {
      if (editCategory && HELP_CATEGORIES.indexOf(editCategory) === -1) {
        editCustomType = editCategory;
        editCategory = "其他";
      }
    }

    // HELP：解析时间段为日期和小时组件（在 _originalPost 和 setData 之前计算）
    let startDate = "",
      startHour = 9,
      endDate = "",
      endHour = 18,
      enableTimeRange = false;
    if (postType === POST_TYPE.HELP) {
      const hasTimeRange = post.timeStart && post.timeEnd;
      if (hasTimeRange) {
        enableTimeRange = true;
        const fmtStart = this.formatDateTime(post.timeStart);
        const fmtEnd = this.formatDateTime(post.timeEnd);
        const parseParts = (str, defaultHour) => {
          if (!str) return { date: "", hour: defaultHour };
          const parts = str.split(" ");
          const timeParts = (parts[1] || defaultHour + ":00").split(":");
          return {
            date: parts[0] || "",
            hour: parseInt(timeParts[0]) || defaultHour,
          };
        };
        const start = parseParts(fmtStart, 9);
        const end = parseParts(fmtEnd, 18);
        startDate = start.date;
        startHour = Math.max(0, Math.min(start.hour, 23));
        endDate = end.date;
        endHour = Math.max(0, Math.min(end.hour, 23));
      }
      // 未填写时间范围时 enableTimeRange 保持 false，不解析时间
    }

    // 解析已有图片 URL（JSON 字符串 → 数组）
    // _editOriginalUrls：原始存储路径（用于保存时区分已有/新增）
    // editImages：展示用完整 URL（拼接 baseUrl 后小程序才能显示）
    let originalUrls = [];
    let displayImages = [];
    try {
      if (post.images) {
        originalUrls = typeof post.images === "string" ? JSON.parse(post.images) : post.images;
        const baseUrl = getApp().globalData.baseUrl || "";
        displayImages = originalUrls.map(url => {
          if (!url) return "";
          if (url.startsWith("http")) return url;
          return baseUrl + url;
        });
      }
    } catch (e) {
      originalUrls = [];
      displayImages = [];
    }
    this._editOriginalUrls = originalUrls;

    // 存储原始值，用于保存时检测是否有修改
    this._originalPost = {
      title: post.title || "",
      category: editCategory,
      customType: editCustomType,
      price: String(post.price || ""),
      description: post.description || "",
      durationUnit: unit,
      maxDuration: post.maxDuration || 7,
      pickupMethod: post.pickupMethod || "self_pickup",
      condition: post.condition || "normal",
      urgency: post.urgency || "normal",
      postType: postType,
      images: originalUrls,
      // HELP 时间段原始值
      enableTimeRange: enableTimeRange,
      timeStartDate: startDate,
      timeStartHour: startHour,
      timeEndDate: endDate,
      timeEndHour: endHour,
    };

    this.setData({
      showEditSheet: true,
      editSource: source,
      editTargetId: post.id,
      editPostType: postType,
      editImages: displayImages,
      editTitle: post.title || "",
      editCategory: editCategory,
      editCustomType: editCustomType,
      editPrice: String(post.price || ""),
      editDesc: post.description || "",
      editDurationUnit: unit,
      editDurationOptions: durationOptions,
      editDurationIndex: durationIndex,
      editPickupMethod: post.pickupMethod || "self_pickup",
      editCondition: post.condition || "normal",
      editUrgency: post.urgency || "normal",
      // HELP 时间段编辑值
      editEnableTimeRange: enableTimeRange,
      editTimeStartDate: startDate,
      editTimeStartHour: startHour,
      editTimeEndDate: endDate,
      editTimeEndHour: endHour,
    });
  },

  onCloseEdit() {
    this.setData({ showEditSheet: false, editTargetId: null, editImages: [] });
  },

  /** 编辑弹窗中 — 添加图片 */
  onEditAddImage() {
    const remaining = 4 - this.data.editImages.length;
    if (remaining <= 0) {
      wx.showToast({ title: '最多 4 张', icon: 'none' });
      return;
    }
    wx.chooseImage({
      count: remaining,
      sizeType: ['compressed'],
      sourceType: ['album', 'camera'],
      success: res => {
        this.setData({ editImages: [...this.data.editImages, ...res.tempFilePaths] });
      }
    });
  },

  /** 编辑弹窗中 — 预览图片 */
  onEditPreviewImage(e) {
    const idx = e.currentTarget.dataset.index;
    const urls = this.data.editImages;
    wx.previewImage({ current: urls[idx], urls: urls });
  },

  /** 编辑弹窗中 — 删除图片 */
  onEditRemoveImage(e) {
    const idx = e.currentTarget.dataset.index;
    const imgs = [...this.data.editImages];
    imgs.splice(idx, 1);
    this.setData({ editImages: imgs });
  },

  onEditInput(e) {
    const field = e.currentTarget.dataset.field;
    this.setData({ [field]: e.detail.value });
  },

  onEditCategoryTap(e) {
    const value = e.currentTarget.dataset.value;
    if (value === "其他") {
      if (this.data.editCategory === "其他") {
        this.setData({ editCategory: "", editCustomType: "" });
        return;
      }
      this.setData({ editCategory: "其他" });
    } else {
      this.setData({ editCategory: value, editCustomType: "" });
    }
  },

  onEditUnitTap(e) {
    const unit = e.currentTarget.dataset.value;
    if (unit === "hour") {
      const hours = Array.from({ length: 23 }, (_, i) => i + 1 + " 小时");
      this.setData({
        editDurationUnit: "hour",
        editDurationOptions: hours,
        editDurationIndex: 2,
      });
    } else {
      const days = Array.from({ length: 7 }, (_, i) => i + 1 + " 天");
      this.setData({
        editDurationUnit: "day",
        editDurationOptions: days,
        editDurationIndex: 6,
      });
    }
  },

  onEditDurationChange(e) {
    this.setData({ editDurationIndex: parseInt(e.detail.value) });
  },

  onEditPickupTap(e) {
    this.setData({ editPickupMethod: e.currentTarget.dataset.value });
  },

  onEditConditionTap(e) {
    this.setData({ editCondition: e.currentTarget.dataset.value });
  },

  onEditUrgencyTap(e) {
    this.setData({ editUrgency: e.currentTarget.dataset.value });
  },

  // HELP 编辑：时间范围开关
  onEditToggleTimeRange(e) {
    this.setData({ editEnableTimeRange: e.detail.value });
  },

  // 时间段选择（HELP 编辑）
  onEditTimeStartDateChange(e) {
    this.setData({ editTimeStartDate: e.detail.value });
  },

  onEditTimeStartHourChange(e) {
    this.setData({ editTimeStartHour: parseInt(e.detail.value) });
  },

  onEditTimeEndDateChange(e) {
    this.setData({ editTimeEndDate: e.detail.value });
  },

  onEditTimeEndHourChange(e) {
    this.setData({ editTimeEndHour: parseInt(e.detail.value) });
  },

  onSaveEdit() {
    if (!this.data.editTitle.trim()) {
      wx.showToast({ title: "请输入标题", icon: "none" });
      return;
    }

    // 检测是否有实质性修改（重新发布允许原样上架，不做此拦截）
    if (this.data.editSource !== POST_STATUS.DRAFT && !this._editHasChanged()) {
      wx.showToast({ title: "您并没有修改内容", icon: "none" });
      return;
    }

    this.setData({ showSaveConfirmAlert: true });
  },

  /** 比较当前编辑表单与打开弹层时的原始值，判断是否有修改 */
  _editHasChanged() {
    const orig = this._originalPost;
    if (!orig) return true;

    const curr = {
      title: this.data.editTitle.trim(),
      category: this.data.editCategory,
      customType: this.data.editCustomType.trim(),
      description: this.data.editDesc.trim(),
    };

    if (orig.postType === POST_TYPE.LEND) {
      curr.price = this.data.editPrice.trim();
      curr.durationUnit = this.data.editDurationUnit;
      curr.maxDuration =
        parseInt(this.data.editDurationOptions[this.data.editDurationIndex]) ||
        7;
      curr.pickupMethod = this.data.editPickupMethod;
      curr.condition = this.data.editCondition;
    } else if (orig.postType === POST_TYPE.WANTED) {
      curr.durationUnit = this.data.editDurationUnit;
      curr.maxDuration =
        parseInt(this.data.editDurationOptions[this.data.editDurationIndex]) ||
        7;
    } else if (orig.postType === POST_TYPE.HELP) {
      curr.urgency = this.data.editUrgency;
      curr.enableTimeRange = this.data.editEnableTimeRange;
      curr.timeStartDate = this.data.editTimeStartDate;
      curr.timeStartHour = this.data.editTimeStartHour;
      curr.timeEndDate = this.data.editTimeEndDate;
      curr.timeEndHour = this.data.editTimeEndHour;
    }

    // orig 含全部字段而 curr 仅含当前类型的字段子集，故逐键比较（整体 stringify 恒不相等）
    return Object.keys(curr).some((key) => curr[key] !== orig[key]);
  },

  onCancelSaveConfirm() {
    this.setData({ showSaveConfirmAlert: false });
  },

  async onDoSave() {
    this.setData({ showSaveConfirmAlert: false });

    const id = this.data.editTargetId;
    const isRepublish = this.data.editSource === POST_STATUS.DRAFT;
    const postType = this.data.editPostType;
    const isHelp = postType === POST_TYPE.HELP;

    const durationStr =
      this.data.editDurationOptions[this.data.editDurationIndex] || "7 天";
    const maxDuration = parseInt(durationStr) || 7;

    // 构建请求体（与后端 IdleItemRequest / HelpRequestDTO 对齐）
    const body = {
      title: this.data.editTitle.trim(),
      category:
        this.data.editCategory === "其他"
          ? this.data.editCustomType.trim()
          : this.data.editCategory,
      description: this.data.editDesc.trim(),
      maxDuration: maxDuration,
      durationUnit: this.data.editDurationUnit,
    };

    if (postType === POST_TYPE.LEND) {
      body.price = parseFloat(this.data.editPrice) || 0;
      body.pickupMethod = this.data.editPickupMethod;
      body.condition = this.data.editCondition;
    }

    if (isHelp) {
      body.isUrgent = this.data.editUrgency === "urgent";
      // 仅当开关开启且日期已填写时，才提交时间范围
      if (this.data.editEnableTimeRange && this.data.editTimeStartDate) {
        const sh =
          this.data.hourOptions[this.data.editTimeStartHour] || "09:00";
        body.timeStart = this.data.editTimeStartDate + " " + sh;
      }
      if (this.data.editEnableTimeRange && this.data.editTimeEndDate) {
        const eh = this.data.hourOptions[this.data.editTimeEndHour] || "18:00";
        body.timeEnd = this.data.editTimeEndDate + " " + eh;
      }
    }

    // 处理图片：已有 URL 直接保留，新增临时路径先上传
    try {
      const baseUrl = getApp().globalData.baseUrl || "";
      const origSet = new Set(this._editOriginalUrls || []);
      let imageUrls = [];
      for (const img of this.data.editImages) {
        // 去除 baseUrl 前缀，得到原始存储路径
        const stripped = img.startsWith(baseUrl) ? img.substring(baseUrl.length) : img;
        if (origSet.has(stripped)) {
          // 已有图片，保留原始路径
          imageUrls.push(stripped);
        } else {
          // 新选图片（本地临时路径），先上传
          const url = await api.upload('/api/common/upload', img);
          imageUrls.push(url);
        }
      }
      body.images = JSON.stringify(imageUrls);
    } catch (e) {
      wx.hideLoading();
      wx.showToast({ title: '图片上传失败', icon: 'none' });
      return;
    }

    // 调用后端 update API——后端会自动将 offline 重新上架为 online
    const endpoint = isHelp
      ? "/api/help-requests/" + id
      : "/api/idle-items/" + id;
    wx.showLoading({ title: isRepublish ? "重新发布中..." : "保存中..." });
    try {
      await api.put(endpoint, body);
      wx.hideLoading();

      // 从后端重新拉取发布列表，确保展示真实数据
      await this.loadMyPosts();

      wx.showToast({
        title: isRepublish ? "已重新发布" : "已保存",
        icon: "success",
      });
      this.onCloseEdit();
    } catch (err) {
      wx.hideLoading();
      wx.showToast({ title: (err && err.message) || "操作失败", icon: "none" });
      // 操作冲突（如编辑期间帖子已被别人申请），刷新列表同步状态
      this.loadMyPosts();
    }
  },

  // ================================================================
  // 审批弹层
  // ================================================================
  onApprovalDetail(e) {
    const id = e.currentTarget.dataset.id;
    const type = e.currentTarget.dataset.type;
    const keyMap = {
      borrow: "borrowApprovals",
      lend: "lendApprovals",
      help: "helpApprovals",
    };
    const key = keyMap[type] || "borrowApprovals";
    const list = this.data[key];
    const item = list.find((i) => String(i.id) === String(id));
    if (item) {
      this.setData({
        showApprovalSheet: true,
        approvalSheetItem: { ...item, approvalType: type },
      });
    }
  },

  onCloseApprovalSheet() {
    this.setData({ showApprovalSheet: false, approvalSheetItem: null });
  },

  // "同意"按钮 → 显示确认弹窗
  onConfirmApproval() {
    const item = this.data.approvalSheetItem;
    const type = item ? item.approvalType : "borrow";
    let body;
    if (type === "borrow") {
      body = "是否确认借入？"; // 借入：确认接受对方主动借给我的意向
    } else if (type === "lend") {
      body = "是否确认借出？"; // 借出：同意别人借走我发布的闲置
    } else {
      body = "是否确认接受帮助？";
    }
    this.setData({
      showConfirmAlert: true,
      confirmAction: "approve",
      confirmAlertTitle: "确认同意",
      confirmAlertBody: body,
    });
  },

  // "拒绝"按钮 → 显示确认弹窗
  onRejectClick() {
    const item = this.data.approvalSheetItem;
    const type = item ? item.approvalType : "borrow";
    let body;
    if (type === "borrow") {
      body = "是否确认拒绝借入？";
    } else if (type === "lend") {
      body = "是否确认拒绝借出？";
    } else {
      body = "是否确认拒绝接受帮助？";
    }
    this.setData({
      showConfirmAlert: true,
      confirmAction: "reject",
      confirmAlertTitle: "确认拒绝",
      confirmAlertBody: body,
      enableRejectReason: false,
      rejectReason: "",
    });
  },

  // 拒绝原因开关（参考 publish-idle 时间范围开关模式）
  onToggleRejectReason(e) {
    this.setData({ enableRejectReason: e.detail.value });
  },

  // 拒绝原因输入
  onRejectReasonInput(e) {
    this.setData({ rejectReason: e.detail.value });
  },

  onCancelConfirm() {
    this.setData({ showConfirmAlert: false });
  },

  // 确认 → 路由到同意或拒绝
  onDoConfirm() {
    if (this.data.confirmAction === "approve") {
      this.onDoApprove();
    } else {
      this.onDoReject();
    }
  },
  onDoApprove() {
    this.submitApproval(true);
  },

  // 确认拒绝
  onDoReject() {
    this.submitApproval(false);
  },

  /**
   * 提交审批到后端，成功后从服务端刷新审批列表（当前 tab）。
   * 借入(borrow) / 借出(lend) 都是同一条 BorrowRequest：PUT /api/borrow-requests/{id}/approve
   *   - borrow：我发布了需求借入（WANTED 帖），确认别人主动借给我的意向
   *   - lend：  我发布了闲置（LEND 帖），审批别人借走我物品的申请
   * 求助(help)：PUT /api/help-requests/applications/{helpApplicationId}/approve
   * 审批项的 id 即对应记录的主键（见后端 getApprovals 映射）。
   * 审批通过后物品进入"进行中"——用户在切换 tab 时自动加载最新数据。
   */
  async submitApproval(approved) {
    const item = this.data.approvalSheetItem;
    if (!item) return;
    const type = item.approvalType; // 'borrow' | 'lend' | 'help'
    const id = item.id;
    const url =
      type === "help"
        ? "/api/help-requests/applications/" + id + "/approve"
        : "/api/borrow-requests/" + id + "/approve";

    wx.showLoading({ title: "处理中...", mask: true });
    try {
      // 构建请求体，拒绝时如果开关开启且有理由则附带 reason
      const reqBody = { approved: approved };
      if (
        !approved &&
        this.data.enableRejectReason &&
        this.data.rejectReason &&
        this.data.rejectReason.trim()
      ) {
        reqBody.reason = this.data.rejectReason.trim();
      }
      await api.put(url, reqBody);
      wx.hideLoading();
      this.setData({
        showApprovalSheet: false,
        approvalSheetItem: null,
        showConfirmAlert: false,
      });
      wx.showToast({ title: approved ? "已同意" : "已拒绝", icon: "none" });

      // 从服务端重新拉取审批列表（当前 tab），确保列表反映真实状态
      // 审批通过后物品进入"进行中"——切换 tab 时会自动加载最新数据，无需在此预加载
      await Promise.all([
        this.loadApprovals("borrow"),
        this.loadApprovals("lend"),
        this.loadApprovals("help"),
      ]);
    } catch (e) {
      wx.hideLoading();
      this.setData({ showConfirmAlert: false });
      wx.showToast({
        title: (e && e.message) || "操作失败，请重试",
        icon: "none",
      });
      // 操作冲突（如已被其他人处理），刷新列表同步最新状态
      this._loadApprovalBadge();
    }
  },

  doReject(type, id) {
    const key = type === "borrow" ? "borrowApprovals" : "helpApprovals";
    const list = this.data[key];
    const idx = list.findIndex((item) => item.id === id);
    if (idx >= 0) {
      list[idx].status = BORROW_STATUS.REJECTED;
      this.setData({ [key]: list });
    }
    wx.showToast({ title: "已拒绝", icon: "none" });
  },

  // ================================================================
  // 进行中详情弹层
  // ================================================================
  onOpenProgressSheet(e) {
    const id = e.currentTarget.dataset.id;
    const type = e.currentTarget.dataset.type;
    const keyMap = {
      borrow: "inProgressBorrows",
      lend: "inProgressLends",
      helpReq: "inProgressHelpReqs",
      helpPro: "inProgressHelpPros",
    };
    const key = keyMap[type];
    if (!key) return;
    const list = this.data[key];
    const item = list.find((i) => String(i.id) === String(id));
    if (item) {
      this.setData({
        showProgressSheet: true,
        progressSheetItem: { ...item },
        progressSheetType: type,
        progressSheetRating: 0,
        progressSheetFeedback: "",
        progressSheetCondition: "normal",
      });
    }
  },

  onCloseProgressSheet() {
    this.setData({
      showProgressSheet: false,
      progressSheetItem: null,
      progressSheetType: "",
      progressSheetRating: 0,
      progressSheetFeedback: "",
      progressSheetCondition: "",
    });
  },

  onProgressRatingTap(e) {
    this.setData({ progressSheetRating: e.currentTarget.dataset.star });
  },

  onProgressFeedbackInput(e) {
    this.setData({ progressSheetFeedback: e.detail.value });
  },

  /**
   * 智能生成互助感想 — 根据互助背景调用 AI 生成口语化评价文本。
   *
   * <p>传入角色（借入/借出/求助/帮忙）、物品标题、归还/损坏情况、用户已有草稿，
   * AI 生成 20-60 字的自然积极评价，直接填入对应输入框。</p>
   */
  async onGenerateFeedback(e) {
    if (this.data._generatingFeedback) return;

    const source = e.currentTarget.dataset.source;
    const item = source === 'progress'
      ? this.data.progressSheetItem
      : this.data.completedSheetItem;
    const type = source === 'progress'
      ? this.data.progressSheetType
      : (item && item.type);
    const existingText = source === 'progress'
      ? (this.data.progressSheetFeedback || '').trim()
      : (this.data.completedFeedback || '').trim();

    if (!item || !type) return;

    // 已有文字时弹确认提示
    if (existingText) {
      const confirmRes = await new Promise(resolve => {
        wx.showModal({
          title: '智能生成',
          content: '当前已填写内容将被修改，确认生成？',
          success: r => resolve(r.confirm)
        });
      });
      if (!confirmRes) return;
    }

    // 构建背景描述
    const bgParts = [];
    if (item.isOverdue) {
      bgParts.push('逾期归还');
    } else if (item.remainingDays !== undefined && item.remainingDays >= 0) {
      bgParts.push('按时归还');
    }
    // 物品状况（仅借出场景）
    if (type === 'lend' && item.condition) {
      const condMap = {};
      condMap[DAMAGE_TYPE.NORMAL] = '物品正常损耗';
      condMap[DAMAGE_TYPE.ABNORMAL] = '物品有非正常损坏';
      condMap[DAMAGE_TYPE.BROKEN] = '物品完全损坏';
      if (condMap[item.condition]) bgParts.push(condMap[item.condition]);
    }
    let bgDesc = bgParts.join('，');
    // 已有文字作为参考传给 AI
    if (existingText) {
      bgDesc += (bgDesc ? '。' : '') + '用户草稿：' + existingText;
    }

    this.setData({ _generatingFeedback: true });
    wx.showLoading({ title: '生成中...', mask: true });

    try {
      const result = await api.post('/api/common/polish', {
        mode: 'feedback',
        role: type,
        itemTitle: item.itemTitle || '',
        description: bgDesc
      });
      wx.hideLoading();
      const feedback = result.feedback || '';
      if (source === 'progress') {
        this.setData({ progressSheetFeedback: feedback, _generatingFeedback: false });
      } else {
        this.setData({ completedFeedback: feedback, _generatingFeedback: false });
      }
    } catch (e) {
      wx.hideLoading();
      this.setData({ _generatingFeedback: false });
      wx.showToast({ title: e.message || '生成失败，请重试', icon: 'none' });
    }
  },

  /** 键盘弹起/收起：计算弹框 scroll-view 底部 padding，使 textarea 刚好露出键盘上方 */
  onKeyboardHeightChange(e) {
    const h = e.detail.height;
    // 键盘高度减去 textarea 到弹框底部的自然距离（footer + 间距 ≈ 100px），避免过度上挤
    const pad = h > 0 ? Math.max(0, h - 140) : 0;
    this.setData({ keyboardHeight: h, sheetPaddingBottom: pad });
  },

  onProgressConditionTap(e) {
    this.setData({ progressSheetCondition: e.currentTarget.dataset.value });
  },

  // "归还/结束"按钮 → 显示确认弹窗
  onConfirmReturn() {
    if (!this.data.progressSheetRating) {
      wx.showToast({ title: "请先评分", icon: "none" });
      return;
    }
    // 借出：使用后物品状况必填
    if (
      this.data.progressSheetType === "lend" &&
      !this.data.progressSheetCondition
    ) {
      wx.showToast({ title: "请选择使用后物品状况", icon: "none" });
      return;
    }
    this.setData({
      showReturnConfirmAlert: true,
      returnConfirmData: {
        item: this.data.progressSheetItem,
        type: this.data.progressSheetType,
      },
    });
  },

  onCancelReturnConfirm() {
    this.setData({ showReturnConfirmAlert: false, returnConfirmData: null });
  },

  // 已确认 → 执行归还/结束（调用后端，双方状态由「进行中」进入「已完成」）
  async onDoReturn() {
    const data = this.data.returnConfirmData;
    if (!data) return;
    const { item, type } = data;
    const isHelp = type === "helpReq" || type === "helpPro";
    const isOverdue = item.isOverdue;
    const rating = this.data.progressSheetRating;
    const feedback = this.data.progressSheetFeedback;

    wx.showLoading({ title: "处理中...", mask: true });
    try {
      // 1. 状态流转：归还 / 结束
      if (isHelp) {
        await api.put(
          "/api/help-requests/applications/" + item.id + "/complete",
          {},
        );
      } else {
        const returnBody = {
          returnStatus: RETURN_STATUS.ON_TIME,
          isOnTime: !isOverdue,
        };
        if (type === "lend") {
          returnBody.damageType = this.data.progressSheetCondition;
        }
        await api.put(
          "/api/borrow-requests/" + item.id + "/return",
          returnBody,
        );
      }

      // 2. 提交评分（互助双方均需评价对方；后端按发起人身份确定被评人）
      if (rating) {
        try {
          const ratingBody = {
            targetId: item.id,
            ratingType: isHelp ? "help" : "borrow",
            overallScore: rating,
          };
          if (feedback) ratingBody.feedback = feedback;
          await api.post("/api/ratings", ratingBody);
          // 通知服务通知页该 relatedId 已评价
          const app = getApp();
          if (!app.globalData.ratedNotificationIds) {
            app.globalData.ratedNotificationIds = [];
          }
          if (!app.globalData.ratedNotificationIds.includes(item.id)) {
            app.globalData.ratedNotificationIds.push(item.id);
          }
        } catch (re) {
          // 评价失败不阻断归还主流程（例如已评价过）
          console.warn("Submit rating failed:", re && re.message);
        }
      }

      wx.hideLoading();
      this.setData({
        showProgressSheet: false,
        progressSheetItem: null,
        progressSheetType: "",
        progressSheetRating: 0,
        progressSheetFeedback: "",
        progressSheetCondition: "",
        showReturnConfirmAlert: false,
        returnConfirmData: null,
      });

      // 3. 刷新当前 tab（进行中）——归还后物品从进行中消失
      const reloads = isHelp
        ? [this.loadInProgress("helpReq"), this.loadInProgress("helpPro")]
        : [this.loadInProgress("borrow"), this.loadInProgress("lend")];
      await Promise.all(reloads);

      // 归还/结束后产生新的已完成记录，静默刷新待评价黄点
      this._refreshCompletedBadge();

      if (isOverdue && !isHelp && type === "borrow") {
        this.setData({ showOverdueTipAlert: true });
      } else {
        wx.showToast({
          title: isHelp
            ? "已结束"
            : type === "lend"
              ? "已确认对方归还"
              : "已归还",
          icon: "none",
        });
      }
    } catch (e) {
      wx.hideLoading();
      this.setData({ showReturnConfirmAlert: false, returnConfirmData: null });
      wx.showToast({
        title: (e && e.message) || "操作失败，请重试",
        icon: "none",
      });
      // 操作冲突，刷新当前列表同步最新状态
      this._loadCurrentTabAndBadge({ isRefresh: true });
    }
  },

  onCloseOverdueTip() {
    this.setData({ showOverdueTipAlert: false });
    wx.showToast({ title: "已归还，请注意按时归还", icon: "none" });
  },

  // ================================================================
  // 已完成详情弹层
  // ================================================================
  onCompletedDetail(e) {
    const id = e.currentTarget.dataset.id;
    const type = e.currentTarget.dataset.type;
    const keyMap = {
      borrow: "completedBorrows",
      lend: "completedLends",
      helpReq: "completedHelpReqs",
      helpPro: "completedHelpPros",
    };
    const key = keyMap[type];
    if (!key) return;
    const list = this.data[key];
    const item = list.find((i) => String(i.id) === String(id));
    if (item) {
      this.setData({
        showCompletedSheet: true,
        completedSheetItem: { ...item },
        completedRating: 0,
        completedFeedback: "",
      });
    }
  },

  onCloseCompletedSheet() {
    this.setData({
      showCompletedSheet: false,
      completedSheetItem: null,
      completedRating: 0,
      completedFeedback: "",
    });
  },

  // 已完成页补充物品状况（借出方在已完成记录中补填）
  onCompletedDamageTap(e) {
    this.setData({ completedDamageType: e.currentTarget.dataset.value });
  },

  // 已完成页补充评价（供未在归还时评分的一方评价对方）
  onCompletedRatingTap(e) {
    this.setData({ completedRating: e.currentTarget.dataset.star });
  },

  onCompletedFeedbackInput(e) {
    this.setData({ completedFeedback: e.detail.value });
  },

  async onSubmitCompletedRating() {
    const item = this.data.completedSheetItem;
    if (!item) return;
    if (!this.data.completedRating) {
      wx.showToast({ title: "请先评分", icon: "none" });
      return;
    }
    const isHelp = item.type === "helpReq" || item.type === "helpPro";
    const isLendMissingDamage = item.type === "lend" && !item.damageType;

    wx.showLoading({ title: "提交中...", mask: true });
    try {
      // 借出方尚未填写物品状况：先补填 damageType
      if (isLendMissingDamage) {
        await api.put("/api/borrow-requests/" + item.id + "/damage", {
          damageType: this.data.completedDamageType,
        });
      }
      const ratingBody2 = {
        targetId: item.id,
        ratingType: isHelp ? "help" : "borrow",
        overallScore: this.data.completedRating,
      };
      if (this.data.completedFeedback)
        ratingBody2.feedback = this.data.completedFeedback;
      await api.post("/api/ratings", ratingBody2);
      wx.hideLoading();
      // 刷新该角色的已完成列表并回填当前弹框
      await this.loadCompleted(item.type);
      const keyMap = {
        borrow: "completedBorrows",
        lend: "completedLends",
        helpReq: "completedHelpReqs",
        helpPro: "completedHelpPros",
      };
      const fresh = (this.data[keyMap[item.type]] || []).find(
        (i) => String(i.id) === String(item.id),
      );
      this.setData({
        completedSheetItem: fresh ? { ...fresh } : this.data.completedSheetItem,
        completedRating: 0,
        completedDamageType: "normal",
        completedFeedback: "",
      });
      // 通知服务通知页：该 relatedId 已评价，卡片应改为「已评价」
      const app = getApp();
      if (!app.globalData.ratedNotificationIds) {
        app.globalData.ratedNotificationIds = [];
      }
      if (!app.globalData.ratedNotificationIds.includes(item.id)) {
        app.globalData.ratedNotificationIds.push(item.id);
      }
      wx.showToast({ title: "评价成功", icon: "none" });
    } catch (e) {
      wx.hideLoading();
      wx.showToast({
        title: (e && e.message) || "评价失败，请重试",
        icon: "none",
      });
    }
  },

  // ================================================================
  // 进行中操作（旧版保留作为兜底，现已通过弹层路由）
  // ================================================================

  // 归还 — 借入
  onReturnBorrow(e) {
    const id = e.currentTarget.dataset.id;
    const type = "borrow";
    const item = this.data.inProgressBorrows.find((i) => String(i.id) === String(id));
    if (item) {
      this.setData({
        showProgressSheet: true,
        progressSheetItem: { ...item },
        progressSheetType: type,
        progressSheetRating: 0,
        progressSheetFeedback: "",
        progressSheetCondition: "",
      });
    }
  },

  // 归还 — 借出
  onReturnLend(e) {
    const id = e.currentTarget.dataset.id;
    const type = "lend";
    const item = this.data.inProgressLends.find((i) => String(i.id) === String(id));
    if (item) {
      this.setData({
        showProgressSheet: true,
        progressSheetItem: { ...item },
        progressSheetType: type,
        progressSheetRating: 0,
        progressSheetFeedback: "",
        progressSheetCondition: "normal",
      });
    }
  },

  // 结束 — 求助
  onEndHelpReq(e) {
    const id = e.currentTarget.dataset.id;
    const type = "helpReq";
    const item = this.data.inProgressHelpReqs.find((i) => String(i.id) === String(id));
    if (item) {
      this.setData({
        showProgressSheet: true,
        progressSheetItem: { ...item },
        progressSheetType: type,
        progressSheetRating: 0,
        progressSheetFeedback: "",
        progressSheetCondition: "",
      });
    }
  },

  // 结束 — 帮助
  onEndHelpPro(e) {
    const id = e.currentTarget.dataset.id;
    const type = "helpPro";
    const item = this.data.inProgressHelpPros.find((i) => String(i.id) === String(id));
    if (item) {
      this.setData({
        showProgressSheet: true,
        progressSheetItem: { ...item },
        progressSheetType: type,
        progressSheetRating: 0,
        progressSheetFeedback: "",
        progressSheetCondition: "",
      });
    }
  },

  // ================================================================
  // 触摸事件拦截 — 防止穿透滚动到页面 scroll-view
  // ================================================================
  preventTouchMove() {},

  // 点击弹框中的住户地址 → 跳转与对方聊天
  onTapChatWithUser(e) {
    const userId =
      e.currentTarget.dataset.userId || e.currentTarget.dataset.userId;
    const userName = e.currentTarget.dataset.userName || "";
    if (!userId) return;
    const myId = auth.getUserId();
    if (!myId || String(myId) === String(userId)) {
      wx.showToast({ title: "不能与自己聊天", icon: "none" });
      return;
    }
    const ids = [String(myId), String(userId)].sort();
    const sessionId = "USER_" + ids[0] + "_" + ids[1];
    const name = encodeURIComponent(userName || "用户");
    wx.navigateTo({
      url: `/pages/chat/chat?sessionId=${sessionId}&name=${name}&room=&about=&aboutId=&aboutType=&otherUserId=${userId}`,
    });
  },

  // ================================================================
  // 辅助函数
  // ================================================================
  getApplyStatusText(status) {
    const map = {
      [BORROW_STATUS.PENDING]: "待处理",
      [BORROW_STATUS.APPROVED]: "已同意",
      [BORROW_STATUS.REJECTED]: "已拒绝",
      [BORROW_STATUS.COMPLETED]: "已完成",
      [BORROW_STATUS.CANCELLED]: "已取消",
    };
    return map[status] || status || "待处理";
  },

  getApplyStatusClass(status) {
    const map = {
      [BORROW_STATUS.PENDING]: "post-status-tag-orange",
      [BORROW_STATUS.APPROVED]: "post-status-tag-green",
      [BORROW_STATUS.REJECTED]: "post-status-tag-red",
      [BORROW_STATUS.COMPLETED]: "post-status-tag-green",
      [BORROW_STATUS.CANCELLED]: "post-status-tag-fill",
    };
    return map[status] || "post-status-tag-orange";
  },

  formatTime(timestamp) {
    if (!timestamp) return "";
    const date = new Date(timestamp);
    const M = (date.getMonth() + 1).toString().padStart(2, "0");
    const d = date.getDate().toString().padStart(2, "0");
    const h = date.getHours().toString().padStart(2, "0");
    const mi = date.getMinutes().toString().padStart(2, "0");
    return M + "-" + d + " " + h + ":" + mi;
  },

  formatDate(timestamp) {
    if (!timestamp) return "";
    const date = new Date(timestamp);
    const Y = date.getFullYear();
    const M = (date.getMonth() + 1).toString().padStart(2, "0");
    const d = date.getDate().toString().padStart(2, "0");
    return Y + "-" + M + "-" + d;
  },

  // 将 ISO 日期时间字符串格式化为 "yyyy-MM-dd HH:mm" 展示格式
  formatDateTime(dt) {
    if (!dt) return "";
    const d = new Date(dt);
    if (isNaN(d.getTime())) return "";
    const Y = d.getFullYear();
    const M = (d.getMonth() + 1).toString().padStart(2, "0");
    const DD = d.getDate().toString().padStart(2, "0");
    const h = d.getHours().toString().padStart(2, "0");
    const mi = d.getMinutes().toString().padStart(2, "0");
    return Y + "-" + M + "-" + DD + " " + h + ":" + mi;
  },

  formatRelativeTime(timestamp) {
    if (!timestamp) return "";
    const now = Date.now();
    const dt = new Date(timestamp).getTime();
    const diff = now - dt;
    const minutes = Math.floor(diff / 60000);
    const hours = Math.floor(diff / 3600000);
    const days = Math.floor(diff / 86400000);

    if (minutes < 1) return "刚刚";
    if (minutes < 60) return minutes + "分钟前";
    if (hours < 24) return hours + "小时前";
    if (days < 7) return days + "天前";
    if (days < 30) return Math.floor(days / 7) + "周前";
    return this.formatDate(timestamp);
  },

  /**
   * 前端安全兜底：清洗可能为 JSON 的反馈文本。
   * 后端也会做此处理，此处作为边缘情况补充。
   */
  sanitizeFeedback(text) {
    if (!text) return "";
    const trimmed = String(text).trim();
    // 非 JSON 形式时原样返回
    if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
      return trimmed;
    }
    try {
      const parsed = JSON.parse(trimmed);
      if (Array.isArray(parsed)) {
        // 数字数组 → 只有评分没有评价文字，返回空
        if (parsed.length > 0 && typeof parsed[0] === "number") return "";
        // 字符串数组 → 拼接
        return parsed
          .filter(function (v) {
            return typeof v === "string" && v.length > 0;
          })
          .join("；");
      }
      if (typeof parsed === "object" && parsed !== null) {
        // 对象 → 提取文本值（跳过纯数字评分）
        var parts = [];
        Object.values(parsed).forEach(function (v) {
          var sv = String(v);
          if (sv.length > 2 && !/^\d+(\.\d+)?$/.test(sv)) {
            parts.push(sv);
          }
        });
        return parts.length > 0 ? parts.join("；") : "";
      }
    } catch (e) {
      // 不是合法 JSON——可能是 Java Map.toString() 格式 {key=value}
      if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
        var inner = trimmed.slice(1, -1);
        var pairs = inner.split(/,\s*/);
        var parts = [];
        pairs.forEach(function (pair) {
          var eqIdx = pair.indexOf("=");
          if (eqIdx >= 0) {
            var value = pair.substring(eqIdx + 1).trim();
            if (value.length > 2 && !/^\d+(\.\d+)?$/.test(value)) {
              parts.push(value);
            }
          }
        });
        return parts.length > 0 ? parts.join("；") : "";
      }
    }
    // 全部提取失败时返回空（不展示原始 JSON）
    return "";
  },
});
