const api = require('../../utils/api');
const auth = require('../../utils/auth');
const { POST_TYPE, STORAGE_KEY, AGENT_EVENT_TYPE } = require('../../utils/constants');
const { parseMarkdown, stripMarkdown } = require('../../utils/markdown');
// 语音识别管理器实例：不在模块顶层 requirePlugin——微信官方要求插件在运行时动态加载，
// 插件环境未就绪（后台已添加但工具未启用插件调试/缓存未刷新）时顶层调用会让模块加载直接抛
// 「Plugin is not defined」导致整页打不开。改在 initSpeech（onLoad 阶段）动态加载 + try-catch 降级
let speechManager = null;

/** SSE 静默看门狗时长（毫秒）：收到任何分块即重置，仅当流真正静默超时才判超时 */
const WATCHDOG_SILENCE_MS = 45000;
/** 事件队列消费间隔（毫秒）：统一排队消费模型——所有 SSE 事件入全局队列，每 40ms 消费 1 次渲染输出。
 * 不管网络一次来 1 个事件还是装满多个的大包，都入队逐个消费；answer 事件内部再做字符级拆解（见 CHAR_STEP），
 * 避免「40ms 吐一整块大文本」的块状顿挫。end 事件消费完再收尾排版。 */
const EVENT_CONSUME_INTERVAL_MS = 40;
/** 字符级蹦字步长：消费到 answer 事件后，每 EVENT_CONSUME_INTERVAL_MS 蹦出的字符数。
 * 后端 answer 事件本身可能带几十~一两百字大段文本，若整块渲染会块状抖动；
 * 块内拆成字符子队列逐字符输出，模拟顺滑打字机。5 字符/40ms ≈ 每秒 125 字，
 * 速度优先档（跳跃感轻微）；如追求更细腻观感可回退到 4（≈100 字/秒）。 */
const CHAR_STEP = 5;

/** 解析后端返回的 sources/actions JSON 字符串为数组（null/非法返回空数组） */
function parseJsonArray(str) {
  if (!str) return [];
  try {
    const v = JSON.parse(str);
    return Array.isArray(v) ? v : [];
  } catch (e) {
    return [];
  }
}

/**
 * 小邻 — AI 智能助手对话页（独立单页面，无底部导航栏，对齐聊天页）。
 *
 * 入口：tabBar「小邻」经中转页 pages/assistant/assistant-entry 重定向进入。
 * 功能：小区知识问答（RAG）、流式对话（SSE 伪流式）、快捷问题、引用来源展示。
 * 鉴权：仅登录即可用（后端 /api/agent/** authenticated，不要求认证通过），
 * 审核中新住户也能问小区规则。
 */
