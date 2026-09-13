const api = require('../../utils/api');
const auth = require('../../utils/auth');
const app = getApp();

/**
 * 消息列表页 — 聊天会话列表。
 *
 * 功能：展示所有活跃聊天会话、未读消息提示、点击进入对应聊天页。
 */
Page({
  data: {
    sessions: []
  },

  onLoad() {
    if (!auth.ensureAccess()) return;   // 登录/审核门禁：未通过则已跳转
    app.ensureWebSocket(); // 确保全局 WS 已连接
    this.loadSessions();
  },

  onShow() {
    if (!auth.ensureAccess()) return; // 登录/审核门禁：覆盖 tab 切换与后台切回
    app.ensureWebSocket(); // 从后台切回时确保 WS 连接
    this.loadSessions();
    // 回到 tab 页时刷新 badge（从聊天页返回时，tabBar 才可见，此时移除 badge 才生效）
    app._updateTabBarBadge();
    // 注册全局消息回调，实时刷新会话列表
    if (!this._globalMsgHandler) {
      this._globalMsgHandler = () => { this.loadSessions(); };
    }
    app.onGlobalMessage(this._globalMsgHandler);
  },

  onHide() {
    // 离开消息页时取消全局消息监听
    if (this._globalMsgHandler) {
      app.offGlobalMessage(this._globalMsgHandler);
    }
  },

  onPullDownRefresh() {
    this.loadSessions().then(() => {
      wx.stopPullDownRefresh();
    });
  },

  async loadSessions() {
    const token = wx.getStorageSync('token');
    if (!token) {
      this.setData({ sessions: [] });
      return;
    }
    const uid = auth.getUserId();
    if (!uid) { this.setData({ sessions: [] }); return; }
    try {
      // 从本地存储构建会话列表（不再从服务端获取；键按用户命名空间隔离）
      const MSG_PREFIX = 'chat_msgs_' + uid + '_';
      const META_PREFIX = 'chat_meta_' + uid + '_';
      const sessions = [];
      try {
        const info = wx.getStorageInfoSync();
        info.keys.forEach(key => {
          // 一次性清理旧格式键（无用户前缀，无法归属账号，直接删除）
          if ((key.startsWith('chat_msgs_') || key.startsWith('chat_meta_')) && !/^\d+_/.test(key.substring(10))) {
            wx.removeStorageSync(key);
            return;
          }
          if (key.startsWith(MSG_PREFIX)) {
            const sessionId = key.substring(MSG_PREFIX.length);
            // 读取最后一条消息
            const messages = wx.getStorageSync(key) || [];
            const lastMsg = messages.length > 0 ? messages[messages.length - 1] : null;
            // 读取会话元数据
            const meta = wx.getStorageSync(META_PREFIX + sessionId) || {};
            if (lastMsg || meta.otherName) {
              const unreadCount = app.getSessionUnread(sessionId);
              const lastMsgType = lastMsg ? (lastMsg.type || 'text') : 'text';
              sessions.push({
                id: sessionId,
                otherName: meta.otherName || '用户',
                avatarText: (meta.otherName || '?')[0],
                avatarColor: this.getAvatarColor(sessionId),
                lastMessage: this.formatPreview(lastMsg),
                lastMsgType: lastMsgType,
                timeText: lastMsg ? this.formatRelativeTime(lastMsg.timestamp) : '',
                roomInfo: meta.room || '',
                aboutTitle: meta.about || '',
                aboutId: meta.aboutId || '',
                aboutType: meta.aboutType || '',
                otherUserId: meta.otherUserId || '',
                unreadCount: unreadCount,
                isRead: unreadCount === 0,
                _sortTime: lastMsg ? lastMsg.timestamp : 0
              });
            }
          }
        });
      } catch (e) {
        console.error('Scan local storage failed:', e);
      }
      sessions.sort((a, b) => {
        return (b._sortTime || 0) - (a._sortTime || 0);
      });

      // 注入服务通知伪会话（始终在列表顶部）
      try {
        const unreadRes = await api.get('/api/notifications/unread-count');
        const noticeUnread = typeof unreadRes === 'number' ? unreadRes : (unreadRes && unreadRes.count) || 0;

        // 同步到全局状态并刷新 tabBar「消息」红点
        app.globalData.noticeUnreadCount = noticeUnread;
        app._updateTabBarBadge();

        // 获取最新一条通知作为预览
        let noticeLast = null;
        try {
          const noticeList = await api.get('/api/notifications');
          const notices = noticeList?.content || (Array.isArray(noticeList) ? noticeList : []);
          if (notices.length > 0) {
            noticeLast = notices[0];
          }
        } catch (e2) { /* 静默失败 */ }

        sessions.unshift({
          id: '__service_notice__',
          otherName: '服务通知',
          avatarText: '🔔',
          avatarColor: '#007AFF',
          lastMessage: noticeLast ? noticeLast.content : '暂无通知',
          lastMsgType: 'text',
          timeText: noticeLast ? this.formatRelativeTime(noticeLast.createdAt) : '',
          roomInfo: '',
          aboutTitle: '',
          aboutId: '',
          aboutType: '',
          otherUserId: '',
          unreadCount: noticeUnread,
          isRead: noticeUnread === 0,
          _sortTime: noticeLast ? new Date(noticeLast.createdAt).getTime() : Date.now(),
          _isServiceNotice: true
        });
      } catch (e2) {
        // API 不可用时仍展示空的服务通知条目
        sessions.unshift({
          id: '__service_notice__',
          otherName: '服务通知',
          avatarText: '🔔',
          avatarColor: '#007AFF',
          lastMessage: '暂无通知',
          lastMsgType: 'text',
          timeText: '',
          roomInfo: '',
          aboutTitle: '',
          aboutId: '',
          aboutType: '',
          otherUserId: '',
          unreadCount: 0,
          isRead: true,
          _sortTime: Date.now(),
          _isServiceNotice: true
        });
      }

      // 补充后端数据：本地存储可能因 WebSocket 断连丢失会话，后端作为兜底
      try {
        const backendSessions = await api.get('/api/chats/sessions');
        const sessionList = backendSessions?.content || (Array.isArray(backendSessions) ? backendSessions : []);
        if (sessionList.length > 0) {
          const localIds = new Set(sessions.map(s => s.id));
          for (const bs of sessionList) {
            if (localIds.has(bs.sessionId)) continue; // 本地已有，跳过
            // 后端 lastMessage 可能是管道符格式（如 voice 的 "3|/uploads/...")，formatPreview 统一格式化
            const lastMsg = this.formatServerLastMsg(bs.lastMessage, bs.lastMessageType);
            const lastTime = bs.lastTime ? new Date(bs.lastTime).getTime() : Date.now();
            sessions.push({
              id: bs.sessionId,
              otherName: bs.otherUserName || '用户',
              avatarText: (bs.otherUserName || '?')[0],
              avatarColor: this.getAvatarColor(bs.sessionId),
              lastMessage: lastMsg,
              lastMsgType: bs.lastMessageType || 'text',
              timeText: this.formatRelativeTime(lastTime),
              roomInfo: '',
              aboutTitle: '',
              aboutId: '',
              aboutType: '',
              otherUserId: bs.otherUserId || '',
              unreadCount: app.getSessionUnread(bs.sessionId),
              isRead: true,
              _sortTime: lastTime,
              _fromBackend: true
            });
          }
        }
      } catch (e) {
        console.error('Fetch backend sessions failed, using local only:', e);
      }

      // 去重：同一 otherUserId 只保留最近活跃的会话（避免同一住户出现多个条目）
      const seenUsers = new Map();
      const dedupedSessions = [];
      for (const s of sessions) {
        // 服务通知不受去重影响
        if (s._isServiceNotice) {
          dedupedSessions.push(s);
          continue;
        }
        if (s.otherUserId) {
          const existing = seenUsers.get(s.otherUserId);
          if (existing) {
            // 保留 _sortTime 更大的（最近活跃）
            if ((s._sortTime || 0) > (existing._sortTime || 0)) {
              const idx = dedupedSessions.indexOf(existing);
              if (idx >= 0) dedupedSessions[idx] = s;
              seenUsers.set(s.otherUserId, s);
            }
            // 否则丢弃当前重复项
            continue;
          }
          seenUsers.set(s.otherUserId, s);
        }
        dedupedSessions.push(s);
      }

      // 应用隐藏/置顶过滤
      const pinned = wx.getStorageSync('pinned_sessions') || [];
      const hidden = wx.getStorageSync('hidden_sessions') || [];
      const filtered = dedupedSessions.filter(s => !hidden.includes(s.id));
      filtered.sort((a, b) => {
        const aPin = pinned.includes(a.id) ? 1 : 0;
        const bPin = pinned.includes(b.id) ? 1 : 0;
        if (aPin !== bPin) return bPin - aPin;
        return (b._sortTime || 0) - (a._sortTime || 0);
      });

      // 标记哪些会话已置顶（用于 CSS 样式和长按菜单切换）
      filtered.forEach(s => {
        s._isPinned = pinned.includes(s.id);
      });

      this.setData({ sessions: filtered });
    } catch (e) {
      console.error('Load sessions failed:', e);
    }
  },

  getAvatarColor(str) {
    const colors = ['#007AFF', '#34c759', '#ff9500', '#ff3b30', '#5856d6', '#af52de', '#ff2d55', '#00c7be'];
    let hash = 0;
    for (let i = 0; i < str.length; i++) {
      hash = str.charCodeAt(i) + ((hash << 5) - hash);
    }
    return colors[Math.abs(hash) % colors.length];
  },

  /** 本地存储的消息 → 会话列表预览文本 */
  formatPreview(msg) {
    if (!msg) return '';
    if (msg.recalled) return msg.isMine ? '你撤回了一条消息' : '对方撤回了一条消息';
    if (msg.type === 'voice') return '[语音] ' + (msg.voiceDuration || 0) + '″';
    if (msg.type === 'image') return '[图片]';
    return msg.content || '';
  },

  /** 服务端 lastMessage（可能为管道符格式）→ 会话列表预览文本 */
  formatServerLastMsg(content, messageType) {
    if (!content) return '新消息';
    if (messageType === 'voice') {
      const idx = content.indexOf('|');
      const duration = idx > 0 ? parseInt(content.substring(0, idx)) || 0 : 0;
      return '[语音] ' + duration + '″';
    }
    if (messageType === 'image') return '[图片]';
    return content;
  },

  formatRelativeTime(timestamp) {
    if (!timestamp) return '';
    const now = Date.now();
    const diff = now - new Date(timestamp).getTime();
    const minutes = Math.floor(diff / 60000);
    if (minutes < 1) return '刚刚';
    if (minutes < 60) return minutes + '分钟前';
    const hours = Math.floor(minutes / 60);
    if (hours < 24) return hours + '小时前';
    const days = Math.floor(hours / 24);
    if (days === 1) return '昨天';
    if (days < 7) return days + '天前';
    const date = new Date(timestamp);
    return (date.getMonth() + 1) + '/' + date.getDate();
  },

  onSessionTap(e) {
    const { id, name, room, about, aboutId, aboutType, otheruserid, isService } = e.currentTarget.dataset;

    // 服务通知条目 → 跳转通知详情页
    if (isService === true || isService === 'true') {
      wx.navigateTo({ url: '/pages/service-notice/service-notice' });
      return;
    }

    const url = '/pages/chat/chat?sessionId=' + id
      + '&name=' + encodeURIComponent(name)
      + '&room=' + encodeURIComponent(room || '')
      + '&about=' + encodeURIComponent(about || '')
      + '&aboutId=' + (aboutId || '')
      + '&aboutType=' + (aboutType || '')
      + '&otherUserId=' + (otheruserid || '');
    wx.navigateTo({ url });
  },

  // 长按会话 → 弹出操作菜单
  onSessionLongPress(e) {
    const { id, isService } = e.currentTarget.dataset;
    const isServiceNotice = isService === true || isService === 'true';

    // 判断当前会话是否已置顶
    const sessions = this.data.sessions;
    const session = sessions.find(s => s.id === id);
    const isPinned = session && session._isPinned;
    const pinLabel = isPinned ? '取消置顶' : '置顶该聊天';

    if (isServiceNotice) {
      wx.showActionSheet({
        itemList: ['标记为已读', pinLabel, '不显示该聊天', '删除该聊天'],
        success: (res) => {
          switch (res.tapIndex) {
            case 0: this.markNoticeRead(); break;
            case 1: this.pinSession(id); break;
            case 2: this.hideSession(id); break;
            case 3: this.deleteNoticeSession(); break;
          }
        }
      });
    } else {
      wx.showActionSheet({
        itemList: [pinLabel, '不显示该聊天', '删除该聊天'],
        success: (res) => {
          switch (res.tapIndex) {
            case 0: this.pinSession(id); break;
            case 1: this.hideSession(id); break;
            case 2: this.deleteSession(id); break;
          }
        }
      });
    }
  },

  // 标记服务通知为已读
  async markNoticeRead() {
    try {
      await api.put('/api/notifications/read-all');
      const sessions = this.data.sessions;
      const idx = sessions.findIndex(s => s.id === '__service_notice__');
      if (idx >= 0) {
        sessions[idx].unreadCount = 0;
        sessions[idx].isRead = true;
        this.setData({ sessions });
      }
      wx.showToast({ title: '已标记为已读', icon: 'none' });
    } catch (e) {
      wx.showToast({ title: '操作失败', icon: 'none' });
    }
  },

  // 删除服务通知（清空通知记录 + 隐藏条目）
  deleteNoticeSession() {
    wx.showModal({
      title: '确认删除',
      content: '将清空所有服务通知记录，同时不显示该会话。',
      success: async (res) => {
        if (res.confirm) {
          try {
            await api.del('/api/notifications/all');
            const sessions = this.data.sessions.filter(s => s.id !== '__service_notice__');
            this.setData({ sessions });
            wx.showToast({ title: '已删除', icon: 'none' });
          } catch (e) {
            wx.showToast({ title: '操作失败', icon: 'none' });
          }
        }
      }
    });
  },

  // 置顶/取消置顶会话（存本地存储）
  pinSession(id) {
    const pinned = wx.getStorageSync('pinned_sessions') || [];
    const idx = pinned.indexOf(id);
    if (idx >= 0) {
      // 已置顶 → 取消置顶
      pinned.splice(idx, 1);
      wx.setStorageSync('pinned_sessions', pinned);
      wx.showToast({ title: '已取消置顶', icon: 'none' });
    } else {
      pinned.push(id);
      wx.setStorageSync('pinned_sessions', pinned);
      wx.showToast({ title: '已置顶', icon: 'none' });
    }
    this.loadSessions();
  },

  // 隐藏会话（不删除记录）
  hideSession(id) {
    const hidden = wx.getStorageSync('hidden_sessions') || [];
    if (!hidden.includes(id)) {
      hidden.push(id);
      wx.setStorageSync('hidden_sessions', hidden);
    }
    const sessions = this.data.sessions.filter(s => s.id !== id);
    this.setData({ sessions });
    wx.showToast({ title: '已隐藏', icon: 'none' });
  },

  // 删除普通会话（清空聊天记录 + 隐藏）
  deleteSession(id) {
    wx.showModal({
      title: '确认删除',
      content: '将清空聊天记录，同时不显示该聊天。',
      success: (res) => {
        if (res.confirm) {
          const uid = auth.getUserId();
          if (uid) {
            wx.removeStorageSync('chat_msgs_' + uid + '_' + id);
            wx.removeStorageSync('chat_meta_' + uid + '_' + id);
          }
          const sessions = this.data.sessions.filter(s => s.id !== id);
          this.setData({ sessions });
          wx.showToast({ title: '已删除', icon: 'none' });
        }
      }
    });
  }
});
