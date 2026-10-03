package com.platform.ai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.ai.PolishingClient;
import com.platform.ai.common.PromptRepository;
import com.platform.ai.search.KnowledgeHit;
import com.platform.ai.search.KnowledgeRetrievalService;
import com.platform.common.ActivityRole;
import com.platform.common.AppTimeZone;
import com.platform.common.BorrowStatus;
import com.platform.common.PostStatus;
import com.platform.common.PostType;
import com.platform.model.dto.ApprovalCountDTO;
import com.platform.model.dto.BorrowResponseDTO;
import com.platform.model.dto.HelpResponseDTO;
import com.platform.model.dto.IdleItemDTO;
import com.platform.model.dto.MyPostItemDTO;
import com.platform.model.dto.NotificationDTO;
import com.platform.model.dto.PageDTO;
import com.platform.model.entity.User;
import com.platform.repository.UserRepository;
import com.platform.service.BorrowService;
import com.platform.service.HelpService;
import com.platform.service.IdleService;
import com.platform.service.NotificationService;
import com.platform.service.UserActivityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AgentToolDispatcher 读工具执行器单元测试 — 覆盖知识检索工具、日期解析、常用业务工具与既有读工具。
 *
 * <p>知识检索工具化（3b）新增：searchKnowledge 参数校验/权限/检索异常兜底/命中缓存、query_date
 * 相对日期解析、query_notifications / query_help_requests / my_todos，以及 requestId 工具计数上限。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentToolDispatcher 读工具执行器单元测试")
class AgentToolDispatcherTest {

    @Mock
    private IdleService idleService;
    @Mock
    private BorrowService borrowService;
    @Mock
    private PolishingClient polishingClient;
    @Mock
    private KnowledgeRetrievalService retrievalService;
    @Mock
    private UserRepository userRepository;
    @Mock
    private NotificationService notificationService;
    @Mock
    private HelpService helpService;
    @Mock
    private UserActivityService userActivityService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private AgentToolDispatcher dispatcher;

    private static final Long USER_ID = 1L;
    private static final Long TENANT_ID = 10L;
    private static final String REQ_1 = "req-1";

    @BeforeEach
    void setUp() {
        dispatcher = new AgentToolDispatcher(idleService, borrowService, polishingClient, objectMapper,
                retrievalService, userRepository, notificationService, helpService, userActivityService,
                new PromptRepository());
        // @Value 字段默认 0（int）会让工具计数关卡永远命中，测试须显式注入上限
        ReflectionTestUtils.setField(dispatcher, "maxToolCalls", 5);
    }

    private User userWithTenant() {
        return User.builder().id(USER_ID).tenantId(TENANT_ID).build();
    }

    // ==================== search_knowledge ====================