Page({
  data: {
    /** 系统状态栏高度（自定义导航偏移） */
    statusBarHeight: 0,
    /** 历史悬浮按钮位置（px，可长按拖动并记忆） */
    fabX: 0,
    fabY: 0,
    /** 消息列表：{id, role: user|assistant, content, sources, actions, streaming} */
    messages: [],
    /** 输入框文本 */
    inputText: '',
    /** 快捷问题（来自 /api/agent/suggestions） */
    suggestions: [],
    /** 键盘高度（输入区抬升） */
    keyboardHeight: 0,
    /** 发送中（流式接收未结束） */
    sending: false,
    /** scroll-view 滚动位置（scroll-top 大数钳制到底部：比 scroll-into-view 更可靠地滚到含引用/来源的完整最底） */
    scrollTop: 0,
    /** 历史会话弹层可见性 */
    historyVisible: false,
    /** 历史列表 scroll-view 高度（px；0=未校准，由 calibrateHistoryHeight 动态计算，保证内容超出时可滚动） */
    historyListHeight: 0,
    /** 历史会话列表（后端返回字段 id 即会话级 conversationId；含 updatedAtText 显示字段） */
    historyList: [],
    /** 批量选择模式（长按进入；选中态由列表项 selected 字段驱动，见 toggleSelect） */
    selecting: false,
    /** 历史分页页码 */
    historyPage: 0,
    /** 历史总数（分页判断） */
    historyTotal: 0,
    /** 历史加载中（防重复请求） */
    loadingHistory: false,
    /** 录音识别中（按住说话；输入模式切换已内聚到共享组件 chat-input-bar 内部） */
    recording: false,
    /** 上滑取消录音中 */
    recordCancelling: false,
    /** 录音时长（秒） */
    recordDuration: 0,
    /** 输入消息最大长度（对齐后端 @Size 校验；textarea maxlength 与语音转文字共用） */
    msgMaxLength: 500
  },

  /** 消息自增 id */
  _msgSeq: 0,
  /** SSE 原始 buffer（分块可能跨事件边界，需累积解析） */
  _sseBuffer: '',
  /** 流式中止函数 */
  _abortStream: null,
  /** 当前正在接收回复的消息 id */
  _currentMsgId: null,
  /** SSE 事件队列（统一排队消费，见 _onSseChunk/_consumeQueueEvent；中止/卸载时清空） */
  _eventQueue: [],
  /** 事件队列消费定时器（每 EVENT_CONSUME_INTERVAL_MS 消费 1 条；中止/卸载时需清理） */
  _queueTimer: null,
  /** 当前 answer 事件的待蹦字符缓冲（null=无；非空时字符级蹦字，见 _ensureQueueTimer） */
  _charBuffer: null,
  /** 字符缓冲已蹦字符数（每 EVENT_CONSUME_INTERVAL_MS 递增 CHAR_STEP） */
  _charIdx: 0,
  /** 已蹦出并累积的完整正文（原始 Markdown；字符蹦完一个 answer 后并入，供 end 重算排版） */
  _rawContent: '',
  /** scroll-top 自增序列（每次滚动值变化，确保每次都触发滚动到底部） */
  _scrollTick: 0,
  /** 上滑取消/手势中断的丢弃标记（置位后 onStop 丢弃识别结果，不发送） */
  _recordCancelled: false,

  onLoad() {
    // 主动进入对话页：清除"从对话页返回"标记，避免中转页误判为返回
    if (getApp().globalData) getApp().globalData.assistantReturning = false;
    if (!auth.ensureLoggedIn()) return;   // 仅登录门禁（审核中新住户也能用，与后端 unapproved 白名单一致）
    // 每次进入对话页 = 新会话：先归档上一轮残留热会话（后台杀小程序等未触发 onUnload 的场景，空闲 15 分钟调度器只是兜底），
    // 避免新消息被静默并进旧对话。fire-and-forget 不阻塞进入、失败不影响页面（对齐 code-standards 禁空 catch）
    api.post('/api/agent/exit').catch(() => {
      console.warn('[assistant] 进入时归档残留会话失败，后端将在空闲后兜底归档');
    });
    const sysInfo = wx.getSystemInfoSync();
    const statusBarHeight = sysInfo.statusBarHeight || 20;
    this.setData({ statusBarHeight });
    // 悬浮按钮拖动边界：X 限制在屏内；Y 上不超导航（statusBar+88rpx）、下不超输入栏（键盘收起时距底 124px）
    // 按钮尺寸 100rpx=50px（对齐首页 + 按钮）
    this._fabMinX = 8;
    this._fabMaxX = sysInfo.windowWidth - 58;
    this._fabMinY = statusBarHeight + 52;
    this._fabBaseMaxY = sysInfo.windowHeight - 124;
    this._fabMaxY = this._fabBaseMaxY;
    // 历史悬浮按钮初始位置：优先记忆值（钳制边界内），否则默认导航下方右上角（50=按钮 100rpx，8=边距）
    const saved = wx.getStorageSync(STORAGE_KEY.HISTORY_FAB_POS);
    const defX = sysInfo.windowWidth - 58;
    const defY = statusBarHeight + 60;
    this.setData({
      fabX: Math.min(this._fabMaxX, Math.max(this._fabMinX, (saved && typeof saved.x === 'number') ? saved.x : defX)),
      fabY: Math.min(this._fabMaxY, Math.max(this._fabMinY, (saved && typeof saved.y === 'number') ? saved.y : defY))
    });
    // 探测 enableChunked 分块通道（幂等，只跑一次）：确认可用后才启用分块流式，
    // 默认非分块直发保证消息只发一次、绝不因降级重试触发服务端重复处理
    api.probeChunked();
    this.loadSuggestions();
    this.initSpeech();
  },

  // ==================== 语音输入（WechatSI 转文字） ====================

  /** 初始化语音识别回调（只注册一次） */
  initSpeech() {
    if (this._speechInited) return;
    this._speechInited = true;
    // 运行时动态加载微信同声传译插件（语音转文字）：模块顶层不调用 requirePlugin
    if (!speechManager) {
      try {
        speechManager = requirePlugin('WechatSI').getRecordRecognitionManager();
      } catch (e) {
        console.warn('[assistant] WechatSI 插件不可用，语音输入已降级:', e && e.message);
      }
    }
    // 插件未启用时跳过回调注册，避免对 null 调用 onStop/onError 抛 TypeError
    if (!speechManager) return;
    speechManager.onStop = (res) => {
      // 识别完成：文字直接发送（不进输入框）；截断到输入上限，避免后端 @Size 拒绝
      this.setData({ recording: false });
      // 上滑取消/手势中断置位后丢弃识别结果（对齐 chat 页 _recordCancelled），避免取消后仍误发
      if (this._recordCancelled) {
        this._recordCancelled = false;
        return;
      }
      const text = (res && res.result) || '';
      if (text && text.trim()) {
        this.sendMessage(text.trim().slice(0, this.data.msgMaxLength));
      } else {
        wx.showToast({ title: '未识别到内容', icon: 'none' });
      }
    };
    speechManager.onError = (err) => {
      // 记录错误码/信息便于定位（如 -2 未授权录音、-30003 数据传输失败等）
      console.error('[assistant] 语音识别失败:', err && err.retcode, err && err.msg);
      this.setData({ recording: false });
      wx.showToast({ title: '语音识别失败，请重试', icon: 'none' });
    };
  },

  /** 输入框失焦（键盘收起；组件内部已处理"发送后立即失焦需重聚焦"） */
  onInputBlur() {
    this.setData({ keyboardHeight: 0 });
  },

  /** 点消息区空白处：收起键盘 + 输入栏回落（对齐聊天页 onChatAreaTap） */
  onMsgAreaTap() {
    wx.hideKeyboard();
    this.setData({ keyboardHeight: 0 });
  },

  /**
   * 滚到最底部：scroll-top 用单调递增的大数，超过内容高度即被钳制到真正最底
   * （含底部 padding 间隙），保证气泡最后一行与下方的引用/来源都完整顶到输入框上方。
   * 比 scroll-into-view 更可靠——不依赖锚点定位，直接钳制到最大滚动位置。
   */
  scrollToBottom() {
    this.setData({ scrollTop: 99999 + (++this._scrollTick) });
  },

  /**
   * 消息内容更新并同步重算 AI 回复的 Markdown 块（用户消息不渲染块，仅保留原字段）。
   * 流式片段可能未闭合，解析器按段落兜底；流结束后 replace 事件整体刷新为干净排版。
   *
   * @param {object} m       消息对象
   * @param {string} content 更新后的内容（Markdown 或纯文本）
   * @return {object} 更新 content 与 blocks 后的消息
   */
  _withContent(m, content) {
    return { ...m, content, blocks: m.role === 'assistant' ? parseMarkdown(content) : (m.blocks || []) };
  },

  /** 按住开始录音识别（voice 模式按压区） */
  onVoiceStart(e) {
    if (this.data.sending) return;
    // 插件未启用时语音不可用：给提示而非对 null speechManager 调用 start 抛错
    if (!speechManager) {
      wx.showToast({ title: '语音功能未启用', icon: 'none' });
      return;
    }
    // 防重复触发录音（对齐 chat 页 onRecordStart；Android touch 事件偶现重复触发）
    if (this.data.recording) return;
    // 先申请录音权限再开始：未授权时微信不会录音，start 会走 onError（-2 未授权）
    this._ensureRecordAuth().then((granted) => {
      if (granted) this._beginRecord(e);
    });
  },

  /**
   * 检查并申请麦克风权限（scope.record）。
   *
   * <p>首次请求弹系统授权框；已拒绝过则引导去设置页开启。
   * getSetting 异常时不阻断（由 start 的 onError 兜底提示）。</p>
   *
   * @return {Promise<boolean>} 是否已获得录音权限
   */
  _ensureRecordAuth() {
    return new Promise((resolve) => {
      wx.getSetting({
        success: (res) => {
          if (res.authSetting['scope.record']) {
            resolve(true);
            return;
          }
          wx.authorize({
            scope: 'scope.record',
            success: () => resolve(true),
            fail: () => {
              wx.showModal({
                title: '需要麦克风权限',
                content: '语音输入需要麦克风权限，请在设置中开启',
                confirmText: '去设置',
                success: (r) => {
                  if (r.confirm) wx.openSetting();
                },
              });
              resolve(false);
            },
          });
        },
        fail: () => resolve(true),
      });
    });
  },

  /** 获得录音权限后真正开始录音识别 */
  _beginRecord(e) {
    // 记录起始触摸 Y（上滑取消判断基准；触摸信息由组件透传在 e.detail）
    this._recordStartY = (e.detail.touches && e.detail.touches[0]) ? e.detail.touches[0].clientY : 0;
    this._recordCancelled = false;
    this.setData({ recording: true, recordCancelling: false, recordDuration: 0 });
    // start() 在多数 WechatSI 版本返回 Promise，但官方文档类型为 void（报错走 onError）；
    // 防御式处理两种返回类型：返回 Promise 时 catch 拒绝兜底，返回 void 时跳过链式调用避免 TypeError
    // 失败即无录音任务，不可再调 stop()（会报 -30012 当前无识别任务）
    const startResult = speechManager.start({ lang: 'zh_CN', duration: 60000 });
    if (startResult && typeof startResult.catch === 'function') {
      startResult.catch((err) => {
        console.warn('[assistant] 语音识别 start 被拒绝:', err && err.retcode, err && err.msg);
        clearInterval(this._recordTimer);
        this.setData({ recording: false, recordCancelling: false, recordDuration: 0 });
      });
    }
    // 录音计时（浮层显示秒数）
    this._recordTimer = setInterval(() => {
      this.setData({ recordDuration: this.data.recordDuration + 1 });
    }, 1000);
  },

  /** 松开停止录音：上滑取消则丢弃，否则识别并发送 */
  onVoiceEnd() {
    // 发送中被拦截未开启录音时（recording 为 false）不可调 stop()，否则报 -30012 无识别任务 → onError 弹"识别失败"提示（对齐 chat 页 onRecordEnd）
    if (!this.data.recording) return;
    clearInterval(this._recordTimer);
    const cancelled = this.data.recordCancelling;
    if (cancelled) {
      // 上滑取消：置丢弃标记 + 调 stop() 结束识别任务。只弹提示不调 stop() 会拖到 60s 超时，
      // 且 onStop 仍会把已识别文字误发（对齐 chat 页 onRecordEnd 的 _recordCancelled 丢弃机制）
      this._recordCancelled = true;
      this.setData({ recording: false, recordCancelling: false });
      wx.showToast({ title: '已取消', icon: 'none' });
      speechManager.stop();
      return;
    }
    this.setData({ recording: false, recordCancelling: false });
    speechManager.stop();   // 触发 onStop → 识别 → 直接发送
  },

  /** 取消（手势中断） */
  onVoiceCancel() {
    // 同 onVoiceEnd：未真正录音时跳过 stop()，避免无识别任务报错弹提示
    if (!this.data.recording) return;
    // 手势中断同样置丢弃标记，防止 onStop 误发（对齐 chat 页 onRecordCancel）
    this._recordCancelled = true;
    clearInterval(this._recordTimer);
    this.setData({ recording: false, recordCancelling: false });
    speechManager.stop();
  },

  /** 录音中移动：上滑超过阈值进入取消态（参考 chat 页；触摸信息由组件透传在 e.detail） */
  onVoiceTouchMove(e) {
    if (!this.data.recording) return;
    const y = (e.detail.touches && e.detail.touches[0]) ? e.detail.touches[0].clientY : 0;
    const cancelling = this._recordStartY > 0 && (this._recordStartY - y) > 80;
    if (cancelling !== this.data.recordCancelling) {
      this.setData({ recordCancelling: cancelling });
      // 进入取消态即置位丢弃标记（对齐 chat 页 onRecordTouchMove），松手时据此丢弃
      if (cancelling) this._recordCancelled = true;
    }
  },

  onShow() {
    if (!auth.ensureLoggedIn()) return; // 后台切回时兜底鉴权
    // 重新回到对话页（跳发布页返回等）：清除返回标记
    if (getApp().globalData) getApp().globalData.assistantReturning = false;
  },

  onHide() {
    // 对话页被覆盖/弹出（手势返回、‹ 按钮、跳发布页）：标记"从对话页返回"，让中转页决定去向
    if (getApp().globalData) getApp().globalData.assistantReturning = true;
  },

  /**
   * 返回手势/返回键处理（Android 系统返回键 + iOS 侧滑返回手势）：
   * 历史弹层打开时先关闭弹层并返回 true 拦截路由回退——避免弹层（自定义全屏覆盖层，
   * 微信不感知）开着时返回手势被当成根页面返回而直接退出小程序；
   * 弹层关闭状态交还系统正常 pop 回中转页（中转页据 assistantReturning 切回首页 tab）。
   */
  /** 返回按钮：退回中转页（中转页据此回首页 tab），无栈时兜底直接切首页 */
  onNavBack() {
    wx.navigateBack({
      fail() {
        if (getApp().globalData) getApp().globalData.assistantReturning = false;
        wx.switchTab({ url: '/pages/home/home' });
      }
    });
  },

  /** 加载快捷问题 */
  loadSuggestions() {
    api.get('/api/agent/suggestions')
      .then((data) => this.setData({ suggestions: data || [] }))
      .catch(() => {});
  },

  /** 点击快捷问题 chips */
  onSuggestionTap(e) {
    const msg = e.currentTarget.dataset.msg;
    if (this.data.sending) return;
    this.setData({ inputText: msg });
    this.sendMessage(msg);
  },

  onInputChange(e) {
    this.setData({ inputText: e.detail.value });
  },

  /** 点击发送按钮 / 键盘确认键 */
  onSend() {
    const msg = (this.data.inputText || '').trim();
    if (!msg || this.data.sending) return;
    this.sendMessage(msg);
  },

  /** 点击终止图标：中止进行中的助手回复流 */
  onAbort() {
    if (!this.data.sending) return;   // 防连点重复触发
    if (this._streamWatchdog) { clearTimeout(this._streamWatchdog); this._streamWatchdog = null; }
    if (this._queueTimer) { clearTimeout(this._queueTimer); this._queueTimer = null; }
    this._eventQueue = [];   // 清空事件队列，防止后台继续消费蹦字
    this._charBuffer = null; // 清空字符缓冲，防止残留字符继续蹦出
    this._charIdx = 0;
    this._rawContent = '';
    if (this._abortStream) {
      this._abortStream();
      this._abortStream = null;
    }
    this._sseBuffer = '';
    const msgId = this._currentMsgId;
    const messages = this.data.messages.map((m) => {
      // 打字机中止：把已蹦的净化文本落为正文并重算，避免正文丢失；aborted 标记由 WXML 渲染成灰色"（已终止）"行
      if (m.id === msgId) {
        const partial = m.typewriting ? (m.displayText || '') : m.content;
        return { ...this._withContent(m, partial), typewriting: false, streaming: false, aborted: true };
      }
      return m;
    });
    this.setData({ messages, sending: false });
  },

  /**
   * 发送消息并建立 SSE 流式接收。
   * @param {string} msg 用户消息
   */
  sendMessage(msg) {
    const userMsg = { id: 'u' + (++this._msgSeq), role: 'user', content: msg, sources: [], streaming: false };
    const aiMsg = { id: 'a' + (++this._msgSeq), role: 'assistant', content: '', blocks: [], sources: [], streaming: true, failed: false, retryText: msg };
    const messages = this.data.messages.concat([userMsg, aiMsg]);
    this._currentMsgId = aiMsg.id;
    this._sseBuffer = '';
    this._eventQueue = [];    // 重置事件队列（统一排队消费）
    this._queueTimer = null;  // 重置消费定时器
    this._charBuffer = null;  // 重置字符缓冲（answer 字符级蹦字）
    this._charIdx = 0;
    this._rawContent = '';
    this.setData({ messages, inputText: '', sending: true });
    // 滚到底部锚点（对齐聊天页 scrollToBottom：等渲染完再滚，长消息不被挤出屏外）
    this.scrollToBottom();

    // 静默看门狗：收到任何 SSE 分块即重置，仅当流真正静默超时才判超时（见 _onSseChunk）
    this._armStreamWatchdog();

    this._abortStream = api.requestStream('/api/agent/chat', { message: msg },
      (chunk, isFallback) => this._onSseChunk(chunk, isFallback),
      (err) => this._onStreamError(err));
  },

  /**
   * 挂载/重置静默看门狗：每次收到流数据都会重新计时，
   * 只有连续 WATCHDOG_SILENCE_MS 毫秒没有任何分块到达才判超时，避免误杀正常慢流。
   */
  _armStreamWatchdog() {
    if (this._streamWatchdog) { clearTimeout(this._streamWatchdog); this._streamWatchdog = null; }
    this._streamWatchdog = setTimeout(() => {
      if (!this.data.sending) return;
      if (this._queueTimer) { clearTimeout(this._queueTimer); this._queueTimer = null; }
      this._eventQueue = [];   // 超时中断：清空未消费事件，避免残余正文追加污染
      this._charBuffer = null; // 清空字符缓冲
      this._charIdx = 0;
      this._rawContent = '';
      if (this._abortStream) { this._abortStream(); this._abortStream = null; }
      const msgId = this._currentMsgId;
      const messages = this.data.messages.map((m) => {
        if (m.id === msgId) {
          // 打字机态超时：已蹦净化文本落为正文，避免正文丢失
          const partial = m.typewriting ? (m.displayText || '') : m.content;
          return { ...this._withContent(m, partial + '\n（回复超时）'), typewriting: false, streaming: false, failed: true };
        }
        return m;
      });
      this.setData({ messages, sending: false });
    }, WATCHDOG_SILENCE_MS);
  },

  /**
   * SSE 分块累积 + 按事件边界切分（兼容 \r\n 行尾，统一归一化），切出的事件全部入队由消费定时器统一消费。
   *
   * @param chunk SSE 文本分块
   */
  _onSseChunk(chunk) {
    // 部分环境/代理用 \r\n 行尾，不归一化则 '\n\n' 分隔永远匹配不到
    this._sseBuffer += (chunk || '').replace(/\r\n/g, '\n');
    const events = [];
    let idx;
    while ((idx = this._sseBuffer.indexOf('\n\n')) !== -1) {
      events.push(this._sseBuffer.slice(0, idx));
      this._sseBuffer = this._sseBuffer.slice(idx + 2);
    }
    if (events.length === 0) return;

    // 统一排队消费：所有事件（无论网络一次来 1 个还是 TCP 粘包大包）追加到全局事件队列，
    // 由消费定时器每 EVENT_CONSUME_INTERVAL_MS 取 1 条渲染——逐段流式，从结构上规避
    // 「打字机动画期间后续 chunk 并发写入」导致的中间 token 丢失 / 消息卡死
    this._eventQueue.push(...events);
    this._ensureQueueTimer();
    // 收到任何数据 = 流存活，重置静默看门狗（流结束后不重挂，避免悬空定时器）
    if (this.data.sending) this._armStreamWatchdog();
  },

  /**
   * 确保事件消费定时器运行：每 {@link EVENT_CONSUME_INTERVAL_MS} 处理一次——
   * 优先字符级蹦字（当前 answer 字符缓冲未蹦完时逐字符输出，避免整块顿挫），
   * 蹦完才消费下一个事件（answer 入字符缓冲、sources/action 即时、end/error/clear 收尾）；
   * 队列清空或消费到结束事件时自愈停止，不叠加定时器。
   */
  _ensureQueueTimer() {
    if (this._queueTimer) return;   // 已有定时器在跑（setTimeout 自愈链），无需重复
    const consumeNext = () => {
      // 阶段一：字符级蹦字（优先）——当前 answer 未蹦完时先蹦 CHAR_STEP 字符
      if (this._charBuffer !== null) {
        this._charIdx += CHAR_STEP;
        const content = this._rawContent + this._charBuffer.slice(0, this._charIdx);
        const displayText = this._cleanTypewriterText(content);
        const msgId = this._currentMsgId;
        this.setData({ messages: this.data.messages.map((m) => (
          m.id === msgId ? { ...m, content, displayText, typewriting: true } : m
        )) });
        this.scrollToBottom();
        if (this._charIdx >= this._charBuffer.length) {
          // 当前 answer 蹦完：累积正文并入 rawContent，清字符缓冲，下次 tick 消费事件
          this._rawContent = content;
          this._charBuffer = null;
          this._charIdx = 0;
        }
      } else {
        // 阶段二：消费下一条事件（answer 拆入字符缓冲；sources/action 即时；end/error/clear 收尾）
        const event = this._eventQueue.shift();
        if (event === undefined) {
          this._queueTimer = null;   // 队列已空：停止
          return;
        }
        const finished = this._consumeQueueEvent(event);
        if (finished) {
          this._queueTimer = null;   // 已消费到结束事件：停止
          return;
        }
      }
      this._queueTimer = setTimeout(consumeNext, EVENT_CONSUME_INTERVAL_MS);
    };
    consumeNext();
  },

  /**
   * 消费 1 条 SSE 事件：answer 把事件文本塞入字符缓冲（由 {@link _ensureQueueTimer} 的蹦字阶段逐字符输出，
   * 不整块渲染）；sources/action 即时设置；end/error/clear 触发收尾并返回 true 停止队列。
   *
   * @param event SSE 事件文本
   * @return {boolean} true=已消费到流结束事件，队列应停止
   */
  _consumeQueueEvent(event) {
    const msgId = this._currentMsgId;
    const dataLine = event.split('\n').find((l) => l.startsWith('data:'));
    if (!dataLine) return false;
    const raw = dataLine.slice(5).trim();
    let evt;
    try { evt = JSON.parse(raw); } catch (e) { return false; }
    const type = evt.type;

    if (type === AGENT_EVENT_TYPE.ANSWER) {
      // 不直接渲染整块（后端 event 常带几十~一两百字大段文本）：把事件文本放入字符缓冲，
      // 由 _ensureQueueTimer 的蹦字阶段逐字符输出（CHAR_STEP/40ms），避免块状顿挫
      this._charBuffer = evt.data || '';
      this._charIdx = 0;
      return false;
    }
    if (type === AGENT_EVENT_TYPE.SOURCES) {
      const srcs = this._dedupSources(evt.data || []);
      this.setData({ messages: this.data.messages.map((m) => (m.id === msgId ? { ...m, sources: srcs } : m)) });
      return false;
    }
    if (type === AGENT_EVENT_TYPE.ACTION) {
      this.setData({ messages: this.data.messages.map((m) => (m.id === msgId ? { ...m, actions: evt.data || [] } : m)) });
      return false;
    }
    if (type === AGENT_EVENT_TYPE.REPLACE) {
      // 剔除写操作意图 JSON 后的干净文案：整体替换正文并重算 Markdown，退出打字机态
      this.setData({ messages: this.data.messages.map((m) => (
        m.id === msgId ? { ...this._withContent(m, evt.data || ''), typewriting: false } : m
      )) });
      return false;
    }
    if (type === AGENT_EVENT_TYPE.ERROR) {
      // 错误文案直接展示后端返回的白名单内容；错误态由下方「回复失败，点此重试」入口与 failed 样式呈现
      // 已有正文时（流中途失败）补换行，避免与半截正文连成一句
      const errText = evt.data || '请稍后重试';
      this._finishQueueOnEnd(msgId, (m) => ({
        ...this._withContent(m, m.content + (m.content ? '\n' : '') + errText),
        streaming: false, failed: true,
      }));
      return true;
    }
    if (type === AGENT_EVENT_TYPE.END) {
      this._finishQueueOnEnd(msgId, (m) => ({ ...m, streaming: false }));
      return true;
    }
    if (type === AGENT_EVENT_TYPE.CLEAR) {
      // 后端对 /clear 固定发 answer → clear → end：clear 只清空消息、不停链——
      // 若在此 return true 停止队列，紧随其后的 end 被弃置，_finishQueueOnEnd 永不执行，
      // sending 卡在 true、看门狗悬空，页面连续 45 秒无法发送新消息。故返回 false 由 end 收尾。
      this.setData({ messages: [] });
      return false;
    }
    return false;
  },

  /**
   * 队列消费到结束信号（end/error）的统一收尾：退出打字机态、用累积正文重算 Markdown 排版、
   * 结束 sending 并清理看门狗（end 仅置消息 streaming=false，页面级 sending 由本方法负责——
   * 漏置会让看门狗 45s 后误报"（回复超时）"）。
   *
   * @param msgId 当前 AI 消息 id
   * @param extra 对当前消息的额外变更（end 置 streaming=false；error 追加错误文案并置 failed）
   */
  _finishQueueOnEnd(msgId, extra) {
    if (this._streamWatchdog) { clearTimeout(this._streamWatchdog); this._streamWatchdog = null; }
    const messages = this.data.messages.map((m) => {
      if (m.id !== msgId) return m;
      // 退出打字机态：用累积正文重算 Markdown 排版（replace 已重算过则 content 已是最新，此处幂等）
      const formatted = m.typewriting ? this._withContent(m, m.content) : m;
      return { ...extra(formatted), typewriting: false };
    });
    const payload = { messages };
    if (this.data.sending) payload.sending = false;
    this.setData(payload);
    this.scrollToBottom();
  },

  /**
   * 净化 Markdown 标记为纯文本（打字机显示用）：剥掉加粗/标题/列表/代码围栏等标记符号，
   * 避免打字机期间把原始 `##`、`**` 等符号蹦给用户看；流结束仍用原始 Markdown 重算正式排版。
   *
   * @param text 原始 Markdown 文本
   * @return 净化后的纯文本
   */
  _cleanTypewriterText(text) {
    // 净化时保留 `1. ` / `- ` 列表标记：打字机期间就显示编号/列表结构，
    // 与流结束后的正式排版一致，避免"纯文字 → 突然加编号"的跳变
    return (text || '')
      .replace(/\*\*([^*]+)\*\*/g, '$1')      // **加粗** → 加粗（标记去掉、文字保留）
      .replace(/^\s*#{1,4}\s*/gm, '')          // 行首标题标记 ## → 去掉
      .replace(/^```[\s\S]*?```$/gm, '')       // 代码块整块去掉（含围栏）
      .replace(/\n{3,}/g, '\n\n')              // 压缩多余空行
      .trim();
  },


  /** 来源名规范化：去掉开头的"小区名-"前缀（如"翠湖花园-平台使用帮"→"平台使用帮"），用于同文档不同 source 串去重 */
  _normalizeSource(s) {
    return (s || '').replace(/^[^-]+-/, '');
  },

  /**
   * 按规范化来源名去重，保留更具体（更长）的 source 展示。
   * 后端按知识条目 id 去重拦不住同文档多切片；source 可能带小区名前缀导致精确串不同，故去前缀后比较。
   *
   * @param list 原始来源数组
   * @return 去重后的来源数组
   */
  _dedupSources(list) {
    const byKey = {};
    const result = [];
    for (const s of list) {
      if (!s || s.source == null) {
        result.push(s);
        continue;
      }
      const key = this._normalizeSource(s.source);
      const cur = byKey[key];
      if (!cur) {
        byKey[key] = s;
        result.push(s);
      } else if ((s.source || '').length > (cur.source || '').length) {
        // 更具体的来源替换当前展示（保持原顺序位置）
        const idx = result.indexOf(cur);
        result[idx] = s;
        byKey[key] = s;
      }
    }
    return result;
  },

  /** 点击动作卡片：写操作暂存草稿跳转预填；发布指引（goto_publish）清草稿、跳对应 tab 不预填 */
  onActionTap(e) {
    const action = e.currentTarget.dataset.action;
    if (!action || !action.type) return;
    // 发布指引快捷跳转：清掉残留草稿、按 params.type 跳对应 tab（无预填，用户自己填），默认闲置借出
    if (action.type === 'goto_publish') {
      try { wx.removeStorageSync(STORAGE_KEY.AGENT_DRAFT); } catch (e) { /* 忽略清除失败 */ }
      const jumpMap = { help: POST_TYPE.HELP, idle: POST_TYPE.LEND, wanted: POST_TYPE.WANTED };
      const t = (action.params && jumpMap[action.params.type]) || POST_TYPE.LEND;
      wx.navigateTo({ url: '/pages/publish-idle/publish-idle?type=' + t });
      return;
    }
    // 写操作：暂存 AI 生成的草稿参数（发布页 onLoad 读取预填，读后清空）
    try {
      wx.setStorageSync(STORAGE_KEY.AGENT_DRAFT, { type: action.type, params: action.params || {} });
    } catch (e) {
      // 存储写满等异常不阻断跳转（草稿丢失不致命，发布页手填即可）
      console.warn('[assistant] 暂存发布草稿失败:', e);
    }
    // 映射动作类型到发布页 postType 参数
    const typeMap = {
      publish_help: POST_TYPE.HELP,
      publish_idle: POST_TYPE.LEND,
      publish_wanted: POST_TYPE.WANTED
    };
    const t = typeMap[action.type] || POST_TYPE.LEND;
    wx.navigateTo({ url: '/pages/publish-idle/publish-idle?type=' + t });
  },

  /** 流式错误：断线提示重试（不自动重发，防重复扣费；透出真实错误便于定位） */
  _onStreamError(err) {
    if (!this.data.sending) return;
    if (this._streamWatchdog) { clearTimeout(this._streamWatchdog); this._streamWatchdog = null; }
    if (this._queueTimer) { clearTimeout(this._queueTimer); this._queueTimer = null; }
    this._eventQueue = [];   // 断线中断：清空未消费事件，避免残余正文追加到失败消息
    this._charBuffer = null; // 清空字符缓冲
    this._charIdx = 0;
    this._rawContent = '';
    // wx.request fail 的错误只有 errMsg 无 message：区分 timeout / 域名白名单，避免都兜底成"网络中断"
    const raw = err || {};
    const errMsg = raw.errMsg || '';
    const reason = raw.message
      ? raw.message
      : errMsg.indexOf('timeout') !== -1 ? '请求超时，请检查网络'
      : errMsg.indexOf('url not in domain list') !== -1 ? '当前网络环境无法连接服务器'
      : '网络中断';
    console.error('[assistant] SSE 流错误:', err);
    const msgId = this._currentMsgId;
    const messages = this.data.messages.map((m) => {
      if (m.id === msgId) {
        return { ...this._withContent(m, m.content + '\n（回复失败：' + reason + '）'), streaming: false, failed: true };
      }
      return m;
    });
    this.setData({ messages, sending: false });
  },

  /** 点击失败气泡下方的重试入口：原问题重发（不自动重发，仅用户显式触发） */
  onRetryTap(e) {
    const msg = e.currentTarget.dataset.msg;
    if (!msg || this.data.sending) return;
    this.sendMessage(msg);
  },

  /** 长按消息：弹出复制选项，复制成功后提示（内容为空不弹；AI 回复复制时去掉 Markdown 标记为纯文本） */
  onMsgLongPress(e) {
    const content = (e.currentTarget.dataset && e.currentTarget.dataset.content) || '';
    const role = e.currentTarget.dataset.role;
    if (!content) return;
    wx.showActionSheet({
      itemList: ['复制'],
      success: (res) => {
        if (res.tapIndex !== 0) return;
        wx.setClipboardData({
          data: role === 'assistant' ? stripMarkdown(content) : content,
          success: () => wx.showToast({ title: '复制成功', icon: 'success' })
        });
      }
    });
  },

  /** 键盘弹起/收起：输入区跟随抬升；键盘弹起时滚到底，避免最新消息被键盘盖住；悬浮按钮下边界随键盘上移 */
  onKeyboardHeightChange(e) {
    this.setData({ keyboardHeight: e.detail.height });
    if (e.detail.height > 0) this.scrollToBottom();
    // 键盘顶起输入栏时，悬浮按钮最大 Y 随之上移，避免被键盘/输入框盖住；超界则回弹
    this._fabMaxY = this._fabBaseMaxY - e.detail.height;
    if (this.data.fabY > this._fabMaxY) {
      this.setData({ fabY: this._fabMaxY });
    }
  },

  // ==================== 历史会话交互 ====================

  /** 历史悬浮按钮拖动开始 */
  onFabTouchStart(e) {
    const t = e.touches[0];
    this._fabDrag = { startX: t.clientX, startY: t.clientY, baseX: this.data.fabX, baseY: this.data.fabY, moved: false };
  },

  /** 拖动中：实时更新位置并钳制在边界内（左右屏内回弹、上不超导航、下不超输入栏） */
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

  /** 拖动结束：移动则记忆位置；未移动视为轻点 → 打开历史弹层 */
  onFabTouchEnd() {
    if (!this._fabDrag) return;
    const dragged = this._fabDrag.moved;
    if (dragged) {
      try {
        wx.setStorageSync(STORAGE_KEY.HISTORY_FAB_POS, { x: this.data.fabX, y: this.data.fabY });
      } catch (e) { /* 存储失败不阻断（位置仅本次会话有效） */ }
    }
    this._fabDrag = null;
    if (!dragged) this.onHistoryTap();
  },

  /** 打开历史弹层：加载历史会话列表 */
  onHistoryTap() {
    if (this.data.sending) return;
    this.loadHistory();
    this.setData({ historyVisible: true, selecting: false });
    this.clearHistorySelection();
  },

  /** 阻止遮罩层触摸事件穿透（历史弹层打开时配合 msg-area scroll-y=false，避免手势竞争） */
  preventTouchMove() {},

  /** 关闭历史弹层 */
  onCloseHistory() {
    this.setData({ historyVisible: false, selecting: false });
    this.clearHistorySelection();
  },

  /**
   * 校准历史列表滚动区高度（px）：测量 scroll-view 内容总高，
   * 目标高度 = min(内容高, 屏幕50% - header)，给 scroll-view 明确 px 高度保证可滚动。
   * 不依赖 flex 高度约束——部分真机对 fixed + flex + max-height 的 scroll-view 高度计算不可靠。
   */
  calibrateHistoryHeight() {
    const query = wx.createSelectorQuery().in(this);
    query.select('#historyScroll').fields({ size: true, scrollOffset: true }).exec((res) => {
      const info = res && res[0];
      if (!info) return;
      const contentH = info.scrollHeight || info.height || 0;
      if (!contentH) return;
      const win = wx.getSystemInfoSync();
      // 上限：屏幕 50% 减 header 估算高度（header 约 100rpx）
      const headerPx = (100 * win.windowWidth) / 750;
      const maxH = Math.max(win.windowHeight * 0.5 - headerPx, 120);
      const target = Math.round(Math.min(contentH, maxH));
      this.setData({ historyListHeight: target });
    });
  },

  /** 加载历史会话列表（分页 20，reset=true 重置第一页） */
  loadHistory(reset = true) {
    if (this.data.loadingHistory) return;
    const page = reset ? 0 : this.data.historyPage + 1;
    this.setData({ loadingHistory: true });
    api.get('/api/agent/history', { page, size: 20 })
      .then((data) => {
        const content = (data && data.content) || [];
        // 展开保留后端全部字段（id 字段即会话级 conversationId）；空标题兜底显示占位
        const items = content.map((c) => ({
          ...c,
          title: c.title || '未命名对话',
          // 更新时间格式化为可读（后端 LocalDateTime，取前 16 位）
          updatedAtText: (c.updatedAt || '').replace('T', ' ').slice(0, 16)
        }));
        this.setData({
          historyList: reset ? items : this.data.historyList.concat(items),
          historyPage: page,
          historyTotal: (data && data.totalElements) || 0
        }, () => {
          // 首屏数据渲染完成后校准滚动区高度（给出明确 px 高度，内容超出时才能滚动）
          if (reset) this.calibrateHistoryHeight();
        });
      })
      .catch(() => {
        if (reset) this.setData({ historyList: [] });
      })
      .finally(() => this.setData({ loadingHistory: false }));
  },

  /** 历史列表滚动到底部：加载更多 */
  onHistoryScrollLower() {
    if (this.data.historyList.length < this.data.historyTotal) {
      this.loadHistory(false);
    }
  },

  /** 点击历史项：恢复该会话继续对话（非批量选择模式；dataset.id 为会话级 conversationId） */
  onHistoryItemTap(e) {
    if (this.data.selecting) {
      this.toggleSelect(e.currentTarget.dataset.id);
      return;
    }
    const conversationId = e.currentTarget.dataset.id;
    // 当前有进行中对话时二次确认（后端恢复前会自动归档当前热会话，前端避免误触）
    if (this.data.messages.length > 0) {
      wx.showModal({
        title: '切换对话',
        content: '切换到该历史对话？当前对话将自动保存',
        success: (r) => {
          if (r.confirm) this.doResume(conversationId);
        }
      });
      return;
    }
    this.doResume(conversationId);
  },

  /** 点击历史项右侧「切换」：弹出确认后切换至该对话（catchtap 防止触发整行 onHistoryItemTap） */
  onSwitchTap(e) {
    const conversationId = e.currentTarget.dataset.id;
    wx.showModal({
      title: '切换对话',
      content: '是否要切换至该对话？当前对话将自动保存',
      confirmText: '切换',
      confirmColor: '#0071E3',
      success: (r) => {
        if (r.confirm) this.doResume(conversationId);
      }
    });
  },

  /** 点击历史项右侧「删除」图标：弹确认后删除该会话（catchtap 防止触发整行 onHistoryItemTap） */
  onDeleteTap(e) {
    const conversationId = e.currentTarget.dataset.id;
    wx.showModal({
      title: '删除确认',
      content: '删除该对话？删除后不可恢复',
      confirmText: '删除',
      confirmColor: '#FF3B30',
      success: (r) => {
        if (!r.confirm) return;
        api.del('/api/agent/history', { ids: [conversationId] })
          .then(() => {
            wx.showToast({ title: '已删除', icon: 'success' });
            this.loadHistory();
          })
          .catch((err) => wx.showToast({ title: err.message || '删除失败', icon: 'none' }));
      }
    });
  },

  /** 执行恢复：按会话级 conversationId 恢复（后端返回回填的最近消息，前端渲染对话内容） */
  doResume(conversationId) {
    api.post(`/api/agent/history/${conversationId}/resume`)
      .then((data) => {
        // 后端返回恢复的最近消息（sources/actions 为 JSON 字符串或 null），转成前端消息结构展示
        const items = (data && data.messages) || [];
        const messages = items.map((m) => ({
          id: 'r' + (++this._msgSeq),
          role: m.role === 'assistant' ? 'assistant' : 'user',
          content: m.content || '',
          // 恢复的 AI 回复同样按 Markdown 块渲染（用户消息不渲染块）
          blocks: m.role === 'assistant' ? parseMarkdown(m.content || '') : [],
          sources: parseJsonArray(m.sources),
          actions: parseJsonArray(m.actions),
          streaming: false,
          failed: false
        }));
        // 恢复后是全新会话上下文：清空流式状态（事件队列、字符缓冲、看门狗由下轮发送或页面卸载兜底）
        this._currentMsgId = null;
        this._sseBuffer = '';
        this._eventQueue = [];
        this._charBuffer = null;
        this._charIdx = 0;
        this._rawContent = '';
        this.setData({ messages, historyVisible: false });
        if (messages.length > 0) {
          wx.showToast({ title: '已切换到该对话', icon: 'none' });
          this.scrollToBottom();
        }
      })
      .catch((err) => wx.showToast({ title: err.message || '切换失败', icon: 'none' }));
  },

  /** 长按进入批量选择模式（清空历史项选中标记，checkbox 由 item.selected 驱动） */
  onHistoryLongPress() {
    if (this.data.historyList.length === 0) return;
    this.setData({ selecting: true });
    this.clearHistorySelection();
  },

  /** 批量选择切换：以会话级 conversationId 为单位（String 归一化避免 number/string 类型不一致） */
  toggleSelect(conversationId) {
    const key = String(conversationId);
    const historyList = this.data.historyList.map((item) =>
      String(item.id) === key ? { ...item, selected: !item.selected } : item
    );
    this.setData({ historyList });
  },

  /** 清除历史列表所有项的选中标记（退出多选 / 开关弹层时调用，与 selecting 状态解耦） */
  clearHistorySelection() {
    const historyList = this.data.historyList.map((item) => ({ ...item, selected: false }));
    this.setData({ historyList });
  },

  /** 批量删除所选会话：按会话级 conversationId 删除（后端删除该会话全部段 + 压缩段） */
  onBatchDelete() {
    // 待删 id 从列表项的 selected 标记汇总（会话级去重，同一会话多段只对应一条）
    const ids = this.data.historyList.filter((item) => item.selected).map((item) => item.id);
    if (ids.length === 0) {
      wx.showToast({ title: '请先选择会话', icon: 'none' });
      return;
    }
    wx.showModal({
      title: '删除确认',
      content: `删除所选 ${ids.length} 个会话？删除后不可恢复`,
      confirmColor: '#FF3B30',
      success: (r) => {
        if (!r.confirm) return;
        api.del('/api/agent/history', { ids })
          .then(() => {
            wx.showToast({ title: '已删除', icon: 'success' });
            this.setData({ selecting: false });
            this.clearHistorySelection();
            this.loadHistory();
          })
          .catch((err) => wx.showToast({ title: err.message || '删除失败', icon: 'none' }));
      }
    });
  },

  /** 页面卸载时中止未完成流 + 清理看门狗与录音计时器 + 标记"从对话页返回" */
  onUnload() {
    // 对话页被关闭（手势返回 / ‹ 按钮返回中转页）：中转页据此回首页 tab
    if (getApp().globalData) getApp().globalData.assistantReturning = true;
    if (this._streamWatchdog) { clearTimeout(this._streamWatchdog); this._streamWatchdog = null; }
    if (this._queueTimer) { clearTimeout(this._queueTimer); this._queueTimer = null; }
    this._eventQueue = [];   // 清空事件队列，防止后台继续消费蹦字
    this._charBuffer = null; // 清空字符缓冲，防止残留字符继续蹦出
    this._charIdx = 0;
    this._rawContent = '';
    if (this._abortStream) {
      this._abortStream();
      this._abortStream = null;
    }
    if (this._recordTimer) {
      clearInterval(this._recordTimer);
      this._recordTimer = null;
    }
    // fire-and-forget 通知后端结束会话并归档剩余对话（空闲 15 分钟只是兜底）。
    // 不等待、不阻塞、不提示：失败不影响页面关闭，仅留 warn 便于定位（对齐 code-standards 禁空 catch）
    api.post('/api/agent/exit').catch(() => {
      console.warn('[assistant] 退出会话通知失败，后端将在空闲后兜底归档');
    });
  }
});
