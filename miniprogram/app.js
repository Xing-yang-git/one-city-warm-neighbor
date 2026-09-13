App({
  globalData: {
    // 使用局域网IP而非localhost：手机预览时localhost指向手机自身，无法访问PC后端
    // PC端开发工具中 192.168.x.x 同样可用（本机回环）
    // 注意：IP 随 DHCP 变动——换网络后若首页转圈，先 ipconfig 查当前 IPv4 更新此处
    baseUrl: "http://192.168.43.174:8080",
    token: "",
    userId: "",
    userInfo: null,
    // ===== 全局 WebSocket 状态 =====
    socketOpen: false,
    unreadBySession: {}, // { sessionId: count } — 每个会话的未读消息数
    activeSessionId: null, // 当前正在查看的会话（不累计未读）
    pendingApprovalTotal: 0, // 待审批总数 — 驱动「管理」tab 红点
    noticeUnreadCount: 0, // 服务通知未读数 — 合并进「消息」tab 红点
  },

  /** 会话级消息回调注册表：sessionId → [callback] */
  _msgCallbacks: {},
  /** 全局消息回调（消息列表页等需要感知任意新消息的页面） */
  _globalMsgCallbacks: [],

  onLaunch() {
    // ============================================================
    // 局域网开发调试说明：
    // 预览模式下微信强制校验请求域名白名单，局域网IP无法加入白名单。
    // 请使用以下任一方式绕过：
    //   1. 真机调试模式（自动代理，推荐）
    //   2. IDE 生成预览二维码时勾选「不校验合法域名」
    // 不要在代码中调用 wx.setEnableDebug —— 会触发微信广告调优系统的
    // operateWXData:fail invalid scope 错误（框架内部行为，无法捕获）。
    // ============================================================

    const token = wx.getStorageSync("token");
    if (token) {
      this.globalData.token = token;
      // token 恢复后尝试建立全局 WS（需要 userId，延迟到 onShow 或首次 ensure 调用）
    }
  },

  onShow() {
    // 注册页与登录页不需要 WebSocket：注册页进入相册选图切后台再返回时，
    // 残留旧 token 可能触发 WS 被服务器拒绝（code 4001）→ forceRelogin → 退回登录页
    const pages = getCurrentPages();
    if (pages.length > 0) {
      const currentRoute = pages[pages.length - 1].route;
      if (
        currentRoute === "pages/register/register" ||
        currentRoute === "pages/login/login"
      ) {
        return;
      }
    }

    // 应用从后台切回前台时，确保 WS 已连接，并刷新「管理」tab 待审批红点与「消息」tab 通知红点
    if (this.globalData.token) {
      this.ensureWebSocket();
      this.refreshManageBadge();
      this.refreshNoticeBadge();
    }
  },

  // ================================================================
  // 全局 WebSocket — 应用级单例，始终在线收消息
  // ================================================================

  /** 确保全局 WS 已连接。若旧连接实际已断开（_socketTask 存在但 socketOpen=false）则清理后重连。 */
  ensureWebSocket() {
    // 旧连接存在但已断开：清理引用以便重连（onError 可能导致 socketOpen=false 但 _socketTask 未置 null）
    if (this._socketTask && !this.globalData.socketOpen) {
      try {
        this._socketTask.close({});
      } catch (e) {}
      this._socketTask = null;
    }
    if (this._socketTask) return; // 已存在且连接正常

    const token = this.globalData.token || wx.getStorageSync("token");
    if (!token) return;

    // 延迟加载以避免循环依赖
    const auth = require("./utils/auth");
    const userId = auth.getUserId();
    if (!userId) return;

    this.globalData.userId = String(userId);

    const wsBase = this.globalData.baseUrl.replace(/^http/, "ws");
    const wsUrl = wsBase + "/ws/chat?token=" + encodeURIComponent(token);

    this._socketTask = wx.connectSocket({ url: wsUrl });

    this._socketTask.onOpen(() => {
      this.globalData.socketOpen = true;
      // 连接/重连后立即补拉未读数，弥补断连期间错过的服务通知
      this.refreshNoticeBadge();
      console.log("[WS] 全局聊天 WebSocket 已连接, userId=" + userId);
    });

    this._socketTask.onMessage((res) => {
      try {
        const msg = JSON.parse(res.data);

        // 通知类型：直接推送给全局监听器（service-notice 页面），并刷新 tabBar 红点
        if (msg.type === "notification") {
          let notification;
          try {
            notification = JSON.parse(msg.content);
          } catch (e) {
            notification = msg;
          }
          this.globalData.noticeUnreadCount =
            (this.globalData.noticeUnreadCount || 0) + 1;
          this._updateTabBarBadge();
          this._globalMsgCallbacks.forEach((cb) => {
            try {
              cb({ type: "notification", data: notification });
            } catch (e) {
              console.error(e);
            }
          });
          return;
        }

        // 撤回消息：更新本地存储并通知会话回调
        if (msg.type === "chat_recall") {
          const recallUid = String(this.globalData.userId);
          const recallSid = msg.sessionId;
          if (recallUid && recallSid) {
            const rk = "chat_msgs_" + recallUid + "_" + recallSid;
            try {
              let existing = wx.getStorageSync(rk) || [];
              const updated = existing.map((m) =>
                m.id === msg.id ? { ...m, recalled: true, content: "" } : m,
              );
              wx.setStorageSync(rk, updated);
            } catch (e) {
              /* ignore */
            }
            // 通知会话回调
            const cbs = this._msgCallbacks[recallSid];
            if (cbs && cbs.length > 0) {
              cbs.forEach((cb) => {
                try {
                  cb({
                    type: "recall",
                    id: msg.id,
                    fromUserId: msg.fromUserId,
                  });
                } catch (e) {}
              });
            }
          }
          return;
        }

        if (msg.type !== "chat_message") return;
        if (!msg.sessionId) return;

        const uid = String(this.globalData.userId);
        const fromSelf = String(msg.fromUserId) === uid;

        // 自己的消息回显（echo）：发送方已通过 POST /api/chats/send 响应拿到真实 id，跳过
        if (fromSelf) return;

        // 落盘：保存到该会话的本地存储（作为 chat_cache 的补充）
        const storageKey = "chat_msgs_" + uid + "_" + msg.sessionId;
        let existing = [];
        try {
          existing = wx.getStorageSync(storageKey) || [];
        } catch (e) {
          /* ignore */
        }

        const now = Date.now();
        const serverTs = msg.createdAt
          ? new Date(msg.createdAt).getTime()
          : now;

        // 处理 voice/image 消息类型，解析管道符编码的语音时长
        let msgType =
          msg.messageType === "system" ? "system" : msg.messageType || "text";
        let voiceDuration = 0;
        let processedContent = msg.content;
        if (msgType === "voice" && processedContent) {
          const idx = processedContent.indexOf("|");
          if (idx > 0) {
            voiceDuration = parseInt(processedContent.substring(0, idx)) || 0;
            processedContent = processedContent.substring(idx + 1);
          }
        }

        const newMsg = {
          id: msg.id || "ws_" + now,
          content: processedContent || "",
          isMine: false,
          type: msgType,
          systemIcon: msgType === "system" ? "ⓘ" : "",
          timestamp: serverTs,
          showTime:
            existing.length === 0 ||
            serverTs - (existing[existing.length - 1].timestamp || 0) > 300000,
          timeText: this._formatWsTime(new Date(serverTs)),
          status: "sent",
          recalled: false,
          sendFailed: false,
          voiceDuration: voiceDuration,
          voicePlayed: false,
        };

        const updated = [...existing, newMsg];
        try {
          wx.setStorageSync(storageKey, updated);
        } catch (e) {
          console.error("[WS] save failed:", e);
        }

        // 保存/更新会话元数据（保证接收方在消息列表中看到正确的发送方名称）
        if (msg.fromUserName) {
          const metaKey = "chat_meta_" + uid + "_" + msg.sessionId;
          try {
            let meta = wx.getStorageSync(metaKey) || {};
            // 只在元数据缺失或不完整时写入，避免覆盖已有的 about 等详情
            if (!meta.otherName || meta.otherName === "用户") {
              meta.otherName = msg.fromUserName;
            }
            if (!meta.otherUserId) {
              meta.otherUserId = msg.fromUserId;
            }
            wx.setStorageSync(metaKey, meta);
          } catch (e) {
            /* ignore */
          }
        }

        // 未读计数：非自己发的 && 不是当前正在查看的会话
        if (!fromSelf && msg.sessionId !== this.globalData.activeSessionId) {
          const counts = this.globalData.unreadBySession;
          counts[msg.sessionId] = (counts[msg.sessionId] || 0) + 1;
          this._updateTabBarBadge();
        }

        // 通知注册到该会话的回调（chat.js 页面实时刷新）
        const cbs = this._msgCallbacks[msg.sessionId];
        if (cbs && cbs.length > 0) {
          cbs.forEach((cb) => {
            try {
              cb(newMsg);
            } catch (e) {
              console.error(e);
            }
          });
        }

        // 通知全局监听器（消息列表页实时刷新会话列表）
        this._globalMsgCallbacks.forEach((cb) => {
          try {
            cb(msg);
          } catch (e) {
            console.error(e);
          }
        });
      } catch (e) {
        console.error("[WS] 消息解析失败:", e);
      }
    });

    this._socketTask.onError((err) => {
      console.error("[WS] 连接错误:", err);
      this.globalData.socketOpen = false;
      this._socketTask = null; // 清理引用，确保 ensureWebSocket() 可重建连接
    });

    this._socketTask.onClose((res) => {
      // code 4001：被新登录挤下线
      if (res && res.code === 4001) {
        const api = require("./utils/api");
        api.forceRelogin();
      }
      this.globalData.socketOpen = false;
      this._socketTask = null;
      // 自动重连（3 秒后）
      setTimeout(() => {
        if (!this._socketTask) this.ensureWebSocket();
      }, 3000);
    });
  },

  // ================================================================
  // 会话级回调 — chat.js 用于接收实时消息刷新页面
  // ================================================================

  /** 注册会话消息回调。chat.js onLoad 时调用。 */
  onSessionMessage(sessionId, callback) {
    if (!this._msgCallbacks[sessionId]) {
      this._msgCallbacks[sessionId] = [];
    }
    this._msgCallbacks[sessionId].push(callback);
  },

  /** 注册全局消息回调（消息列表页用于实时刷新）。 */
  onGlobalMessage(callback) {
    if (typeof callback === "function") {
      this._globalMsgCallbacks.push(callback);
    }
  },

  /** 取消注册全局消息回调。 */
  offGlobalMessage(callback) {
    const idx = this._globalMsgCallbacks.indexOf(callback);
    if (idx >= 0) this._globalMsgCallbacks.splice(idx, 1);
  },

  /** 取消注册会话消息回调。chat.js onUnload 时调用。 */
  offSessionMessage(sessionId, callback) {
    const cbs = this._msgCallbacks[sessionId];
    if (cbs) {
      const idx = cbs.indexOf(callback);
      if (idx >= 0) cbs.splice(idx, 1);
      if (cbs.length === 0) delete this._msgCallbacks[sessionId];
    }
  },

  // ================================================================
  // 未读消息管理
  // ================================================================

  /** 标记会话已读（chat.js onLoad 时调用），清除该会话未读计数。 */
  markSessionRead(sessionId) {
    this.globalData.activeSessionId = sessionId;
    if (this.globalData.unreadBySession[sessionId]) {
      delete this.globalData.unreadBySession[sessionId];
      this._updateTabBarBadge();
    }
  },

  /** 离开聊天页时清除活跃会话标记。 */
  clearActiveSession() {
    this.globalData.activeSessionId = null;
  },

  /** 获取某个会话的未读消息数。 */
  getSessionUnread(sessionId) {
    return this.globalData.unreadBySession[sessionId] || 0;
  },

  /** 获取全部未读消息总数。 */
  getTotalUnread() {
    return Object.values(this.globalData.unreadBySession).reduce(
      (a, b) => a + b,
      0,
    );
  },

  /** 更新 tabBar 消息 tab（索引 2）的未读提示（聊天 + 服务通知）。 */
  _updateTabBarBadge() {
    const total =
      this.getTotalUnread() + (this.globalData.noticeUnreadCount || 0);
    if (total > 0) {
      wx.showTabBarRedDot({ index: 2 }).catch(() => {});
    } else {
      wx.hideTabBarRedDot({ index: 2 }).catch(() => {});
    }
  },

  // ================================================================
  // 「管理」tab 红点 — 待审批事项提示
  // ================================================================

  /** 设置「管理」tab（索引 1）红点并缓存待审批总数。my-posts 页加载后以本地列表精确同步。 */
  setManageBadge(total) {
    this.globalData.pendingApprovalTotal = total || 0;
    if (this.globalData.pendingApprovalTotal > 0) {
      wx.showTabBarRedDot({ index: 1 }).catch(() => {});
    } else {
      wx.hideTabBarRedDot({ index: 1 }).catch(() => {});
    }
  },

  /** 从服务端刷新待审批总数并更新红点。红点非关键路径，请求失败静默忽略。 */
  refreshManageBadge() {
    if (!this.globalData.token && !wx.getStorageSync("token")) return;
    const api = require("./utils/api");
    api
      .get("/api/users/approvals/count")
      .then((res) => {
        this.setManageBadge(res && res.total ? res.total : 0);
      })
      .catch(() => {});
  },

  /** 从服务端刷新服务通知未读数并更新 tabBar「消息」红点。 */
  refreshNoticeBadge() {
    if (!this.globalData.token && !wx.getStorageSync("token")) return;
    const api = require("./utils/api");
    api
      .get("/api/notifications/unread-count")
      .then((res) => {
        this.globalData.noticeUnreadCount =
          typeof res === "number" ? res : (res && res.count) || 0;
        this._updateTabBarBadge();
      })
      .catch(() => {});
  },

  /** WebSocket 消息时间格式化。 */
  _formatWsTime(date) {
    const h = date.getHours().toString().padStart(2, "0");
    const m = date.getMinutes().toString().padStart(2, "0");
    return h + ":" + m;
  },
});