    @Test
    @DisplayName("search_knowledge - 正常命中返回格式化文本并写入命中缓存")
    void should_searchKnowledge_returnFormattedHits() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(userWithTenant()));
        KnowledgeHit hit = new KnowledgeHit(1L, "装修时间规定", "工作日 8:00-12:00，周末及法定节假日不得施工。", "rules", "小区规章制度", 0.1, null, null);
        when(retrievalService.searchForAgent(TENANT_ID, "装修时间")).thenReturn(List.of(hit));

        String result = dispatcher.searchKnowledge(USER_ID, REQ_1, new KnowledgeSearchParams("装修时间"));

        assertThat(result).startsWith("找到以下小区资料：");
        assertThat(result).contains("第 1 条 | 标题：《装修时间规定》 | 来源：小区规章制度 | 分类：rules");
        assertThat(result).contains("内容：工作日 8:00-12:00，周末及法定节假日不得施工。");

        // 命中写入 requestId 缓存，takeHits 取回并清除
        List<KnowledgeHit> taken = dispatcher.takeHits(REQ_1);
        assertThat(taken).hasSize(1);
        assertThat(taken.get(0).id()).isEqualTo(1L);
        assertThat(dispatcher.takeHits(REQ_1)).isEmpty();
    }

    @Test
    @DisplayName("search_knowledge - 正文超 200 字截断补省略号")
    void should_truncateContent_when_tooLong() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(userWithTenant()));
        String longContent = "内".repeat(250);
        KnowledgeHit hit = new KnowledgeHit(1L, "标题", longContent, "rules", "小区规章制度", 0.1, null, null);
        when(retrievalService.searchForAgent(TENANT_ID, "关键词")).thenReturn(List.of(hit));

        String result = dispatcher.searchKnowledge(USER_ID, REQ_1, new KnowledgeSearchParams("关键词"));

        assertThat(result).contains("内容：" + "内".repeat(200) + "……");
    }

    @Test
    @DisplayName("search_knowledge - 关键词为空或纯符号返回无效提示")
    void should_returnInvalidKeyword_when_blankOrSymbols() {
        assertThat(dispatcher.searchKnowledge(USER_ID, REQ_1, new KnowledgeSearchParams("   ")))
                .isEqualTo("检索关键词无效，请换个说法描述你想查什么");
        assertThat(dispatcher.searchKnowledge(USER_ID, REQ_1, new KnowledgeSearchParams("。。。")))
                .isEqualTo("检索关键词无效，请换个说法描述你想查什么");
        // 参数校验阶段直接返回，不触达用户查询
        verify(userRepository, never()).findById(any());
    }

    @Test
    @DisplayName("search_knowledge - 单字关键词返回太短提示")
    void should_returnTooShort_when_singleChar() {
        assertThat(dispatcher.searchKnowledge(USER_ID, REQ_1, new KnowledgeSearchParams("装")))
                .isEqualTo("检索关键词太短，请提供更具体的关键词");
    }

    @Test
    @DisplayName("search_knowledge - 超 50 字关键词截断后检索")
    void should_truncateKeyword_when_over50Chars() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(userWithTenant()));
        when(retrievalService.searchForAgent(eq(TENANT_ID), anyString())).thenReturn(List.of());
        String keyword = "很".repeat(60);

        dispatcher.searchKnowledge(USER_ID, REQ_1, new KnowledgeSearchParams(keyword));

        verify(retrievalService).searchForAgent(eq(TENANT_ID), eq(keyword.substring(0, 50)));
    }

    @Test
    @DisplayName("search_knowledge - 无 tenantId（非业主/租客）不可使用知识库")
    void should_returnNoPermission_when_noTenant() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(User.builder().id(USER_ID).tenantId(null).build()));

        String result = dispatcher.searchKnowledge(USER_ID, REQ_1, new KnowledgeSearchParams("物业"));

        assertThat(result).isEqualTo("仅业主/租客可使用小区知识库");
        verify(retrievalService, never()).searchForAgent(any(), any());
    }

    @Test
    @DisplayName("search_knowledge - 用户不存在同样按无权限处理")
    void should_returnNoPermission_when_userNotFound() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        String result = dispatcher.searchKnowledge(USER_ID, REQ_1, new KnowledgeSearchParams("物业"));

        assertThat(result).isEqualTo("仅业主/租客可使用小区知识库");
    }

    @Test
    @DisplayName("search_knowledge - 命中为空返回未找到提示")
    void should_returnNoResult_when_hitsEmpty() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(userWithTenant()));
        when(retrievalService.searchForAgent(TENANT_ID, "物业")).thenReturn(List.of());

        String result = dispatcher.searchKnowledge(USER_ID, REQ_1, new KnowledgeSearchParams("物业"));

        assertThat(result).isEqualTo("知识库未找到相关内容");
    }

    @Test
    @DisplayName("search_knowledge - 检索异常时返回可读兜底不抛异常")
    void should_returnErrorReply_when_retrievalThrows() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(userWithTenant()));
        when(retrievalService.searchForAgent(TENANT_ID, "物业")).thenThrow(new RuntimeException("检索服务不可用"));

        String result = dispatcher.searchKnowledge(USER_ID, REQ_1, new KnowledgeSearchParams("物业"));

        assertThat(result).isEqualTo("知识检索暂不可用，请稍后再试");
    }

    @Test
    @DisplayName("search_knowledge - 同 requestId 多次调用命中合并去重，takeHits 清除缓存")
    void should_mergeHits_byRequestId() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(userWithTenant()));
        KnowledgeHit shared = new KnowledgeHit(1L, "装修规定", "内容A", "rules", "小区规章", 0.1, null, null);
        KnowledgeHit unique1 = new KnowledgeHit(2L, "垃圾投放", "内容B", "service", "服务手册", 0.2, null, null);
        KnowledgeHit unique2 = new KnowledgeHit(3L, "宠物管理", "内容C", "rules", "小区规章", 0.3, null, null);
        when(retrievalService.searchForAgent(eq(TENANT_ID), anyString()))
                .thenReturn(List.of(shared, unique1), List.of(shared, unique2));

        dispatcher.searchKnowledge(USER_ID, REQ_1, new KnowledgeSearchParams("关键词"));
        dispatcher.searchKnowledge(USER_ID, REQ_1, new KnowledgeSearchParams("关键词"));

        List<KnowledgeHit> taken = dispatcher.takeHits(REQ_1);
        assertThat(taken).hasSize(3);
        assertThat(taken).extracting(KnowledgeHit::id).containsExactly(1L, 2L, 3L);
        // takeHits 清除缓存：再次取回为空
        assertThat(dispatcher.takeHits(REQ_1)).isEmpty();
    }

    // ==================== 工具计数与 reset ====================

    @Test
    @DisplayName("工具计数 - 同一 requestId 超 maxToolCalls 后不再执行并返回上限文案")
    void should_returnLimitReply_when_exceedToolLimit() {
        for (int i = 0; i < 5; i++) {
            dispatcher.queryDate(USER_ID, REQ_1, new DateQueryParams("今天"));
        }
        String limited = dispatcher.queryDate(USER_ID, REQ_1, new DateQueryParams("明天"));
        assertThat(limited).isEqualTo("已达到本轮工具调用上限，请直接回答用户");

        // reset 后计数清零，可继续执行
        dispatcher.reset(REQ_1);
        String afterReset = dispatcher.queryDate(USER_ID, REQ_1, new DateQueryParams("明天"));
        assertThat(afterReset).startsWith("明天是 ");
    }

    // ==================== query_date 相对日期解析 ====================

    @Test
    @DisplayName("query_date - 今天/明天/昨天/前天/后天/大后天/N天前/N天后 解析")
    void should_resolveDate_when_relativeExpressions() {
        LocalDate today = LocalDate.now(AppTimeZone.APP_ZONE);
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy年M月d日");
        List<String> weekdays = List.of("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日");

        assertDate(dispatcher, "今天", today, fmt, weekdays);
        assertDate(dispatcher, "明天", today.plusDays(1), fmt, weekdays);
        assertDate(dispatcher, "昨天", today.minusDays(1), fmt, weekdays);
        assertDate(dispatcher, "前天", today.minusDays(2), fmt, weekdays);
        assertDate(dispatcher, "后天", today.plusDays(2), fmt, weekdays);
        assertDate(dispatcher, "大后天", today.plusDays(3), fmt, weekdays);
        assertDate(dispatcher, "3天后", today.plusDays(3), fmt, weekdays);
        assertDate(dispatcher, "5天前", today.minusDays(5), fmt, weekdays);
    }

    @Test
    @DisplayName("query_date - 下周X/周X/星期X 解析")
    void should_resolveWeekday_when_weekdayExpressions() {
        LocalDate today = LocalDate.now(AppTimeZone.APP_ZONE);
        int current = today.getDayOfWeek().getValue();   // 1=周一 … 7=周日
        LocalDate thisWed = today.plusDays((3 - current + 7) % 7);
        LocalDate nextWed = thisWed.plusDays(7);
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy年M月d日");
        List<String> weekdays = List.of("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日");

        String thisWeek = dispatcher.queryDate(USER_ID, REQ_1, new DateQueryParams("周三"));
        assertThat(thisWeek).isEqualTo("周三是 " + thisWed.format(fmt) + " " + weekdays.get(thisWed.getDayOfWeek().getValue() - 1));

        String nextWeek = dispatcher.queryDate(USER_ID, REQ_1, new DateQueryParams("下周三"));
        assertThat(nextWeek).isEqualTo("下周三是 " + nextWed.format(fmt) + " " + weekdays.get(nextWed.getDayOfWeek().getValue() - 1));

        // 星期天 等价于 周日
        String sunday = dispatcher.queryDate(USER_ID, REQ_1, new DateQueryParams("星期天"));
        assertThat(sunday).startsWith("星期天是 ");
    }

    @Test
    @DisplayName("query_date - 无法识别的日期表达返回兜底提示")
    void should_returnParseFail_when_unrecognized() {
        assertThat(dispatcher.queryDate(USER_ID, REQ_1, new DateQueryParams("下周")))
                .isEqualTo("无法识别该日期描述，请换个说法");
        assertThat(dispatcher.queryDate(USER_ID, REQ_1, new DateQueryParams("晚上")))
                .isEqualTo("无法识别该日期描述，请换个说法");
        assertThat(dispatcher.queryDate(USER_ID, REQ_1, new DateQueryParams(null)))
                .isEqualTo("无法识别该日期描述，请换个说法");
        assertThat(dispatcher.queryDate(USER_ID, REQ_1, new DateQueryParams("  ")))
                .isEqualTo("无法识别该日期描述，请换个说法");
    }

    @Test
    @DisplayName("query_date - 本地解析异常（超大偏移量溢出）返回防御性兜底不抛异常")
    void should_returnDateErrorReply_when_resolveThrows() {
        // 本地解析理论不抛，但「N天后」的 N 超 Integer.MAX_VALUE 时 Integer.parseInt 抛
        // NumberFormatException → 触发 query_date 的防御性 catch 兜底（模块 4b 兜底核查）
        String result = dispatcher.queryDate(USER_ID, REQ_1, new DateQueryParams("99999999999999999999天后"));

        assertThat(result).isEqualTo("查询日期暂不可用，请稍后再试");
    }

    private void assertDate(AgentToolDispatcher dispatcher, String expr, LocalDate expected,
                            DateTimeFormatter fmt, List<String> weekdays) {
        // 每次调用用独立 requestId，避免多次 query_date 触发工具计数上限
        String result = dispatcher.queryDate(USER_ID, "req-date-" + expr, new DateQueryParams(expr));
        String expectedText = expr + "是 " + expected.format(fmt) + " " + weekdays.get(expected.getDayOfWeek().getValue() - 1);
        assertThat(result).isEqualTo(expectedText);
    }

    // ==================== query_notifications ====================

    @Test
    @DisplayName("query_notifications - 返回最新通知列表与未读数")
    void should_queryNotifications_returnList() {
        NotificationDTO n = NotificationDTO.builder()
                .title("停水通知").content("今晚10点至次日6点停水").isRead(false)
                .createdAt(LocalDateTime.of(2026, 8, 7, 9, 0)).build();
        when(notificationService.getNotifications(USER_ID)).thenReturn(List.of(n));
        when(notificationService.getUnreadCount(USER_ID)).thenReturn(1L);

        String result = dispatcher.queryNotifications(USER_ID, REQ_1, new VoidParams());

        assertThat(result).contains("共 1 条通知，未读 1 条");
        assertThat(result).contains("停水通知");
        assertThat(result).contains("内容：今晚10点至次日6点停水");
        assertThat(result).contains("时间：2026-08-07 09:00");
        assertThat(result).contains("未读");
    }

    @Test
    @DisplayName("query_notifications - 无通知返回兜底")
    void should_queryNotifications_returnEmptyReply() {
        when(notificationService.getNotifications(USER_ID)).thenReturn(List.of());
        when(notificationService.getUnreadCount(USER_ID)).thenReturn(0L);

        assertThat(dispatcher.queryNotifications(USER_ID, REQ_1, new VoidParams()))
                .isEqualTo("目前没有新的小区通知");
    }

    @Test
    @DisplayName("query_notifications - 服务异常返回可读兜底不抛异常")
    void should_queryNotifications_degradeOnError() {
        when(notificationService.getNotifications(USER_ID)).thenThrow(new RuntimeException("db down"));

        assertThat(dispatcher.queryNotifications(USER_ID, REQ_1, new VoidParams()))
                .isEqualTo("查询小区通知暂不可用，请稍后再试");
    }

    // ==================== query_help_requests ====================

    @Test
    @DisplayName("query_help_requests - 返回互助求助列表")
    void should_queryHelpRequests_returnList() {
        HelpResponseDTO dto = HelpResponseDTO.builder()
                .title("帮忙搬家具").category("搬运").isUrgent(true)
                .createdAt(LocalDateTime.of(2026, 8, 7, 10, 0)).userRoom("3栋2单元1502").build();
        PageDTO<HelpResponseDTO> page = PageDTO.<HelpResponseDTO>builder().content(List.of(dto)).totalElements(1).build();
        when(helpService.search(USER_ID, "搬", 0, 5)).thenReturn(page);

        String result = dispatcher.queryHelpRequests(USER_ID, REQ_1, new HelpSearchParams("搬"));

        assertThat(result).contains("找到以下互助求助：");
        assertThat(result).contains("1. 标题：帮忙搬家具 | 分类：搬运 | 是否紧急：是 | 时间：2026-08-07 10:00 | 发起人房号：3栋2单元1502");
    }

    @Test
    @DisplayName("query_help_requests - 无结果返回兜底")
    void should_queryHelpRequests_returnEmptyReply() {
        when(helpService.search(USER_ID, null, 0, 5))
                .thenReturn(PageDTO.<HelpResponseDTO>builder().content(List.of()).totalElements(0).build());

        assertThat(dispatcher.queryHelpRequests(USER_ID, REQ_1, new HelpSearchParams(null)))
                .isEqualTo("目前没有找到相关的互助求助");
    }

    @Test
    @DisplayName("query_help_requests - 服务异常返回可读兜底不抛异常")
    void should_queryHelpRequests_degradeOnError() {
        when(helpService.search(USER_ID, "搬", 0, 5)).thenThrow(new RuntimeException("db down"));

        assertThat(dispatcher.queryHelpRequests(USER_ID, REQ_1, new HelpSearchParams("搬")))
                .isEqualTo("查询互助求助暂不可用，请稍后再试");
    }

    // ==================== my_todos ====================

    @Test
    @DisplayName("my_todos - 返回待审批与进行中汇总")
    void should_myTodos_returnSummary() {
        when(userActivityService.getApprovalCounts(USER_ID)).thenReturn(
                ApprovalCountDTO.builder().borrow(1).lend(1).help(1).total(3).build());
        when(userActivityService.getInProgress(USER_ID, ActivityRole.BORROW)).thenReturn(List.of(MyPostItemDTO.builder().title("电钻").build()));
        when(userActivityService.getInProgress(USER_ID, ActivityRole.LEND)).thenReturn(List.of());
        when(userActivityService.getInProgress(USER_ID, ActivityRole.HELP_REQ)).thenReturn(List.of(MyPostItemDTO.builder().title("搬家").build()));
        when(userActivityService.getInProgress(USER_ID, ActivityRole.HELP_PRO)).thenReturn(List.of());

        String result = dispatcher.myTodos(USER_ID, REQ_1, new VoidParams());

        assertThat(result).contains("待审批 3 项（借入确认 1、借出审批 1、帮助申请 1）；");
        assertThat(result).contains("进行中 2 项：");
        assertThat(result).contains("1. 电钻；");
        assertThat(result).contains("2. 搬家；");
    }

    @Test
    @DisplayName("my_todos - 无待办返回兜底")
    void should_myTodos_returnEmptyReply() {
        when(userActivityService.getApprovalCounts(USER_ID)).thenReturn(
                ApprovalCountDTO.builder().borrow(0).lend(0).help(0).total(0).build());
        when(userActivityService.getInProgress(USER_ID, ActivityRole.BORROW)).thenReturn(List.of());
        when(userActivityService.getInProgress(USER_ID, ActivityRole.LEND)).thenReturn(List.of());
        when(userActivityService.getInProgress(USER_ID, ActivityRole.HELP_REQ)).thenReturn(List.of());
        when(userActivityService.getInProgress(USER_ID, ActivityRole.HELP_PRO)).thenReturn(List.of());

        assertThat(dispatcher.myTodos(USER_ID, REQ_1, new VoidParams()))
                .isEqualTo("目前没有待处理的事项");
    }

    @Test
    @DisplayName("my_todos - 服务异常返回可读兜底不抛异常")
    void should_myTodos_degradeOnError() {
        when(userActivityService.getApprovalCounts(USER_ID)).thenThrow(new RuntimeException("db down"));

        assertThat(dispatcher.myTodos(USER_ID, REQ_1, new VoidParams()))
                .isEqualTo("查询我的待办暂不可用，请稍后再试");
    }

    // ==================== 既有读工具（补 requestId 参数） ====================

    @Test
    @DisplayName("search_items - 返回物品摘要 JSON，缺失字段补空串")
    void should_searchItems_returnSummaryJson() throws Exception {
        IdleItemDTO dto = IdleItemDTO.builder()
                .id(1L).title("博世冲击钻").description(null).category(null).build();
        PageDTO<IdleItemDTO> page = PageDTO.<IdleItemDTO>builder()
                .content(List.of(dto)).totalElements(1).build();
        when(idleService.search(eq(USER_ID), eq("钻"), eq(PostType.LEND), eq(0), eq(5), eq("hybrid")))
                .thenReturn(page);

        String result = dispatcher.searchItems(USER_ID, REQ_1, new SearchParams("钻", null));

        var list = objectMapper.readTree(result);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("id").asLong()).isEqualTo(1L);
        assertThat(list.get(0).get("title").asText()).isEqualTo("博世冲击钻");
        assertThat(list.get(0).get("description").asText()).isEmpty();
        assertThat(list.get(0).get("category").asText()).isEmpty();
    }

    @Test
    @DisplayName("search_items - 中文别名 postType 归一化为枚举值")
    void should_normalizePostType_when_chineseAlias() {
        PageDTO<IdleItemDTO> page = PageDTO.<IdleItemDTO>builder().content(List.of()).totalElements(0).build();
        when(idleService.search(eq(USER_ID), eq("梯子"), eq(PostType.WANTED), eq(0), eq(5), eq("hybrid")))
                .thenReturn(page);

        dispatcher.searchItems(USER_ID, REQ_1, new SearchParams("梯子", "求借"));

        verify(idleService).search(eq(USER_ID), eq("梯子"), eq(PostType.WANTED), eq(0), eq(5), eq("hybrid"));
    }

    @Test
    @DisplayName("my_posts - 返回我的发布摘要 JSON")
    void should_myPosts_returnPostsJson() throws Exception {
        IdleItemDTO dto = IdleItemDTO.builder()
                .id(3L).title("电饭煲").status(PostStatus.ONLINE).build();
        when(idleService.getMyPosts(USER_ID, PostType.LEND)).thenReturn(List.of(dto));

        String result = dispatcher.myPosts(USER_ID, REQ_1, new MyPostsParams(null));

        var list = objectMapper.readTree(result);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("id").asLong()).isEqualTo(3L);
        assertThat(list.get(0).get("status").asText()).isEqualTo(PostStatus.ONLINE);
    }

    @Test
    @DisplayName("my_borrows_due - 仅返回进行中（active/approved）的借用")
    void should_myBorrowsDue_filterActiveOnly() throws Exception {
        BorrowResponseDTO active = BorrowResponseDTO.builder()
                .idleId(1L).idleTitle("电钻").durationDays(3).status(BorrowStatus.ACTIVE).build();
        BorrowResponseDTO approved = BorrowResponseDTO.builder()
                .idleId(2L).idleTitle("梯子").durationDays(null).status(BorrowStatus.APPROVED).build();
        BorrowResponseDTO returned = BorrowResponseDTO.builder()
                .idleId(3L).idleTitle("锤子").durationDays(1).status(BorrowStatus.RETURNED).build();
        BorrowResponseDTO pending = BorrowResponseDTO.builder()
                .idleId(4L).idleTitle("扳手").durationDays(1).status(BorrowStatus.PENDING).build();
        when(borrowService.getMyApplications(USER_ID))
                .thenReturn(List.of(active, approved, returned, pending));

        String result = dispatcher.myBorrowsDue(USER_ID, REQ_1, new VoidParams());

        var list = objectMapper.readTree(result);
        assertThat(list).hasSize(2);
        // 仅 active/approved 进入结果
        assertThat(list.get(0).get("idleId").asLong()).isEqualTo(1L);
        assertThat(list.get(1).get("idleId").asLong()).isEqualTo(2L);
        // durationDays 缺失时补 0
        assertThat(list.get(1).get("durationDays").asInt()).isZero();
    }

    @Test
    @DisplayName("generate_feedback - 角色为空时默认借出方，JSON 包裹返回")
    void should_generateFeedback_defaultRoleLend() throws Exception {
        when(polishingClient.generateFeedback(ActivityRole.LEND, "电钻", "借用顺利"))
                .thenReturn("邻居把电钻借给我用，用起来很顺手，归还也顺利，感谢！");

        String result = dispatcher.generateFeedback(USER_ID, REQ_1, new FeedbackParams(null, "电钻", "借用顺利"));

        var json = objectMapper.readTree(result);
        assertThat(json.get("feedback").asText()).contains("感谢");
    }

    @Test
    @DisplayName("generate_feedback - 未知 postType 映射到 LEND")
    void should_normalizeUnknownPostType_toLend() {
        PageDTO<IdleItemDTO> page = PageDTO.<IdleItemDTO>builder().content(List.of()).totalElements(0).build();
        when(idleService.search(eq(USER_ID), anyString(), eq(PostType.LEND), eq(0), eq(5), eq("hybrid")))
                .thenReturn(page);

        // "出借"/"借出"/未知值均不在别名映射中，统一兜底为 LEND
        dispatcher.searchItems(USER_ID, REQ_1, new SearchParams("伞", "出借"));
        dispatcher.searchItems(USER_ID, REQ_1, new SearchParams("伞", "借出"));
        dispatcher.searchItems(USER_ID, REQ_1, new SearchParams("伞", "其他类型"));

        verify(idleService, org.mockito.Mockito.times(3))
                .search(eq(USER_ID), anyString(), eq(PostType.LEND), eq(0), eq(5), eq("hybrid"));
    }

    @Test
    @DisplayName("search_items - 非 LEND 英文枚举原样透传")
    void should_passThroughEnglishEnum_when_valid() {
        PageDTO<IdleItemDTO> page = PageDTO.<IdleItemDTO>builder().content(List.of()).totalElements(0).build();
        when(idleService.search(eq(USER_ID), eq("帮忙"), eq(PostType.HELP), eq(0), eq(5), eq("hybrid")))
                .thenReturn(page);

        dispatcher.searchItems(USER_ID, REQ_1, new SearchParams("帮忙", "HELP"));

        verify(idleService).search(eq(USER_ID), eq("帮忙"), eq(PostType.HELP), eq(0), eq(5), eq("hybrid"));
    }

    // ==================== 工具兜底核查补齐（Step 4b：全部工具异常不抛给模型） ====================

    @Test
    @DisplayName("search_items - 服务异常返回可读兜底不抛异常")
    void should_searchItems_degradeOnError() {
        when(idleService.search(any(), any(), any(), anyInt(), anyInt(), any()))
                .thenThrow(new RuntimeException("db down"));

        assertThat(dispatcher.searchItems(USER_ID, REQ_1, new SearchParams("钻", null)))
                .isEqualTo("搜索闲置物品暂不可用，请稍后再试");
    }

    @Test
    @DisplayName("my_posts - 服务异常返回可读兜底不抛异常")
    void should_myPosts_degradeOnError() {
        when(idleService.getMyPosts(USER_ID, PostType.LEND)).thenThrow(new RuntimeException("db down"));

        assertThat(dispatcher.myPosts(USER_ID, REQ_1, new MyPostsParams(null)))
                .isEqualTo("查询我的发布暂不可用，请稍后再试");
    }

    @Test
    @DisplayName("my_borrows_due - 服务异常返回可读兜底不抛异常")
    void should_myBorrowsDue_degradeOnError() {
        when(borrowService.getMyApplications(USER_ID)).thenThrow(new RuntimeException("db down"));

        assertThat(dispatcher.myBorrowsDue(USER_ID, REQ_1, new VoidParams()))
                .isEqualTo("查询我的借用暂不可用，请稍后再试");
    }

    @Test
    @DisplayName("generate_feedback - 服务异常返回可读兜底不抛异常")
    void should_generateFeedback_degradeOnError() {
        when(polishingClient.generateFeedback(any(), any(), any()))
                .thenThrow(new RuntimeException("上游不可用"));

        assertThat(dispatcher.generateFeedback(USER_ID, REQ_1, new FeedbackParams(null, "电钻", "借用顺利")))
                .isEqualTo("生成互助感想暂不可用，请稍后再试");
    }
}
