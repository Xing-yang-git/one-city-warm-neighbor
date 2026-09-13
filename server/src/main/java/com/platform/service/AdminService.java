package com.platform.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.BizException;
import com.platform.common.BizStatus;
import com.platform.common.DamageType;
import com.platform.common.ModerationStatus;
import com.platform.common.NotificationType;
import com.platform.common.PostType;
import com.platform.common.ReturnStatus;
import com.platform.common.UserFormatter;
import com.platform.common.UserType;
import com.platform.common.VersionConflictException;
import com.platform.model.dto.AuditRequest;
import com.platform.model.dto.ContentItemDTO;
import com.platform.model.dto.ContentOfflineRequest;
import com.platform.model.dto.DashboardDTO;
import com.platform.model.dto.AdminDTO;
import com.platform.model.dto.AuditCountDTO;
import com.platform.model.dto.BuildingDTO;
import com.platform.model.dto.CommunityBuildingDTO;
import com.platform.model.dto.CommunityDTO;
import com.platform.model.dto.CommunityUnitDTO;
import com.platform.model.dto.ContentCountDTO;
import com.platform.model.dto.ExportLogDTO;
import com.platform.model.dto.ExportRequest;
import com.platform.model.dto.HelpRequestDTO;
import com.platform.model.dto.HelpResponseDTO;
import com.platform.model.dto.IdleItemDTO;
import com.platform.model.dto.IdleItemRequest;
import com.platform.model.dto.OperationLogDTO;
import com.platform.model.dto.OperationResultDTO;
import com.platform.model.dto.ProfileResponseDTO;
import com.platform.model.dto.RecordItemDTO;
import com.platform.model.dto.ResidentDTO;
import com.platform.model.dto.TenantDTO;
import com.platform.model.dto.UserDTO;
import com.platform.model.entity.BorrowRequest;
import com.platform.model.entity.Building;
import com.platform.model.entity.ExportLog;
import com.platform.model.entity.HelpApplication;
import com.platform.model.entity.HelpRequest;
import com.platform.model.entity.IdleItem;
import com.platform.model.entity.Notification;
import com.platform.model.entity.OperationLog;
import com.platform.model.entity.Rating;
import com.platform.model.entity.Room;
import com.platform.model.entity.Tenant;
import com.platform.model.entity.Unit;
import com.platform.model.entity.User;
import com.platform.repository.BorrowRequestRepository;
import com.platform.repository.BuildingRepository;
import com.platform.repository.ExportLogRepository;
import com.platform.repository.HelpApplicationRepository;
import com.platform.repository.HelpRequestRepository;
import com.platform.repository.IdleItemRepository;
import com.platform.repository.OperationLogRepository;
import com.platform.repository.RatingRepository;
import com.platform.repository.RoomRepository;
import com.platform.repository.TenantRepository;
import com.platform.repository.UnitRepository;
import com.platform.repository.UserRepository;
import com.platform.websocket.ChatWebSocketHandler;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;
import org.springframework.data.domain.Sort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.time.format.DateTimeFormatter;

@Service
@Transactional
public class AdminService {

    private static final Logger log = LoggerFactory.getLogger(AdminService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final IdleItemRepository idleItemRepository;
    private final HelpRequestRepository helpRequestRepository;
    private final BorrowRequestRepository borrowRequestRepository;
    private final HelpApplicationRepository helpApplicationRepository;
    private final UserRepository userRepository;
    private final OperationLogRepository operationLogRepository;
    private final NotificationService notificationService;
    private final TenantRepository tenantRepository;
    private final BuildingRepository buildingRepository;
    private final UnitRepository unitRepository;
    private final RoomRepository roomRepository;
    private final RatingRepository ratingRepository;
    private final ExportLogRepository exportLogRepository;
    private final UserActivityService userActivityService;
    private final PasswordEncoder passwordEncoder;
    private final ChatWebSocketHandler chatWebSocketHandler;

    public AdminService(IdleItemRepository idleItemRepository,
                        HelpRequestRepository helpRequestRepository,
                        BorrowRequestRepository borrowRequestRepository,
                        HelpApplicationRepository helpApplicationRepository,
                        UserRepository userRepository,
                        OperationLogRepository operationLogRepository,
                        NotificationService notificationService,
                        TenantRepository tenantRepository,
                        BuildingRepository buildingRepository,
                        UnitRepository unitRepository,
                        RoomRepository roomRepository,
                        RatingRepository ratingRepository,
                        ExportLogRepository exportLogRepository,
                        UserActivityService userActivityService,
                        PasswordEncoder passwordEncoder,
                        ChatWebSocketHandler chatWebSocketHandler) {
        this.idleItemRepository = idleItemRepository;
        this.helpRequestRepository = helpRequestRepository;
        this.borrowRequestRepository = borrowRequestRepository;
        this.helpApplicationRepository = helpApplicationRepository;
        this.userRepository = userRepository;
        this.operationLogRepository = operationLogRepository;
        this.notificationService = notificationService;
        this.tenantRepository = tenantRepository;
        this.buildingRepository = buildingRepository;
        this.unitRepository = unitRepository;
        this.roomRepository = roomRepository;
        this.ratingRepository = ratingRepository;
        this.exportLogRepository = exportLogRepository;
        this.userActivityService = userActivityService;
        this.passwordEncoder = passwordEncoder;
        this.chatWebSocketHandler = chatWebSocketHandler;
    }

    // ==================== 仪表盘 ====================

    /**
     * 获取管理员所属小区的运营看板数据：KPI（含较上月环比）、月度互助趋势（周/月/季）、
     * 本月互助完成率、损坏三态统计、互助对象排行。
     *
     * <p>沿用全表 findAll + 内存聚合模式（社区数据量小）；一次性加载各实体并建映射供全部指标复用，
     * 避免 N+1。租户隔离由 {@code tenantMatches} 保证，super_admin 仍被 {@code requireNotSuperAdmin} 拦截。</p>
     *
     * @param adminId 当前管理员用户 ID
     * @return 看板统计数据
     */
    public DashboardDTO getDashboard(Long adminId) {
        requireNotSuperAdmin(findAdmin(adminId));
        Long tenantId = getAdminTenantId(adminId);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime monthStart = now.withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime prevMonthStart = monthStart.minusMonths(1);
        LocalDateTime prevMonthEnd = monthStart.minusNanos(1);

        // 一次性加载本小区数据（findAll + 内存过滤），建映射供全部指标复用
        List<IdleItem> idleItems = idleItemRepository.findAll().stream()
                .filter(i -> tenantMatches(tenantId, i.getTenantId()))
                .collect(Collectors.toList());
        List<HelpRequest> helpRequests = helpRequestRepository.findAll().stream()
                .filter(h -> tenantMatches(tenantId, h.getTenantId()))
                .collect(Collectors.toList());
        Map<Long, IdleItem> idleMap = idleItems.stream()
                .collect(Collectors.toMap(IdleItem::getId, i -> i, (a, b) -> a));
        List<BorrowRequest> borrows = borrowRequestRepository.findAll().stream()
                .filter(b -> {
                    IdleItem item = idleMap.get(b.getIdleId());
                    return item != null && tenantMatches(tenantId, item.getTenantId());
                })
                .collect(Collectors.toList());
        Map<Long, HelpRequest> helpMap = helpRequests.stream()
                .collect(Collectors.toMap(HelpRequest::getId, h -> h, (a, b) -> a));
        List<HelpApplication> helpApps = helpApplicationRepository.findAll().stream()
                .filter(a -> {
                    HelpRequest hr = helpMap.get(a.getHelpId());
                    return hr != null && tenantMatches(tenantId, hr.getTenantId());
                })
                .collect(Collectors.toList());
        Set<Long> borrowIds = borrows.stream().map(BorrowRequest::getId).collect(Collectors.toSet());
        Set<Long> helpAppIds = helpApps.stream().map(HelpApplication::getId).collect(Collectors.toSet());
        List<Rating> ratings = ratingRepository.findAll().stream()
                .filter(r -> (r.getBorrowId() != null && borrowIds.contains(r.getBorrowId()))
                        || (r.getHelpApplicationId() != null && helpAppIds.contains(r.getHelpApplicationId())))
                .collect(Collectors.toList());
        Map<Long, User> userMap = userRepository.findAll().stream()
                .filter(u -> tenantMatches(tenantId, u.getTenantId()))
                .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));

        // KPI 四项：快照卡（在线闲置/求助）环比用发布增速，月度卡（本月发布/月活）用直接环比
        long onlineIdle = countLendOnline(idleItems);
        long onlineHelp = countHelpOnline(helpRequests);
        long monthPublish = countPublishes(idleItems, helpRequests, monthStart, now);
        long prevMonthPublish = countPublishes(idleItems, helpRequests, prevMonthStart, prevMonthEnd);
        long monthLendPublish = countLendPublishes(idleItems, monthStart, now);
        long prevMonthLendPublish = countLendPublishes(idleItems, prevMonthStart, prevMonthEnd);
        long monthHelpPublish = countHelpPublishes(helpRequests, monthStart, now);
        long prevMonthHelpPublish = countHelpPublishes(helpRequests, prevMonthStart, prevMonthEnd);
        long monthMau = countActiveUsers(userMap, idleItems, helpRequests, borrows, helpApps, ratings, monthStart, now);
        long prevMonthMau = countActiveUsers(userMap, idleItems, helpRequests, borrows, helpApps, ratings, prevMonthStart, prevMonthEnd);

        List<DashboardDTO.KpiStat> kpis = List.of(
                kpi("idle", onlineIdle, momChange(monthLendPublish, prevMonthLendPublish)),
                kpi("help", onlineHelp, momChange(monthHelpPublish, prevMonthHelpPublish)),
                kpi("pub", monthPublish, momChange(monthPublish, prevMonthPublish)),
                kpi("mau", monthMau, momChange(monthMau, prevMonthMau)));

        // 月度互助趋势（周/月/季三段）
        DashboardDTO.Trends trends = DashboardDTO.Trends.builder()
                .week(buildWeekTrend(now, idleItems, helpRequests, borrows, helpApps))
                .month(buildMonthTrend(now, idleItems, helpRequests, borrows, helpApps))
                .quarter(buildQuarterTrend(now, idleItems, helpRequests, borrows, helpApps))
                .build();

        // 本月互助完成率：已互助（完成借用+完成帮助）/ 直接下架（本月发布且 offline）
        long completed = countCompleted(borrows, helpApps, monthStart, now);
        long removed = countRemoved(idleItems, helpRequests, monthStart, now);
        double rate = completed + removed == 0 ? 0
                : Math.round(completed * 1000.0 / (completed + removed)) / 10.0;

        return DashboardDTO.builder()
                .kpis(kpis)
                .trends(trends)
                .completion(DashboardDTO.CompletionStat.builder()
                        .completed(completed).removed(removed).rate(rate).build())
                .damage(buildDamageStat(borrows))
                .ranking(buildRanking(borrows, helpApps, userMap))
                .build();
    }

    /**
     * 判断日期是否在 [start, end] 闭区间内（含 null 安全）。
     */
    private boolean isWithinMonth(LocalDateTime dt, LocalDateTime start, LocalDateTime end) {
        return dt != null && !dt.isBefore(start) && !dt.isAfter(end);
    }

    /** KPI 单项构造。 */
    private DashboardDTO.KpiStat kpi(String key, long value, double momChange) {
        return DashboardDTO.KpiStat.builder().key(key).value(value).momChange(momChange).build();
    }

    /**
     * 环比百分比（本月 vs 上月）：(current - previous) / previous * 100，保留 1 位小数；分母为 0 返回 0。
     */
    private double momChange(long current, long previous) {
        if (previous == 0) {
            return 0;
        }
        return Math.round((current - previous) * 1000.0 / previous) / 10.0;
    }

    /** 在线闲置数：postType=LEND 且 status=online（不含 WANTED）。 */
    private long countLendOnline(List<IdleItem> idleItems) {
        return idleItems.stream()
                .filter(i -> PostType.LEND.equals(i.getPostType()) && BizStatus.ONLINE.equals(i.getStatus()))
                .count();
    }

    /** 在线技能求助数：status=online。 */
    private long countHelpOnline(List<HelpRequest> helpRequests) {
        return helpRequests.stream().filter(h -> BizStatus.ONLINE.equals(h.getStatus())).count();
    }

    /** 时间窗口内闲置+求助发布总数。 */
    private long countPublishes(List<IdleItem> idleItems, List<HelpRequest> helpRequests,
            LocalDateTime start, LocalDateTime end) {
        long idle = idleItems.stream().filter(i -> isWithinMonth(i.getCreatedAt(), start, end)).count();
        long help = helpRequests.stream().filter(h -> isWithinMonth(h.getCreatedAt(), start, end)).count();
        return idle + help;
    }

    /** 时间窗口内 LEND 发布数（在线闲置卡环比用）。 */
    private long countLendPublishes(List<IdleItem> idleItems, LocalDateTime start, LocalDateTime end) {
        return idleItems.stream()
                .filter(i -> PostType.LEND.equals(i.getPostType())
                        && isWithinMonth(i.getCreatedAt(), start, end))
                .count();
    }

    /** 时间窗口内求助发布数（在线求助卡环比用）。 */
    private long countHelpPublishes(List<HelpRequest> helpRequests, LocalDateTime start, LocalDateTime end) {
        return helpRequests.stream().filter(h -> isWithinMonth(h.getCreatedAt(), start, end)).count();
    }

    /**
     * 时间窗口内活跃住户数：本月有行为的去重住户。
     *
     * <p>行为来源 union：发布闲置/求助的 userId、借入方 borrowerId、帮助接单 helperId、
     * 评价双方 from/toUserId；仅保留小区内（userMap 存在）且非管理员身份的住户。</p>
     */
    private long countActiveUsers(Map<Long, User> userMap, List<IdleItem> idleItems,
            List<HelpRequest> helpRequests, List<BorrowRequest> borrows,
            List<HelpApplication> helpApps, List<Rating> ratings,
            LocalDateTime start, LocalDateTime end) {
        Set<Long> active = new HashSet<>();
        idleItems.stream().filter(i -> isWithinMonth(i.getCreatedAt(), start, end))
                .map(IdleItem::getUserId).forEach(active::add);
        helpRequests.stream().filter(h -> isWithinMonth(h.getCreatedAt(), start, end))
                .map(HelpRequest::getUserId).forEach(active::add);
        borrows.stream().filter(b -> isWithinMonth(b.getCreatedAt(), start, end))
                .map(BorrowRequest::getBorrowerId).forEach(active::add);
        helpApps.stream().filter(a -> isWithinMonth(a.getCreatedAt(), start, end))
                .map(HelpApplication::getHelperId).forEach(active::add);
        ratings.stream().filter(r -> isWithinMonth(r.getCreatedAt(), start, end)).forEach(r -> {
            if (r.getFromUserId() != null) {
                active.add(r.getFromUserId());
            }
            if (r.getToUserId() != null) {
                active.add(r.getToUserId());
            }
        });
        // 仅保留小区内住户（排除管理员身份）
        return active.stream()
                .filter(userMap::containsKey)
                .filter(id -> !ADMIN_USER_TYPES.contains(userMap.get(id).getUserType()))
                .count();
    }

    /** 近 7 天按天趋势（末位「今日」）。 */
    private DashboardDTO.TrendData buildWeekTrend(LocalDateTime now, List<IdleItem> idleItems,
            List<HelpRequest> helpRequests, List<BorrowRequest> borrows, List<HelpApplication> helpApps) {
        LocalDate today = now.toLocalDate();
        List<String> labels = new ArrayList<>();
        List<Long> publish = new ArrayList<>();
        List<Long> completed = new ArrayList<>();
        for (int i = -6; i <= 0; i++) {
            LocalDate day = today.plusDays(i);
            labels.add(i == 0 ? "今日" : day.format(DateTimeFormatter.ofPattern("M/d")));
            publish.add(countPublishOn(day, idleItems, helpRequests));
            completed.add(countCompletedOn(day, borrows, helpApps));
        }
        return DashboardDTO.TrendData.builder().labels(labels).publish(publish).completed(completed).build();
    }

    /** 本月按周分桶（每 7 天一桶，label 第N周）。 */
    private DashboardDTO.TrendData buildMonthTrend(LocalDateTime now, List<IdleItem> idleItems,
            List<HelpRequest> helpRequests, List<BorrowRequest> borrows, List<HelpApplication> helpApps) {
        LocalDate monthStartDate = now.toLocalDate().withDayOfMonth(1);
        int buckets = (monthStartDate.lengthOfMonth() + 6) / 7;
        List<String> labels = new ArrayList<>();
        List<Long> publish = new ArrayList<>();
        List<Long> completed = new ArrayList<>();
        for (int b = 0; b < buckets; b++) {
            LocalDate start = monthStartDate.plusDays(b * 7L);
            LocalDate end = start.plusDays(6L);
            labels.add("第" + (b + 1) + "周");
            publish.add(countPublishBetween(start, end, idleItems, helpRequests));
            completed.add(countCompletedBetween(start, end, borrows, helpApps));
        }
        return DashboardDTO.TrendData.builder().labels(labels).publish(publish).completed(completed).build();
    }

    /** 本季度按月趋势（3 个月，label N月）。 */
    private DashboardDTO.TrendData buildQuarterTrend(LocalDateTime now, List<IdleItem> idleItems,
            List<HelpRequest> helpRequests, List<BorrowRequest> borrows, List<HelpApplication> helpApps) {
        int quarterStartMonth = ((now.getMonthValue() - 1) / 3) * 3 + 1;
        int year = now.getYear();
        List<String> labels = new ArrayList<>();
        List<Long> publish = new ArrayList<>();
        List<Long> completed = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            int month = quarterStartMonth + i;
            int monthYear = year;
            if (month > 12) {
                month -= 12;
                monthYear += 1;
            }
            labels.add(month + "月");
            publish.add(countPublishInMonth(monthYear, month, idleItems, helpRequests));
            completed.add(countCompletedInMonth(monthYear, month, borrows, helpApps));
        }
        return DashboardDTO.TrendData.builder().labels(labels).publish(publish).completed(completed).build();
    }

    /** 某天发布数（闲置+求助）。 */
    private long countPublishOn(LocalDate day, List<IdleItem> idleItems, List<HelpRequest> helpRequests) {
        long idle = idleItems.stream().filter(i -> sameDay(i.getCreatedAt(), day)).count();
        long help = helpRequests.stream().filter(h -> sameDay(h.getCreatedAt(), day)).count();
        return idle + help;
    }

    /** 某天完成互助数（归还完成借用 + 完成帮助）。 */
    private long countCompletedOn(LocalDate day, List<BorrowRequest> borrows, List<HelpApplication> helpApps) {
        long b = borrows.stream().filter(x -> sameDay(x.getReturnedAt(), day)).count();
        long a = helpApps.stream().filter(x -> sameDay(x.getCompletedAt(), day)).count();
        return b + a;
    }

    /** [start, end] 闭区间内发布数。 */
    private long countPublishBetween(LocalDate start, LocalDate end,
            List<IdleItem> idleItems, List<HelpRequest> helpRequests) {
        long idle = idleItems.stream().filter(i -> betweenDay(i.getCreatedAt(), start, end)).count();
        long help = helpRequests.stream().filter(h -> betweenDay(h.getCreatedAt(), start, end)).count();
        return idle + help;
    }

    /** [start, end] 闭区间内完成互助数。 */
    private long countCompletedBetween(LocalDate start, LocalDate end,
            List<BorrowRequest> borrows, List<HelpApplication> helpApps) {
        long b = borrows.stream().filter(x -> betweenDay(x.getReturnedAt(), start, end)).count();
        long a = helpApps.stream().filter(x -> betweenDay(x.getCompletedAt(), start, end)).count();
        return b + a;
    }

    /** 指定年/月内发布数。 */
    private long countPublishInMonth(int year, int month,
            List<IdleItem> idleItems, List<HelpRequest> helpRequests) {
        long idle = idleItems.stream().filter(i -> inMonth(i.getCreatedAt(), year, month)).count();
        long help = helpRequests.stream().filter(h -> inMonth(h.getCreatedAt(), year, month)).count();
        return idle + help;
    }

    /** 指定年/月内完成互助数。 */
    private long countCompletedInMonth(int year, int month,
            List<BorrowRequest> borrows, List<HelpApplication> helpApps) {
        long b = borrows.stream().filter(x -> inMonth(x.getReturnedAt(), year, month)).count();
        long a = helpApps.stream().filter(x -> inMonth(x.getCompletedAt(), year, month)).count();
        return b + a;
    }

    /** 日期是否为指定日（null 安全）。 */
    private boolean sameDay(LocalDateTime dt, LocalDate day) {
        return dt != null && dt.toLocalDate().equals(day);
    }

    /** 日期是否在 [start, end] 闭区间（null 安全）。 */
    private boolean betweenDay(LocalDateTime dt, LocalDate start, LocalDate end) {
        if (dt == null) {
            return false;
        }
        LocalDate d = dt.toLocalDate();
        return !d.isBefore(start) && !d.isAfter(end);
    }

    /** 日期是否在指定年/月（null 安全）。 */
    private boolean inMonth(LocalDateTime dt, int year, int month) {
        return dt != null && dt.getYear() == year && dt.getMonthValue() == month;
    }

    /** 本月完成互助数：归还完成借用（status=returned）+ 完成帮助（status=completed）。 */
    private long countCompleted(List<BorrowRequest> borrows, List<HelpApplication> helpApps,
            LocalDateTime start, LocalDateTime end) {
        long b = borrows.stream().filter(x -> BizStatus.RETURNED.equals(x.getStatus())
                && isWithinMonth(x.getReturnedAt(), start, end)).count();
        long a = helpApps.stream().filter(x -> BizStatus.COMPLETED.equals(x.getStatus())
                && isWithinMonth(x.getCompletedAt(), start, end)).count();
        return b + a;
    }

    /** 本月直接下架数：本月发布且状态 offline 的闲置+求助。 */
    private long countRemoved(List<IdleItem> idleItems, List<HelpRequest> helpRequests,
            LocalDateTime start, LocalDateTime end) {
        long idle = idleItems.stream().filter(i -> BizStatus.OFFLINE.equals(i.getStatus())
                && isWithinMonth(i.getCreatedAt(), start, end)).count();
        long help = helpRequests.stream().filter(h -> BizStatus.OFFLINE.equals(h.getStatus())
                && isWithinMonth(h.getCreatedAt(), start, end)).count();
        return idle + help;
    }

    /** 损坏三态统计：按借用记录 damageType 分布（null 不计）。 */
    private DashboardDTO.DamageStat buildDamageStat(List<BorrowRequest> borrows) {
        long normal = 0;
        long severe = 0;
        long broken = 0;
        for (BorrowRequest b : borrows) {
            if (DamageType.NORMAL.equals(b.getDamageType())) {
                normal++;
            } else if (DamageType.ABNORMAL.equals(b.getDamageType())) {
                severe++;
            } else if (DamageType.BROKEN.equals(b.getDamageType())) {
                broken++;
            }
        }
        return DashboardDTO.DamageStat.builder().normal(normal).severe(severe).broken(broken).build();
    }

    /**
     * 互助对象排行：按住户聚合互助总次数（闲置借入 + 技能接单完成合并），同一住户只出现一条。
     * 展示名复用 {@link UserFormatter#formatRoomWithType}；按次数降序返回全量。
     */
    private List<DashboardDTO.RankingItem> buildRanking(List<BorrowRequest> borrows,
            List<HelpApplication> helpApps, Map<Long, User> userMap) {
        Map<Long, Long> merged = new HashMap<>();
        // 闲置借入：借入方借用次数（全部状态）
        borrows.stream().collect(Collectors.groupingBy(BorrowRequest::getBorrowerId, Collectors.counting()))
                .forEach((uid, count) -> merged.merge(uid, count, Long::sum));
        // 技能接单：帮助接单完成次数（COMPLETED）
        helpApps.stream()
                .filter(a -> BizStatus.COMPLETED.equals(a.getStatus()))
                .collect(Collectors.groupingBy(HelpApplication::getHelperId, Collectors.counting()))
                .forEach((uid, count) -> merged.merge(uid, count, Long::sum));
        List<DashboardDTO.RankingItem> items = new ArrayList<>();
        merged.forEach((uid, count) -> {
            DashboardDTO.RankingItem item = toRankingItem(uid, count, userMap);
            if (item != null) {
                items.add(item);
            }
        });
        // 次数降序，并列按展示名升序
        items.sort(Comparator.comparingLong(DashboardDTO.RankingItem::getCount).reversed()
                .thenComparing(DashboardDTO.RankingItem::getName, Comparator.nullsLast(String::compareTo)));
        return items;
    }

    /** 拼排行单项：用户不在本小区映射中则返回 null（调用方跳过）。 */
    private DashboardDTO.RankingItem toRankingItem(Long userId, long count, Map<Long, User> userMap) {
        User user = userMap.get(userId);
        if (user == null) {
            return null;
        }
        return DashboardDTO.RankingItem.builder()
                .name(UserFormatter.formatRoomWithType(user))
                .count(count)
                .build();
    }

    // ==================== 审核管理 ====================

    private static final List<String> ADMIN_USER_TYPES = Arrays.asList(UserType.ADMIN, UserType.SENIOR_ADMIN, UserType.SUPER_ADMIN);

    /**
     * 按认证状态筛选获取审核列表，一次性返回全部数据（按创建时间倒序）。
     *
     * @param adminId 当前管理员 ID
     * @param status  "pending" / "approved" / "rejected" / null（全部非 registering 状态）
     * @return 全部审核用户 DTO 列表
     */
    public List<UserDTO> getAudits(Long adminId, String status) {
        requireNotSuperAdmin(findAdmin(adminId));
        Long tenantId = getAdminTenantId(adminId);
        List<User> users;

        if (status != null && !status.isEmpty()) {
            if (BizStatus.APPROVED.equals(status)) {
                // "全部住户"页签：仅显示真实住户，排除管理员账号
                users = tenantId != null
                        ? userRepository.findByTenantIdAndAuthStatusAndUserTypeNotIn(tenantId, BizStatus.APPROVED, ADMIN_USER_TYPES)
                        : userRepository.findByAuthStatusAndUserTypeNotIn(BizStatus.APPROVED, ADMIN_USER_TYPES);
            } else {
                users = tenantId != null
                        ? userRepository.findByTenantIdAndAuthStatus(tenantId, status)
                        : userRepository.findByAuthStatus(status);
            }
        } else {
            // "all"页签：排除仍处于 "registering" 状态（尚未完成注册）的用户
            users = tenantId != null
                    ? userRepository.findByTenantIdAndAuthStatusNot(tenantId, BizStatus.REGISTERING)
                    : userRepository.findByAuthStatusNot(BizStatus.REGISTERING);
        }

        // 保持与分页版一致的按创建时间倒序（无分页查询不保证顺序）
        users.sort(Comparator.comparing(User::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())));

        List<UserDTO> dtos = users.stream()
                .map(this::toUserDTO)
                .collect(Collectors.toList());

        // 批量填充审核人信息：查询 OperationLog 获取每个用户的审核操作人
        List<Long> userIds = dtos.stream().map(UserDTO::getId).collect(Collectors.toList());
        if (!userIds.isEmpty()) {
            Map<Long, String> auditorMap = operationLogRepository.findApprovalsByUserIds(userIds).stream()
                    .collect(Collectors.toMap(
                            OperationLog::getTargetId,
                            ol -> ol.getAdmin() != null ? ol.getAdmin().getName() : "",
                            (existing, replacement) -> replacement  // 多条日志时保留最新（DESC排序保证第一条最新）
                    ));
            dtos.forEach(dto -> dto.setAuditorName(auditorMap.getOrDefault(dto.getId(), "")));
        }

        return dtos;
    }

    /**
     * 获取各审核页签的数量统计。
     */
    public AuditCountDTO getAuditCounts(Long adminId) {
        requireNotSuperAdmin(findAdmin(adminId));
        Long tenantId = getAdminTenantId(adminId);
        long pending = tenantId != null
                ? userRepository.countByTenantIdAndAuthStatus(tenantId, BizStatus.PENDING)
                : userRepository.countByAuthStatus(BizStatus.PENDING);
        long approved = tenantId != null
                ? userRepository.countByTenantIdAndAuthStatusAndUserTypeNotIn(tenantId, BizStatus.APPROVED, ADMIN_USER_TYPES)
                : userRepository.countByAuthStatusAndUserTypeNotIn(BizStatus.APPROVED, ADMIN_USER_TYPES);
        long rejected = tenantId != null
                ? userRepository.countByTenantIdAndAuthStatus(tenantId, BizStatus.REJECTED)
                : userRepository.countByAuthStatus(BizStatus.REJECTED);
        long all = tenantId != null
                ? userRepository.countByTenantIdAndAuthStatusNot(tenantId, BizStatus.REGISTERING)
                : userRepository.countByAuthStatusNot(BizStatus.REGISTERING);
        return AuditCountDTO.builder()
                .pending(pending)
                .approved(approved)
                .rejected(rejected)
                .all(all)
                .build();
    }

    public Map<String, Object> auditUser(Long adminId, Long userId, AuditRequest req) {
        requireNotSuperAdmin(findAdmin(adminId));
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BizException("用户不存在"));

        user.setAuthStatus(req.getApproved() ? BizStatus.APPROVED : BizStatus.REJECTED);
        if (req.getApproved()) {
            user.setRejectReason(null);
        } else {
            user.setRejectReason(req.getReason());
            // 拒绝即踢：版本+1 使其现有 token 立即失效，并断开其在线 WS 连接（通过分支不打断已登录用户）
            user.setTokenVersion((user.getTokenVersion() == null ? 0 : user.getTokenVersion()) + 1);
        }
        userRepository.save(user);
        if (!req.getApproved()) {
            chatWebSocketHandler.closeUserSessions(user.getId().toString());
        }

        String title = req.getApproved() ? "实名认证已通过" : "实名认证被拒绝";
        String content = req.getApproved()
                ? "您的实名认证申请已通过审核"
                : "您的实名认证申请被拒绝"
                        + (req.getReason() != null ? "，原因：" + req.getReason() : "");
        createNotification(userId, "audit_result", title, content, null);

        OperationLog log = new OperationLog();
        log.setAdminId(adminId);
        log.setTenantId(getAdminTenantId(adminId));
        log.setAction(req.getApproved() ? "approve_user" : "reject_user");
        log.setTargetType("user");
        log.setTargetId(userId);
        log.setDetail(req.getReason());
        log.setCreatedAt(LocalDateTime.now());
        operationLogRepository.save(log);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", req.getApproved() ? "审核通过" : "已拒绝");
        return result;
    }

    // ==================== 内容管理 ====================

    /**
     * 获取内容列表，支持按状态页签、类型、楼栋、单元、关键词筛选，一次性返回全部数据。
     *
     * @param adminId           管理员 ID
     * @param statusTab         状态页签（normal/offline/yellow 等）
     * @param type              内容类型（idle/help）
     * @param buildingNo        楼栋号筛选（数值，可为 null）
     * @param unitNo            单元号筛选（数值，可为 null）
     * @param search            关键词
     * @param moderationStatus  审核状态
     * @param moderatedBy       审核员
     * @return 全部内容列表（按 createdAt 降序）
     */
    public List<ContentItemDTO> getContentList(Long adminId, String statusTab, String type, Integer buildingNo,
                                               Integer unitNo, String search,
                                               String moderationStatus, String moderatedBy) {
        requireNotSuperAdmin(findAdmin(adminId));
        Long tenantId = getAdminTenantId(adminId);

        // 解析楼栋筛选条件（无匹配用户时直接返回空列表）
        List<Long> buildingUserIds = resolveBuildingFilter(buildingNo, unitNo);
        if (buildingUserIds != null && buildingUserIds.isEmpty()) {
            return List.of();
        }

        // 将状态页签映射为数据库状态值列表
        String effectiveTab = (statusTab != null && !statusTab.isEmpty()) ? statusTab : "all";

        // 发布审核 tab：按 moderation 字段筛选
        if ("moderation".equals(effectiveTab)) {
            List<ContentItemDTO> allItems = new ArrayList<>();
            if (type == null || "idle".equals(type)) {
                fetchModerationIdleItems(tenantId, buildingUserIds, search, moderationStatus, moderatedBy)
                        .forEach(item -> allItems.add(toContentItemDTO(item)));
            }
            if (type == null || "help".equals(type)) {
                fetchModerationHelpItems(tenantId, buildingUserIds, search, moderationStatus, moderatedBy)
                        .forEach(item -> allItems.add(toContentItemDTO(item)));
            }
            // 审核 tab 按 updatedAt 倒序
            allItems.sort((a, b) -> {
                LocalDateTime ta = a.getUpdatedAt();
                LocalDateTime tb = b.getUpdatedAt();
                if (ta == null && tb == null) return 0;
                if (ta == null) return 1;
                if (tb == null) return -1;
                return tb.compareTo(ta);
            });
            return allItems;
        }

        // 待审批 tab 特殊处理：数据来源为 borrow_requests / help_applications（pending 状态），
        // 而非 idle_items / help_requests 表，与 C端「管理页→审批」数据源一致
        if ("pending".equals(effectiveTab)) {
            List<ContentItemDTO> allItems = new ArrayList<>();
            if (type == null || "idle".equals(type)) {
                allItems.addAll(fetchPendingBorrowItems(tenantId, buildingUserIds, search));
            }
            if (type == null || "help".equals(type)) {
                allItems.addAll(fetchPendingHelpItems(tenantId, buildingUserIds, search));
            }
            sortContentByCreatedAtDesc(allItems);
            return allItems;
        }

        List<String> idleStatuses = mapStatusTabToIdleStatuses(effectiveTab);
        List<String> helpStatuses = mapStatusTabToHelpStatuses(effectiveTab);

        // 查询并转换为 DTO
        List<ContentItemDTO> allItems = new ArrayList<>();
        if (type == null || "idle".equals(type)) {
            fetchIdleItems(idleStatuses, buildingUserIds, tenantId, search)
                    .forEach(item -> allItems.add(toContentItemDTO(item)));
        }
        if (type == null || "help".equals(type)) {
            fetchHelpItems(helpStatuses, buildingUserIds, tenantId, search)
                    .forEach(item -> allItems.add(toContentItemDTO(item)));
        }

        // 违规下架tab — 按审核员筛选
        List<ContentItemDTO> filtered = allItems;
        if (moderatedBy != null && !moderatedBy.isEmpty()) {
            boolean isAi = "ai".equals(moderatedBy);
            filtered = allItems.stream()
                    .filter(dto -> isAi ? dto.getReviewedByName() == null : dto.getReviewedByName() != null)
                    .collect(Collectors.toList());
        }

        // 排序后返回全量
        sortContentByCreatedAtDesc(filtered);
        return filtered;
    }

    /**
     * 解析楼栋筛选条件：无筛选条件返回 null，有筛选但无匹配用户返回空列表。
     *
     * @return null = 不需要筛选；空列表 = 筛选条件无匹配（应返回空结果）；非空列表 = 匹配的用户 ID
     */
    private List<Long> resolveBuildingFilter(Integer buildingNo, Integer unitNo) {
        if (buildingNo == null) {
            return null;
        }
        return resolveBuildingUserIds(buildingNo, unitNo);
    }

    /**
     * 按 createdAt 降序排列 ContentItemDTO 列表（null 值排最后）。
     */
    private void sortContentByCreatedAtDesc(List<ContentItemDTO> items) {
        items.sort((a, b) -> {
            LocalDateTime ta = a.getCreatedAt();
            LocalDateTime tb = b.getCreatedAt();
            if (ta == null && tb == null) return 0;
            if (ta == null) return 1;
            if (tb == null) return -1;
            return tb.compareTo(ta);
        });
    }

    /**
     * 获取内容详情，包含对方信息、评价和违规信息。
     *
     * @param id   内容 ID
     * @param type "idle" 或 "help"
     * @return 内容详情 DTO
     */
    public ContentItemDTO getContentDetail(Long adminId, Long id, String type) {
        requireNotSuperAdmin(findAdmin(adminId));
        Long tenantId = getAdminTenantId(adminId);
        if ("idle".equals(type)) {
            IdleItem item = idleItemRepository.findById(id)
                    .orElseThrow(() -> new BizException("物品不存在"));
            if (!tenantMatches(tenantId, item.getTenantId())) {
                throw new BizException("无权查看该物品");
            }
            return toContentItemDTO(item);
        } else if ("help".equals(type)) {
            HelpRequest item = helpRequestRepository.findById(id)
                    .orElseThrow(() -> new BizException("求助不存在"));
            if (!tenantMatches(tenantId, item.getTenantId())) {
                throw new BizException("无权查看该求助");
            }
            return toContentItemDTO(item);
        } else {
            throw new BizException("不支持的类型，请使用 idle 或 help");
        }
    }

    /**
     * 获取各内容状态页签的数量统计。
     *
     * @return 页签名到数量的映射
     */
    public ContentCountDTO getContentCounts(Long adminId) {
        requireNotSuperAdmin(findAdmin(adminId));
        Long tenantId = getAdminTenantId(adminId);

        long idleShowing = tenantId != null
                ? idleItemRepository.countByTenantIdAndStatus(tenantId, BizStatus.ONLINE)
                : idleItemRepository.countByStatus(BizStatus.ONLINE);
        long helpShowing = tenantId != null
                ? helpRequestRepository.countByTenantIdAndStatus(tenantId, BizStatus.ONLINE)
                : helpRequestRepository.countByStatus(BizStatus.ONLINE);

        long borrowPending = tenantId != null
                ? borrowRequestRepository.countByStatusAndTenantId(BizStatus.PENDING, tenantId)
                : borrowRequestRepository.countByStatus(BizStatus.PENDING);
        long helpAppPending = tenantId != null
                ? helpApplicationRepository.countByStatusAndTenantId(BizStatus.PENDING, tenantId)
                : helpApplicationRepository.countByStatus(BizStatus.PENDING);

        long idleProgressing = tenantId != null
                ? idleItemRepository.countByTenantIdAndStatus(tenantId, BizStatus.ACTIVE)
                : idleItemRepository.countByStatus(BizStatus.ACTIVE);
        long helpProgressing = tenantId != null
                ? helpRequestRepository.countByTenantIdAndStatus(tenantId, BizStatus.ACTIVE)
                : helpRequestRepository.countByStatus(BizStatus.ACTIVE);

        long idleCompleted = tenantId != null
                ? idleItemRepository.countByTenantIdAndStatus(tenantId, BizStatus.COMPLETED)
                : idleItemRepository.countByStatus(BizStatus.COMPLETED);
        long helpCompleted = tenantId != null
                ? helpRequestRepository.countByTenantIdAndStatus(tenantId, BizStatus.COMPLETED)
                : helpRequestRepository.countByStatus(BizStatus.COMPLETED);

        long idleViolation = tenantId != null
                ? idleItemRepository.countByTenantIdAndStatus(tenantId, BizStatus.OFFLINE)
                : idleItemRepository.countByStatus(BizStatus.OFFLINE);
        long helpViolation = tenantId != null
                ? helpRequestRepository.countByTenantIdAndStatus(tenantId, BizStatus.OFFLINE)
                : helpRequestRepository.countByStatus(BizStatus.OFFLINE);

        long idleAll = idleShowing + idleProgressing + idleCompleted + idleViolation;
        long helpAll = helpShowing + helpProgressing + helpCompleted + helpViolation;

        return ContentCountDTO.builder()
                .showing(idleShowing + helpShowing)
                .pending(borrowPending + helpAppPending)
                .progressing(idleProgressing + helpProgressing)
                .completed(idleCompleted + helpCompleted)
                .violation(idleViolation + helpViolation)
                .all(idleAll + helpAll + borrowPending + helpAppPending)
                .build();
    }

    /**
     * 下架（删除）内容并记录违规信息。
     *
     * @param adminId   执行操作的管理员
     * @param contentId 要删除的内容 ID
     * @param req       下架请求，包含类型、原因列表和自定义原因
     * @return 结果 map
     */
    /**
     * 下架（删除）内容并记录违规信息。
     */
    public OperationResultDTO removeContent(Long adminId, Long contentId, ContentOfflineRequest req) {
        requireNotSuperAdmin(findAdmin(adminId));
        String delistReason = buildDelistReason(req);

        switch (req.getTargetType()) {
            case "idle" -> removeIdleContent(contentId, adminId, delistReason, req.isFromModeration(), req.getUpdatedAt());
            case "help" -> removeHelpContent(contentId, adminId, delistReason, req.isFromModeration(), req.getUpdatedAt());
            default -> throw new BizException("不支持的目标类型");
        }

        return OperationResultDTO.builder()
                .success(true)
                .message("内容已删除")
                .build();
    }

    /**
     * AI 内容审核通过 — 管理员人工确认帖子合规，将其上线。
     */
    @Transactional
    public void approveContent(Long adminId, Long contentId, String type, String updatedAt) {
        requireNotSuperAdmin(findAdmin(adminId));
        switch (type) {
            case "idle" -> {
                IdleItem item = idleItemRepository.findById(contentId)
                        .orElseThrow(() -> new BizException("物品不存在"));
                checkVersionConflict(item.getUpdatedAt(), updatedAt);
                if (ModerationStatus.RED.equals(item.getModerationStatus()) ||
                    (ModerationStatus.REVIEWED.equals(item.getModerationStatus()) && BizStatus.OFFLINE.equals(item.getStatus()))) {
                    throw new BizException("该内容已被驳回，无法重新上线");
                }
                item.setStatus(BizStatus.ONLINE);
                item.setDelistReason(null);
                item.setModerationStatus(ModerationStatus.REVIEWED);
                item.setReviewedBy(adminId);
                idleItemRepository.save(item);
                notificationService.create(item.getUserId(), NotificationType.CONTENT_APPROVED,
                        "发布内容已通过审核",
                        "您发布的「" + item.getTitle() + "」经审核已通过，现已上线",
                        item.getId());
            }
            case "help" -> {
                HelpRequest hr = helpRequestRepository.findById(contentId)
                        .orElseThrow(() -> new BizException("求助不存在"));
                checkVersionConflict(hr.getUpdatedAt(), updatedAt);
                if (ModerationStatus.RED.equals(hr.getModerationStatus()) ||
                    (ModerationStatus.REVIEWED.equals(hr.getModerationStatus()) && BizStatus.OFFLINE.equals(hr.getStatus()))) {
                    throw new BizException("该内容已被驳回，无法重新上线");
                }
                hr.setStatus(BizStatus.ONLINE);
                hr.setDelistReason(null);
                hr.setModerationStatus(ModerationStatus.REVIEWED);
                hr.setReviewedBy(adminId);
                helpRequestRepository.save(hr);
                notificationService.create(hr.getUserId(), NotificationType.CONTENT_APPROVED,
                        "发布内容已通过审核",
                        "您发布的「" + hr.getTitle() + "」经审核已通过，现已上线",
                        hr.getId());
            }
            default -> throw new BizException("不支持的类型");
        }
    }

    /**
     * 从下架请求中构建下架原因字符串。
     */
    private String buildDelistReason(ContentOfflineRequest req) {
        if (req.getCustomReason() != null && !req.getCustomReason().isBlank()) {
            return req.getCustomReason();
        }
        if (req.getReasons() != null && !req.getReasons().isEmpty()) {
            return String.join("，", req.getReasons());
        }
        return "违规";
    }

    /**
     * 乐观锁版本检查：若前端传了 updatedAt，与 DB 当前值对比（均截断到秒），不一致则抛出冲突异常。
     * @param dbUpdatedAt 数据库实体的更新时间
     * @param clientUpdatedAt 前端传来的更新时间（ISO 字符串，含纳秒精度）
     * @throws VersionConflictException 数据已被他人修改
     */
    private void checkVersionConflict(java.time.LocalDateTime dbUpdatedAt, String clientUpdatedAt) {
        if (clientUpdatedAt == null || clientUpdatedAt.isBlank()) return;
        // 两端均截断到秒级比较：避免 DB（LocalDateTime）与 Jackson 序列化精度不一致导致误判
        String dbStr = dbUpdatedAt != null ? dbUpdatedAt.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString() : "";
        String clientStr = clientUpdatedAt.trim();
        // 截掉客户端时间的小数秒部分（如 .123456 → ""）
        int dotIdx = clientStr.indexOf('.');
        if (dotIdx > 0) clientStr = clientStr.substring(0, dotIdx);
        if (!dbStr.equals(clientStr)) {
            throw new VersionConflictException("数据已被其他管理员修改，请刷新后重试");
        }
    }

    /**
     * 下架闲置物品：标记违规状态、发送通知、记录操作日志，
     * 并自动拒绝该物品下所有待审批的借入申请，确保待审批列表同步清空。
     */
    private void removeIdleContent(Long contentId, Long adminId, String delistReason, boolean fromModeration, String updatedAt) {
        IdleItem item = idleItemRepository.findById(contentId)
                .orElseThrow(() -> new BizException("物品不存在"));
        checkVersionConflict(item.getUpdatedAt(), updatedAt);
        item.setStatus(BizStatus.OFFLINE);
        item.setDelistReason(delistReason);
        // 审核 tab 驳回：设为 ModerationStatus.REVIEWED，红牌筛选 (REVIEWED + offline) 可匹配；其他 tab 下架：清空审核状态，不参与 moderation 筛选
        item.setModerationStatus(fromModeration ? ModerationStatus.REVIEWED : null);
        item.setReviewedBy(adminId);
        idleItemRepository.save(item);

        // 拒绝该物品下所有待审批的借入申请，使其从待审批列表中消失
        List<BorrowRequest> pendingBorrows = borrowRequestRepository.findByIdleId(contentId).stream()
                .filter(br -> BizStatus.PENDING.equals(br.getStatus()))
                .collect(Collectors.toList());
        for (BorrowRequest br : pendingBorrows) {
            br.setStatus(BizStatus.REJECTED);
            borrowRequestRepository.save(br);
            createNotification(br.getBorrowerId(), "audit_result",
                    "借入申请已拒绝",
                    "您的借入申请「" + item.getTitle() + "」因物品被下架而自动拒绝，原因：" + delistReason,
                    br.getId());
        }

        // 强制结束该物品下所有进行中的借用，使其从进行中列表中消失
        List<BorrowRequest> activeBorrows = borrowRequestRepository.findByIdleId(contentId).stream()
                .filter(br -> BizStatus.APPROVED.equals(br.getStatus()))
                .collect(Collectors.toList());
        for (BorrowRequest br : activeBorrows) {
            br.setStatus(BizStatus.RETURNED);
            br.setReturnedAt(LocalDateTime.now());
            borrowRequestRepository.save(br);
            createNotification(br.getBorrowerId(), "violation",
                    "物品已被下架",
                    "您借用的「" + item.getTitle() + "」已被管理员下架，借用已强制结束",
                    br.getId());
        }

        createNotification(item.getUserId(), NotificationType.CONTENT_REJECTED,
                "发布内容未通过审核", "您发布的「" + item.getTitle() + "」因" + delistReason + "未通过审核",
                item.getId());

        saveOperationLog(adminId, item.getTenantId(), "remove_content", "idle", contentId, delistReason);
    }

    /**
     * 下架求助信息：标记违规状态、发送通知、记录操作日志，
     * 并自动拒绝该求助下所有待审批的帮助申请，确保待审批列表同步清空。
     */
    private void removeHelpContent(Long contentId, Long adminId, String delistReason, boolean fromModeration, String updatedAt) {
        HelpRequest item = helpRequestRepository.findById(contentId)
                .orElseThrow(() -> new BizException("求助信息不存在"));
        checkVersionConflict(item.getUpdatedAt(), updatedAt);
        item.setStatus(BizStatus.OFFLINE);
        item.setDelistReason(delistReason);
        // 审核 tab 驳回：设为 ModerationStatus.REVIEWED，红牌筛选 (REVIEWED + offline) 可匹配；其他 tab 下架：清空审核状态，不参与 moderation 筛选
        item.setModerationStatus(fromModeration ? ModerationStatus.REVIEWED : null);
        item.setReviewedBy(adminId);
        helpRequestRepository.save(item);

        // 拒绝该求助下所有待审批的帮助申请，使其从待审批列表中消失
        List<HelpApplication> pendingApps = helpApplicationRepository.findByHelpIdAndStatus(contentId, BizStatus.PENDING);
        for (HelpApplication app : pendingApps) {
            app.setStatus(BizStatus.REJECTED);
            helpApplicationRepository.save(app);
            createNotification(app.getHelperId(), "audit_result",
                    "帮助申请已拒绝",
                    "您对「" + item.getTitle() + "」的帮助申请因求助被下架而自动拒绝，原因：" + delistReason,
                    app.getId());
        }

        // 强制结束该求助下所有进行中的帮助，使其从进行中列表中消失
        List<HelpApplication> activeApps = helpApplicationRepository.findByHelpIdAndStatus(contentId, BizStatus.APPROVED);
        for (HelpApplication app : activeApps) {
            app.setStatus(BizStatus.COMPLETED);
            app.setCompletedAt(LocalDateTime.now());
            helpApplicationRepository.save(app);
            createNotification(app.getHelperId(), "violation",
                    "求助已被下架",
                    "您正在帮助的「" + item.getTitle() + "」已被管理员下架，帮助已强制结束",
                    app.getId());
        }

        createNotification(item.getUserId(), NotificationType.CONTENT_REJECTED,
                "发布内容未通过审核", "您发布的「" + item.getTitle() + "」因" + delistReason + "未通过审核",
                item.getId());

        saveOperationLog(adminId, item.getTenantId(), "remove_content", "help", contentId, delistReason);
    }

    /**
     * 保存操作日志（抽取 removeContent 中重复的日志构建逻辑）。
     */
    private void saveOperationLog(Long adminId, Long tenantId, String action,
                                  String targetType, Long targetId, String detail) {
        OperationLog log = new OperationLog();
        log.setAdminId(adminId);
        log.setTenantId(tenantId);
        log.setAction(action);
        log.setTargetType(targetType);
        log.setTargetId(targetId);
        log.setDetail(detail);
        log.setCreatedAt(LocalDateTime.now());
        operationLogRepository.save(log);
    }

    // ==================== 代发布 ====================

    public IdleItemDTO proxyPublishIdle(Long adminId, IdleItemRequest req) {
        requireNotSuperAdmin(findAdmin(adminId));
        // tenant_id 为 NOT NULL 列，代发数据必须归属管理员所在租户，否则 save 直接抛约束异常
        Long tenantId = getAdminTenantId(adminId);
        IdleItem item = new IdleItem();
        item.setUserId(req.getUserId() != null ? req.getUserId() : adminId);
        item.setTenantId(tenantId);
        item.setTitle(req.getTitle());
        item.setDescription(req.getDescription());
        item.setPostType(req.getPostType());
        item.setCategory(req.getCategory());
        item.setImages(req.getImages());
        item.setPrice(req.getPrice());
        item.setStatus(BizStatus.ONLINE);
        item.setIsProxy(true);
        item.setCreatedAt(LocalDateTime.now());
        item = idleItemRepository.save(item);

        OperationLog log = new OperationLog();
        log.setAdminId(adminId);
        log.setTenantId(tenantId);
        log.setAction("proxy_publish_idle");
        log.setTargetType("idle");
        log.setTargetId(item.getId());
        log.setDetail("管理员代发闲置物品");
        log.setCreatedAt(LocalDateTime.now());
        operationLogRepository.save(log);

        return IdleItemDTO.builder()
                .id(item.getId())
                .userId(item.getUserId())
                .title(item.getTitle())
                .description(item.getDescription())
                .postType(item.getPostType())
                .category(item.getCategory())
                .images(item.getImages())
                .price(item.getPrice())
                .status(item.getStatus())
                .createdAt(item.getCreatedAt())
                .build();
    }

    /**
     * 管理员代发求助。
     */
    public HelpResponseDTO proxyPublishHelp(Long adminId, HelpRequestDTO req) {
        requireNotSuperAdmin(findAdmin(adminId));
        Long tenantId = getAdminTenantId(adminId);
        HelpRequest helpRequest = new HelpRequest();
        helpRequest.setUserId(req.getUserId() != null ? req.getUserId() : adminId);
        helpRequest.setTenantId(tenantId);
        helpRequest.setTitle(req.getTitle());
        helpRequest.setDescription(req.getDescription());
        helpRequest.setCategory(req.getCategory());
        helpRequest.setIsUrgent(req.getIsUrgent() != null && req.getIsUrgent());

        // 解析时间字段（格式与 HelpService.publish 一致）
        parseHelpTimeFields(helpRequest, req);

        helpRequest.setStatus(BizStatus.ONLINE);
        helpRequest.setIsProxy(true);
        helpRequest.setCreatedAt(LocalDateTime.now());
        helpRequest = helpRequestRepository.save(helpRequest);

        saveOperationLog(adminId, tenantId, "proxy_publish_help", "help",
                helpRequest.getId(), "管理员代发求助");

        return HelpResponseDTO.builder()
                .id(helpRequest.getId())
                .userId(helpRequest.getUserId())
                .title(helpRequest.getTitle())
                .description(helpRequest.getDescription())
                .category(helpRequest.getCategory())
                .isUrgent(helpRequest.getIsUrgent())
                .status(helpRequest.getStatus())
                .createdAt(helpRequest.getCreatedAt())
                .build();
    }

    /**
     * 解析 HelpRequestDTO 中的 timeStart / timeEnd 字符串并设置到实体上。
     */
    private void parseHelpTimeFields(HelpRequest helpRequest, HelpRequestDTO req) {
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        if (req.getTimeStart() != null && !req.getTimeStart().isEmpty()) {
            try {
                helpRequest.setTimeStart(LocalDateTime.parse(req.getTimeStart(), fmt));
            } catch (Exception e) {
                log.debug("Failed to parse timeStart: {}", e.getMessage());
            }
        }
        if (req.getTimeEnd() != null && !req.getTimeEnd().isEmpty()) {
            try {
                helpRequest.setTimeEnd(LocalDateTime.parse(req.getTimeEnd(), fmt));
            } catch (Exception e) {
                log.debug("Failed to parse timeEnd: {}", e.getMessage());
            }
        }
    }

    // ==================== 记录 ====================

    /**
     * 获取交易记录（已归还的借用 / 已完成的帮助），按 createdAt 降序分页。
     */
    public List<RecordItemDTO> getRecords(Long adminId, String type) {
        requireNotSuperAdmin(findAdmin(adminId));
        Long tenantId = getAdminTenantId(adminId);
        List<RecordItemDTO> allRecords = switch (type) {
            case "borrow" -> loadBorrowRecords(tenantId);
            case "help" -> loadHelpRecords(tenantId);
            case "all" -> {
                List<RecordItemDTO> merged = new ArrayList<>();
                merged.addAll(loadBorrowRecords(tenantId));
                merged.addAll(loadHelpRecords(tenantId));
                yield merged;
            }
            default -> throw new BizException("不支持的类型，请使用 borrow、help 或 all");
        };

        sortByCreatedAtDesc(allRecords);
        return allRecords;
    }

    /**
     * 加载该租户下已归还的借用记录（含评分、时长、物品状况等详情）。
     */
    private List<RecordItemDTO> loadBorrowRecords(Long tenantId) {
        List<BorrowRequest> borrows = borrowRequestRepository.findByStatus(BizStatus.RETURNED).stream()
                .filter(br -> {
                    IdleItem idle = idleItemRepository.findById(br.getIdleId()).orElse(null);
                    return idle != null && tenantMatches(tenantId, idle.getTenantId());
                })
                .collect(Collectors.toList());

        // 批量查询评价
        List<Long> borrowIds = borrows.stream().map(BorrowRequest::getId).collect(Collectors.toList());
        Map<Long, List<Rating>> ratingsByBorrowId = borrowIds.isEmpty() ? Map.of()
                : ratingRepository.findByBorrowIdIn(borrowIds).stream()
                        .collect(Collectors.groupingBy(Rating::getBorrowId));

        List<RecordItemDTO> records = new ArrayList<>();
        for (BorrowRequest br : borrows) {
            IdleItem idleItem = idleItemRepository.findById(br.getIdleId()).orElse(null);
            User borrower = userRepository.findById(br.getBorrowerId()).orElse(null);
            Long ownerId = idleItem != null ? idleItem.getUserId() : null;
            User owner = ownerId != null ? userRepository.findById(ownerId).orElse(null) : null;

            // 评价按时间排序：先评价的在前
            List<Rating> ratings = ratingsByBorrowId.getOrDefault(br.getId(), List.of()).stream()
                    .sorted(Comparator.comparing(Rating::getCreatedAt))
                    .collect(Collectors.toList());

            // 借出方收到的评价 = toUserId == ownerId
            Rating ownerRating = ratings.stream().filter(r -> r.getToUserId().equals(ownerId)).findFirst().orElse(null);
            // 借入方收到的评价 = toUserId == borrowerId
            Rating borrowerRating = ratings.stream().filter(r -> r.getToUserId().equals(br.getBorrowerId())).findFirst().orElse(null);

            RecordItemDTO.RecordItemDTOBuilder b = RecordItemDTO.builder()
                    .id(br.getId())
                    .type("borrow")
                    .title(idleItem != null ? idleItem.getTitle() : "未知物品")
                    .publisher(UserFormatter.formatPersonName(owner))
                    .peer(UserFormatter.formatPersonName(borrower))
                    .content(idleItem != null ? idleItem.getTitle() : "未知物品")
                    .room(UserFormatter.formatRoom(owner))
                    .timeStart(fmt(br.getCreatedAt()))
                    .timeEnd(br.getReturnedAt() != null ? fmt(br.getReturnedAt()) : null)
                    .createdAt(fmt(br.getCreatedAt()))
                    .status(br.getStatus())
                    // 物品状况
                    .condBefore(condLabel(idleItem != null ? idleItem.getCondition() : null))
                    .condAfter(damageLabel(br.getDamageType(), br.getDamageNote()))
                    .returnStatus(returnLabel(br.getReturnStatus()))
                    .lendDuration(buildDuration(br))
                    .damageType(br.getDamageType())
                    // 评分（感想是评分人自己的感受，不是被评人的）：
                    // ownerRating    = 借入方→借出方的评价，feedback 是借入方的感想
                    // borrowerRating = 借出方→借入方的评价，feedback 是借出方的感想
                    .pubRatingScore(ownerRating != null ? ownerRating.getScore() : null)
                    .pubComment(borrowerRating != null ? borrowerRating.getFeedback() : null)
                    .peerRatingScore(borrowerRating != null ? borrowerRating.getScore() : null)
                    .peerComment(ownerRating != null ? ownerRating.getFeedback() : null);
            // 时间线：发布时间、申请时间、同意时间、归还·评价×2
            buildBorrowTimeline(b, idleItem, br, ratings, ownerId);
            records.add(b.build());
        }
        return records;
    }

    /** 构建借用记录的 5 节点时间线 */
    private void buildBorrowTimeline(RecordItemDTO.RecordItemDTOBuilder b, IdleItem idleItem, BorrowRequest br,
                                      List<Rating> sortedRatings, Long ownerId) {
        b.publishedAt(fmt(idleItem != null ? idleItem.getCreatedAt() : null));
        b.applyAt(fmt(br.getCreatedAt()));
        b.approveAt(fmt(br.getApprovedAt()));

        if (!sortedRatings.isEmpty()) {
            Rating first = sortedRatings.get(0);
            b.rating1Label(first.getFromUserId().equals(br.getBorrowerId()) ? "借入方评价" : "借出方评价");
            b.rating1Time(fmt(first.getCreatedAt()));
        }
        if (sortedRatings.size() >= 2) {
            Rating second = sortedRatings.get(1);
            b.rating2Label(second.getFromUserId().equals(br.getBorrowerId()) ? "借入方评价" : "借出方评价");
            b.rating2Time(fmt(second.getCreatedAt()));
        }
    }

    /** 格式化 LocalDateTime 为 "yyyy-MM-dd HH:mm" 字符串 */
    private static String fmt(LocalDateTime dt) {
        return dt != null ? dt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) : null;
    }

    /** 构建帮助记录的 5 节点时间线 */
    private void buildHelpTimeline(RecordItemDTO.RecordItemDTOBuilder b, HelpRequest helpRequest,
                                    HelpApplication app, List<Rating> sortedRatings) {
        b.publishedAt(fmt(helpRequest != null ? helpRequest.getCreatedAt() : null));
        b.applyAt(fmt(app.getCreatedAt()));
        // 帮助申请无独立审批时间字段，用更新时间近似（penging→accepted 时会刷新）
        b.approveAt(fmt(app.getUpdatedAt()));

        if (!sortedRatings.isEmpty()) {
            Rating first = sortedRatings.get(0);
            b.rating1Label(first.getFromUserId().equals(app.getHelperId()) ? "相助方评价" : "求助方评价");
            b.rating1Time(fmt(first.getCreatedAt()));
        }
        if (sortedRatings.size() >= 2) {
            Rating second = sortedRatings.get(1);
            b.rating2Label(second.getFromUserId().equals(app.getHelperId()) ? "相助方评价" : "求助方评价");
            b.rating2Time(fmt(second.getCreatedAt()));
        }
    }

    /** 构建借用时长描述文字（如 "3天"、"1周"） */
    private String buildDuration(BorrowRequest br) {
        if (br.getDurationType() == null || br.getDurationDays() == null) return null;
        return br.getDurationDays() + mapDurationUnit(br.getDurationType());
    }

    private String mapDurationUnit(String type) {
        return switch (type) {
            case "hour" -> "小时";
            case "day" -> "天";
            case "week" -> "周";
            case "month" -> "月";
            default -> "未知";
        };
    }

    /** 物品成色映射为中文（对齐 C端 condition 枚举） */
    private String condLabel(String condition) {
        if (condition == null) return null;
        return switch (condition) {
            case "like-new" -> "几乎全新";
            case "normal"  -> "正常使用痕迹";
            case "worn"    -> "有明显磨损";
            default        -> condition;
        };
    }

    /** 损坏类型映射为中文（对齐 C端三类体系 + 拼接备注） */
    private String damageLabel(String damageType, String damageNote) {
        String type = (damageType != null && !damageType.isEmpty())
                ? mapDamageType(damageType)
                : "待确认";
        if (damageNote != null && !damageNote.isEmpty()) return type + "：" + damageNote;
        return type;
    }

    /** 损坏类型映射为中文（统一三类：正常损耗/非正常损坏/完全损坏），兼容所有历史值 */
    private String mapDamageType(String dt) {
        if (dt == null || dt.isEmpty()) return "待确认";
        return switch (dt) {
            // 统一后的三类主值
            case DamageType.NORMAL   -> "正常损耗";
            case DamageType.ABNORMAL -> "非正常损坏";
            case DamageType.BROKEN   -> "完全损坏";
            // 兼容旧值（存量数据迁移已覆盖，此处兜底确保不显示原始字符串）
            case "none"              -> "正常损耗";
            case "minor", "slight"   -> "非正常损坏";
            case "moderate"          -> "非正常损坏";
            default                  -> dt;
        };
    }

    /** 归还情况映射为中文（对齐 C端：ontime/delayed/not_returned） */
    private String returnLabel(String returnStatus) {
        if (returnStatus == null) return null;
        return switch (returnStatus) {
            case ReturnStatus.ON_TIME      -> "按时归还";
            case ReturnStatus.DELAYED      -> "逾期归还";
            case ReturnStatus.NOT_RETURNED -> "未归还";
            default                        -> "";
        };
    }

    /**
     * 加载该租户下已完成的帮助记录（含评分、时间线等详情）。
     */
    private List<RecordItemDTO> loadHelpRecords(Long tenantId) {
        List<HelpApplication> applications = helpApplicationRepository.findByStatus(BizStatus.COMPLETED).stream()
                .filter(app -> {
                    HelpRequest hr = helpRequestRepository.findById(app.getHelpId()).orElse(null);
                    return hr != null && tenantMatches(tenantId, hr.getTenantId());
                })
                .collect(Collectors.toList());

        // 批量查询所有帮助申请的评价
        List<Long> appIds = applications.stream().map(HelpApplication::getId).collect(Collectors.toList());
        Map<Long, List<Rating>> ratingsByAppId = appIds.isEmpty() ? Map.of()
                : ratingRepository.findByHelpApplicationIdIn(appIds).stream()
                        .collect(Collectors.groupingBy(Rating::getHelpApplicationId));

        List<RecordItemDTO> records = new ArrayList<>();
        for (HelpApplication app : applications) {
            HelpRequest helpRequest = helpRequestRepository.findById(app.getHelpId()).orElse(null);
            User helper = userRepository.findById(app.getHelperId()).orElse(null);
            Long requesterId = helpRequest != null ? helpRequest.getUserId() : null;
            User requester = requesterId != null ? userRepository.findById(requesterId).orElse(null) : null;

            // 评价按时间排序
            List<Rating> ratings = ratingsByAppId.getOrDefault(app.getId(), List.of()).stream()
                    .sorted(Comparator.comparing(Rating::getCreatedAt))
                    .collect(Collectors.toList());
            Rating requesterRating = ratings.stream().filter(r -> r.getToUserId().equals(requesterId)).findFirst().orElse(null);
            Rating helperRating = ratings.stream().filter(r -> r.getToUserId().equals(app.getHelperId())).findFirst().orElse(null);

            RecordItemDTO.RecordItemDTOBuilder b = RecordItemDTO.builder()
                    .id(app.getId())
                    .type("help")
                    .title(helpRequest != null ? helpRequest.getTitle() : "未知求助")
                    .publisher(UserFormatter.formatPersonName(requester))
                    .peer(UserFormatter.formatPersonName(helper))
                    .content(helpRequest != null ? helpRequest.getTitle() : "未知求助")
                    .room(UserFormatter.formatRoom(requester))
                    .timeStart(fmt(app.getCreatedAt()))
                    .timeEnd(app.getCompletedAt() != null
                            ? fmt(app.getCompletedAt()) : fmt(app.getUpdatedAt()))
                    .createdAt(fmt(app.getCreatedAt()))
                    .status(app.getStatus())
                    // 评分（感想是评分人自己的感受，不是被评人的）：
                    // requesterRating = 相助方→求助方的评价，feedback 是相助方的感想
                    // helperRating     = 求助方→相助方的评价，feedback 是求助方的感想
                    .pubRatingScore(requesterRating != null ? requesterRating.getScore() : null)
                    .pubComment(helperRating != null ? helperRating.getFeedback() : null)
                    .peerRatingScore(helperRating != null ? helperRating.getScore() : null)
                    .peerComment(requesterRating != null ? requesterRating.getFeedback() : null);
            // 时间线：发布时间、申请时间、同意时间、结束·评价×2
            buildHelpTimeline(b, helpRequest, app, ratings);
            records.add(b.build());
        }
        return records;
    }

    /**
     * 按 createdAt 降序排列记录列表（null 值排最后）。
     * createdAt 已格式化为 "yyyy-MM-dd HH:mm" 字符串，可直接字典序比较。
     */
    private void sortByCreatedAtDesc(List<RecordItemDTO> records) {
        records.sort((a, b) -> {
            String ta = a.getCreatedAt();
            String tb = b.getCreatedAt();
            if (ta == null && tb == null) return 0;
            if (ta == null) return 1;
            if (tb == null) return -1;
            return tb.compareTo(ta);
        });
    }


    // ==================== 操作日志 ====================

    /**
     * 查询操作日志（按时间倒序，返回当前管理员租户下的全部，一次性返回不分页）。
     *
     * @param adminId 当前管理员 ID（需高级管理员权限）
     * @return 该租户下全部操作日志 DTO 列表
     */
    public List<OperationLogDTO> getOperationLogs(Long adminId) {
        User admin = findAdmin(adminId);
        requireSeniorAdmin(admin);
        Long tenantId = getAdminTenantId(adminId);
        // 收集该租户下所有用户 ID，用于过滤日志
        List<Long> tenantUserIds = userRepository.findAll().stream()
                .filter(u -> tenantMatches(tenantId, u.getTenantId()))
                .map(User::getId)
                .collect(Collectors.toList());
        // 从全量日志中按本租户用户过滤（不再分页），返回该租户全部操作日志
        List<OperationLog> filteredLogs = operationLogRepository
                .findAll(Sort.by(Sort.Direction.DESC, "createdAt")).stream()
                .filter(log -> tenantUserIds.contains(log.getAdminId()))
                .collect(Collectors.toList());

        // 一次性返回全部操作日志
        return filteredLogs.stream()
                .map(this::toOperationLogDTO)
                .collect(Collectors.toList());
    }

    // ==================== 操作日志导出 ====================

    /**
     * 导出操作日志为 Excel 文件。
     * 按时间倒序排列，包含时间、操作人、动作、目标类型、详情五列。
     */
    public byte[] exportOperationLogs(Long adminId) {
        User caller = findAdmin(adminId);
        requireSeniorAdmin(caller);
        Long tenantId = getAdminTenantId(adminId);

        // 收集该租户下所有用户 ID
        List<Long> tenantUserIds = userRepository.findAll().stream()
                .filter(u -> tenantMatches(tenantId, u.getTenantId()))
                .map(User::getId)
                .collect(Collectors.toList());

        // 获取操作日志并转换为行数据
        List<OperationLog> allLogs = operationLogRepository.findAll().stream()
                .filter(l -> tenantUserIds.contains(l.getAdminId()))
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .collect(Collectors.toList());

        List<List<Object>> rows = new ArrayList<>();
        for (OperationLog l : allLogs) {
            User opAdmin = userRepository.findById(l.getAdminId()).orElse(null);
            List<Object> row = new ArrayList<>();
            row.add(fmt(l.getCreatedAt()));
            row.add(opAdmin != null ? opAdmin.getName() : "");
            row.add(mapAction(l.getAction()));
            row.add(mapTargetType(l.getTargetType()));
            row.add(l.getDetail() != null ? l.getDetail() : "");
            rows.add(row);
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ExcelWriter writer = EasyExcel.write(baos).build();
        WriteSheet sheet = EasyExcel.writerSheet("操作日志")
                .head(Arrays.asList(
                        Arrays.asList("时间"),
                        Arrays.asList("操作人"),
                        Arrays.asList("操作动作"),
                        Arrays.asList("目标类型"),
                        Arrays.asList("详情")
                )).build();
        writer.write(rows, sheet);
        writer.finish();

        return baos.toByteArray();
    }

    /**
     * 导出导出日志为 Excel 文件，按租户范围过滤。
     * super_admin 查看全部记录，senior_admin 仅查看本小区。
     */
    public byte[] exportExportLogs(Long adminId) {
        User caller = findAdmin(adminId);
        requireSeniorAdmin(caller);
        Long tenantId = getAdminTenantId(adminId);

        // super_admin 查看全部导出记录，senior_admin 仅查看本小区
        List<ExportLog> allLogs = tenantId != null
                ? exportLogRepository.findByTenantIdOrderByCreatedAtDesc(tenantId)
                : exportLogRepository.findAll().stream()
                        .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                        .collect(Collectors.toList());

        // 导出选项的中文映射
        Map<String, String> optionLabels = Map.of(
            "residents", "住户",
            "posts", "发布",
            "borrows", "互借",
            "helps", "互助",
            "removals", "下架",
            "ratings", "评分"
        );

        List<List<Object>> rows = new ArrayList<>();
        for (ExportLog log : allLogs) {
            User admin = userRepository.findById(log.getAdminId()).orElse(null);
            // senior_admin 不展示 super_admin 的导出记录
            if (tenantId != null && admin != null && UserType.SUPER_ADMIN.equals(admin.getUserType())) {
                continue;
            }
            // 将英文选项键转为中文标签
            String rawOptions = log.getSelectedOptions();
            String optionsChinese = rawOptions;
            if (rawOptions != null && !rawOptions.isEmpty()) {
                String[] keys = rawOptions.split(",");
                optionsChinese = java.util.Arrays.stream(keys)
                        .map(k -> optionLabels.getOrDefault(k.trim(), k.trim()))
                        .collect(Collectors.joining("、"));
            }
            List<Object> row = new ArrayList<>();
            row.add(fmt(log.getCreatedAt()));
            row.add(admin != null ? admin.getName() : "未知");
            row.add(optionsChinese);
            rows.add(row);
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ExcelWriter writer = EasyExcel.write(baos).build();
        WriteSheet sheet = EasyExcel.writerSheet("导出日志")
                .head(Arrays.asList(
                        Arrays.asList("时间"),
                        Arrays.asList("操作人"),
                        Arrays.asList("导出项目")
                )).build();
        writer.write(rows, sheet);
        writer.finish();

        return baos.toByteArray();
    }

    // ==================== 数据导出 ====================


    /**
     * 执行数据导出，根据勾选项目生成多 Sheet Excel 文件并返回字节数组。
     * 仅勾选的项目会出现在对应的 Sheet 中。
     *
     * @param adminId 当前管理员ID
     * @param req     导出请求体（勾选项目、日期范围）
     * @return Excel 文件的字节数组
     */
    public byte[] exportData(Long adminId, ExportRequest req) {
        User caller = findAdmin(adminId);
        requireSeniorAdmin(caller);
        Long tenantId = getAdminTenantId(adminId);
        LocalDate startDate = parseDate(req.getDateStart());
        LocalDate endDate = req.getDateEnd() != null
                ? parseDate(req.getDateEnd()).plusDays(1)  // 闭区间：包含当天全部数据
                : null;

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ExcelWriter writer = EasyExcel.write(baos).build();

        // 六个导出项对应的记录数，顺序：residents/posts/borrows/helps/removals/ratings
        int[] counts = new int[6];
        int sheetIdx = 0;

        try {
            if (contains(req.getOptions(), "residents")) {
                List<List<Object>> data = buildResidentsData(tenantId, startDate, endDate);
                WriteSheet sheet = EasyExcel.writerSheet(sheetIdx++, "住户清单")
                        .head(residentsHead()).build();
                writer.write(data, sheet);
                counts[0] = data.size();
            }

            if (contains(req.getOptions(), "posts")) {
                List<List<Object>> data = buildPostsData(tenantId, startDate, endDate);
                WriteSheet sheet = EasyExcel.writerSheet(sheetIdx++, "发布记录")
                        .head(postsHead()).build();
                writer.write(data, sheet);
                counts[1] = data.size();
            }

            if (contains(req.getOptions(), "borrows")) {
                List<List<Object>> data = buildBorrowsData(tenantId, startDate, endDate);
                WriteSheet sheet = EasyExcel.writerSheet(sheetIdx++, "互借记录")
                        .head(borrowsHead()).build();
                writer.write(data, sheet);
                counts[2] = data.size();

                List<List<Object>> helpData = buildHelpsData(tenantId, startDate, endDate);
                WriteSheet helpSheet = EasyExcel.writerSheet(sheetIdx++, "互助记录")
                        .head(helpsHead()).build();
                writer.write(helpData, helpSheet);
                counts[3] = helpData.size();
            }

            if (contains(req.getOptions(), "removals")) {
                List<List<Object>> data = buildRemovalsData(tenantId, startDate, endDate);
                WriteSheet sheet = EasyExcel.writerSheet(sheetIdx++, "内容下架记录")
                        .head(removalsHead()).build();
                writer.write(data, sheet);
                counts[4] = data.size();
            }

            if (contains(req.getOptions(), "ratings")) {
                List<List<Object>> data = buildRatingsData(tenantId, startDate, endDate);
                WriteSheet sheet = EasyExcel.writerSheet(sheetIdx++, "评分数据")
                        .head(ratingsHead()).build();
                writer.write(data, sheet);
                counts[5] = data.size();
            }
        } finally {
            writer.finish();
        }

        byte[] bytes = baos.toByteArray();

        // 记录导出日志 — 文件名格式：{小区名}_{导出日期时间}.xlsx
        // super_admin 的 tenantId 为 null，文件名为 "community_日期时间.xlsx"
        Tenant tenant = tenantId != null ? tenantRepository.findById(tenantId).orElse(null) : null;
        String tenantName = tenant != null ? tenant.getName() : "community";
        String exportDate = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm"));
        String fileName = tenantName + "_" + exportDate + ".xlsx";
        saveExportLog(adminId, tenantId, req, counts, fileName);

        return bytes;
    }

    // ──────────────────────────────────────────────
    // 住户清单 Sheet
    // ──────────────────────────────────────────────

    /** 住户清单 Sheet 表头 */
    private List<List<String>> residentsHead() {
        return Arrays.asList(
                Arrays.asList("住户"),
                Arrays.asList("姓名"),
                Arrays.asList("手机号"),
                Arrays.asList("用户类型"),
                Arrays.asList("认证状态"),
                Arrays.asList("完整地址"),
                Arrays.asList("注册时间"),
                Arrays.asList("借入次数"),
                Arrays.asList("借出次数"),
                Arrays.asList("求助次数"),
                Arrays.asList("帮助他人次数"),
                Arrays.asList("按时归还率"),
                Arrays.asList("个人综合评分")
        );
    }

    /**
     * 构建住户清单数据：已认证的非管理员住户 + 互助统计 + 综合评分。
     * 按注册时间倒序排列（最新在最上面）。
     */
    private List<List<Object>> buildResidentsData(Long tenantId, LocalDate startDate, LocalDate endDate) {
        List<List<Object>> data = new ArrayList<>();
        List<User> users = tenantId != null
                ? userRepository.findByTenantIdAndAuthStatusAndUserTypeNotIn(tenantId, BizStatus.APPROVED, ADMIN_USER_TYPES)
                : userRepository.findByAuthStatusAndUserTypeNotIn(BizStatus.APPROVED, ADMIN_USER_TYPES);

        // 按注册时间倒序
        users.sort((a, b) -> {
            if (a.getCreatedAt() == null) return 1;
            if (b.getCreatedAt() == null) return -1;
            return b.getCreatedAt().compareTo(a.getCreatedAt());
        });

        for (User user : users) {
            if (!inRange(user.getCreatedAt(), startDate, endDate)) continue;

            UserActivityService.InteractionStats stats =
                    userActivityService.interactionStats(user.getId());
            double returnRate = stats.returnedCount() > 0
                    ? Math.round((double) stats.onTimeCount() / stats.returnedCount() * 1000.0) / 10.0
                    : 100.0;

            // 个人综合评分
            Double avgScore = ratingRepository.getAverageScore(user.getId());
            String scoreText = avgScore != null
                    ? String.format("%.1f", avgScore) + "分"
                    : "5.0分";

            List<Object> row = new ArrayList<>();
            row.add(UserFormatter.formatRoom(user));
            row.add(user.getName());
            row.add(user.getPhone());
            row.add(UserFormatter.getUserTypeLabel(user.getUserType()));
            row.add(BizStatus.APPROVED.equals(user.getAuthStatus()) ? "已认证" : "未认证");
            row.add(UserFormatter.formatRoom(user));
            row.add(fmt(user.getCreatedAt()));
            row.add(stats.borrowCount());
            row.add(stats.lendCount());
            row.add(stats.helpReqCount());
            row.add(stats.helpProCount());
            row.add(returnRate + "%");
            row.add(scoreText);
            data.add(row);
        }
        return data;
    }

    // ──────────────────────────────────────────────
    // 发布记录 Sheet（闲置 LEND/WANTED + 技能求助 合并）
    // ──────────────────────────────────────────────

    /** 发布记录 Sheet 表头 */
    private List<List<String>> postsHead() {
        return Arrays.asList(
                Arrays.asList("发布类型"),
                Arrays.asList("标题"),
                Arrays.asList("分类"),
                Arrays.asList("发布住户"),
                Arrays.asList("发布人姓名"),
                Arrays.asList("状态"),
                Arrays.asList("下架原因"),
                Arrays.asList("发布时间"),
                Arrays.asList("备注")
        );
    }

    /**
     * 构建发布记录数据：合并闲置物品（LEND+WANTED）和技能求助为统一字段集。
     */
    private List<List<Object>> buildPostsData(Long tenantId, LocalDate startDate, LocalDate endDate) {
        List<List<Object>> data = new ArrayList<>();

        // 闲置物品（LEND + WANTED）
        List<IdleItem> idleItems = idleItemRepository.findAll().stream()
                .filter(i -> tenantMatches(tenantId, i.getTenantId()))
                .collect(Collectors.toList());
        for (IdleItem item : idleItems) {
            if (!inRange(item.getCreatedAt(), startDate, endDate)) continue;
            User user = userRepository.findById(item.getUserId()).orElse(null);

            String postType = PostType.LEND.equals(item.getPostType()) ? "闲置借出" : "需求借入";

            List<Object> row = new ArrayList<>();
            row.add(postType);
            row.add(item.getTitle() != null && !item.getTitle().isEmpty() ? item.getTitle() : "（无标题）");
            row.add(item.getCategory() != null && !item.getCategory().isEmpty() ? item.getCategory() : "（无分类）");
            row.add(user != null ? UserFormatter.formatRoom(user) : "");
            row.add(user != null ? user.getName() : "");
            row.add(mapIdleStatus(item.getStatus()));
            row.add(mapDelistReason(item.getDelistReason()));
            row.add(fmt(item.getCreatedAt()));
            row.add(item.getDescription() != null ? item.getDescription() : "");
            data.add(row);
        }

        // 技能求助
        List<HelpRequest> helps = helpRequestRepository.findAll().stream()
                .filter(h -> tenantMatches(tenantId, h.getTenantId()))
                .collect(Collectors.toList());
        for (HelpRequest item : helps) {
            if (!inRange(item.getCreatedAt(), startDate, endDate)) continue;
            User user = userRepository.findById(item.getUserId()).orElse(null);

            List<Object> row = new ArrayList<>();
            row.add("技能求助");
            row.add(item.getTitle() != null && !item.getTitle().isEmpty() ? item.getTitle() : "（无标题）");
            row.add(item.getCategory() != null && !item.getCategory().isEmpty() ? item.getCategory() : "（无分类）");
            row.add(user != null ? UserFormatter.formatRoom(user) : "");
            row.add(user != null ? user.getName() : "");
            row.add(mapHelpStatus(item.getStatus()));
            row.add(mapDelistReason(item.getDelistReason()));
            row.add(fmt(item.getCreatedAt()));
            row.add(item.getDescription() != null ? item.getDescription() : "");
            data.add(row);
        }

        // 按发布时间倒序排列（最新在最上面）
        data.sort((a, b) -> {
            String ta = (String) a.get(7);  // 发布时间在第 8 列（索引 7）
            String tb = (String) b.get(7);
            if (ta == null && tb == null) return 0;
            if (ta == null) return 1;
            if (tb == null) return -1;
            return tb.compareTo(ta);
        });

        return data;
    }

    // ──────────────────────────────────────────────
    // 互借记录 Sheet（仅已归还的借用交易，含双方评分）
    // ──────────────────────────────────────────────

    /** 互借记录 Sheet 表头 */
    private List<List<String>> borrowsHead() {
        return Arrays.asList(
                Arrays.asList("借入住户"),
                Arrays.asList("借入人姓名"),
                Arrays.asList("获得评分"),
                Arrays.asList("互助感想"),
                Arrays.asList("借出住户"),
                Arrays.asList("借出人姓名"),
                Arrays.asList("获得评分"),
                Arrays.asList("互助感想"),
                Arrays.asList("发布标题"),
                Arrays.asList("详情说明"),
                Arrays.asList("借入时长"),
                Arrays.asList("状态"),
                Arrays.asList("借出前物品状况"),
                Arrays.asList("是否按时归还"),
                Arrays.asList("归还后物品状况"),
                Arrays.asList("发布时间"),
                Arrays.asList("完成时间")
        );
    }

    /**
     * 构建互借记录数据：仅已归还的借用交易，含双方评分和物品详情。
     * 按发布时间倒序排列（最新在最上面）。
     */
    private List<List<Object>> buildBorrowsData(Long tenantId, LocalDate startDate, LocalDate endDate) {
        List<List<Object>> data = new ArrayList<>();
        // 仅查询已归还的借用记录（确保损坏类型和归还状态非空）
        List<BorrowRequest> borrows = borrowRequestRepository.findByStatus(BizStatus.RETURNED).stream()
                .filter(br -> {
                    IdleItem idle = idleItemRepository.findById(br.getIdleId()).orElse(null);
                    return idle != null && tenantMatches(tenantId, idle.getTenantId());
                })
                .collect(Collectors.toList());

        // 批量查询评价
        List<Long> borrowIds = borrows.stream().map(BorrowRequest::getId).collect(Collectors.toList());
        Map<Long, List<Rating>> ratingsByBorrowId = borrowIds.isEmpty() ? Map.of()
                : ratingRepository.findByBorrowIdIn(borrowIds).stream()
                        .collect(Collectors.groupingBy(Rating::getBorrowId));

        for (BorrowRequest br : borrows) {
            IdleItem idleItem = idleItemRepository.findById(br.getIdleId()).orElse(null);
            if (idleItem == null) continue;
            // 排除已下架的物品
            if (BizStatus.OFFLINE.equals(idleItem.getStatus())
                    || BizStatus.DRAFT.equals(idleItem.getStatus())) continue;
            // 按物品的发布时间过滤（对齐列标签"发布时间"）
            if (!inRange(idleItem.getCreatedAt(), startDate, endDate)) continue;

            User borrower = userRepository.findById(br.getBorrowerId()).orElse(null);
            Long ownerId = idleItem.getUserId();
            User lender = ownerId != null ? userRepository.findById(ownerId).orElse(null) : null;

            // 评价按时间排序：先评价的在前
            List<Rating> ratings = ratingsByBorrowId.getOrDefault(br.getId(), List.of()).stream()
                    .sorted(Comparator.comparing(Rating::getCreatedAt))
                    .collect(Collectors.toList());
            // 借入方收到的评价（借出方对借入方的评价）= toUserId == borrowerId
            Rating borrowerRating = ratings.stream()
                    .filter(r -> r.getToUserId().equals(br.getBorrowerId()))
                    .findFirst().orElse(null);
            // 借出方收到的评价（借入方对借出方的评价）= toUserId == ownerId
            Rating lenderRating = ratings.stream()
                    .filter(r -> r.getToUserId().equals(ownerId))
                    .findFirst().orElse(null);

            String duration = br.getDurationDays() != null ? br.getDurationDays().toString() : "";
            if (br.getDurationType() != null) {
                duration += mapDurationUnit(br.getDurationType());
            }

            // 发布时间取物品的原始发布时间，完成时间取归还操作的显式记录时间
            String publishTime = fmt(idleItem.getCreatedAt());
            String completeTime = fmt(br.getReturnedAt());

            List<Object> row = new ArrayList<>();
            // 感想是评分人自己的感受，而不是被评人的：
            // borrowerRating = 借出方→借入方的评价，其 feedback 是借出方的感想
            // lenderRating   = 借入方→借出方的评价，其 feedback 是借入方的感想
            row.add(borrower != null ? UserFormatter.formatRoom(borrower) : "");            // 借入住户
            row.add(borrower != null ? borrower.getName() : "");                             // 借入人姓名
            row.add(borrowerRating != null ? borrowerRating.getScore() + "分" : "对方未评价");  // 获得评分（借入方收到的评分）
            row.add(lenderRating != null && lenderRating.getFeedback() != null
                    ? lenderRating.getFeedback() : "");                                       // 互助感想（借入方自己的感想）
            row.add(lender != null ? UserFormatter.formatRoom(lender) : "");                 // 借出住户
            row.add(lender != null ? lender.getName() : "");                                  // 借出人姓名
            row.add(lenderRating != null ? lenderRating.getScore() + "分" : "对方未评价");      // 获得评分（借出方收到的评分）
            row.add(borrowerRating != null && borrowerRating.getFeedback() != null
                    ? borrowerRating.getFeedback() : "");                                     // 互助感想（借出方自己的感想）
            row.add(idleItem.getTitle());                                                     // 发布标题
            row.add(idleItem.getDescription() != null
                    ? idleItem.getDescription() : "");                                        // 详情说明
            row.add(duration);                                                                // 借入时长
            row.add(mapBorrowStatus(br.getStatus()));                                         // 状态
            row.add(condLabel(idleItem.getCondition()));                                      // 借出前物品状况（idle_items.condition）
            row.add(mapReturnStatus(br.getReturnStatus()));                                   // 是否按时归还（return_status：ontime→按时归还/delayed→逾期归还）
            row.add(mapDamageType(br.getDamageType()));                                       // 归还后物品状况（damage_type：正常损耗/非正常损坏/完全损坏）
            row.add(publishTime);                                                             // 发布时间
            row.add(completeTime);                                                            // 完成时间
            data.add(row);
        }

        // 按发布时间倒序排列（最新在最上面），发布时间在列索引 15
        data.sort((a, b) -> {
            String ta = (String) a.get(15);
            String tb = (String) b.get(15);
            if (ta == null && tb == null) return 0;
            if (ta == null) return 1;
            if (tb == null) return -1;
            return tb.compareTo(ta);
        });

        return data;
    }

    // ──────────────────────────────────────────────
    // 互助记录 Sheet（技能求助交易，仅已完成的帮助申请）
    // ──────────────────────────────────────────────

    /** 互助记录 Sheet 表头 */
    private List<List<String>> helpsHead() {
        return Arrays.asList(
                Arrays.asList("求助标题"),
                Arrays.asList("求助住户"),
                Arrays.asList("求助人姓名"),
                Arrays.asList("获得评分"),
                Arrays.asList("互助感想"),
                Arrays.asList("相助住户"),
                Arrays.asList("相助人姓名"),
                Arrays.asList("获得评分"),
                Arrays.asList("互助感想"),
                Arrays.asList("发布时间"),
                Arrays.asList("完成时间")
        );
    }

    /**
     * 构建互助记录数据：已完成的技能求助交易，包含双方评分和感谢语。
     * 按完成时间倒序排列（最新在最上面）。
     */
    private List<List<Object>> buildHelpsData(Long tenantId, LocalDate startDate, LocalDate endDate) {
        List<List<Object>> data = new ArrayList<>();

        // 查询本小区所有已完成的帮助申请
        List<HelpApplication> applications = helpApplicationRepository.findByStatus(BizStatus.COMPLETED).stream()
                .filter(app -> {
                    HelpRequest hr = helpRequestRepository.findById(app.getHelpId()).orElse(null);
                    return hr != null && tenantMatches(tenantId, hr.getTenantId());
                })
                .collect(Collectors.toList());

        // 批量查询评价
        List<Long> appIds = applications.stream().map(HelpApplication::getId).collect(Collectors.toList());
        Map<Long, List<Rating>> ratingsByAppId = appIds.isEmpty() ? Map.of()
                : ratingRepository.findByHelpApplicationIdIn(appIds).stream()
                        .collect(Collectors.groupingBy(Rating::getHelpApplicationId));

        for (HelpApplication app : applications) {
            HelpRequest helpRequest = helpRequestRepository.findById(app.getHelpId()).orElse(null);
            if (helpRequest == null) continue;
            // 排除已下架的求助
            if (BizStatus.OFFLINE.equals(helpRequest.getStatus())
                    || BizStatus.DRAFT.equals(helpRequest.getStatus())) continue;
            // 按求助的发布时间过滤（对齐列标签"发布时间"）
            if (!inRange(helpRequest.getCreatedAt(), startDate, endDate)) continue;

            User requester = userRepository.findById(helpRequest.getUserId()).orElse(null);
            User helper = userRepository.findById(app.getHelperId()).orElse(null);

            // 评价按时间排序
            List<Rating> ratings = ratingsByAppId.getOrDefault(app.getId(), List.of()).stream()
                    .sorted(Comparator.comparing(Rating::getCreatedAt))
                    .collect(Collectors.toList());

            // 求助方收到的评价（相助方对求助方的评价）
            Rating requesterRating = ratings.stream()
                    .filter(r -> r.getToUserId().equals(helpRequest.getUserId()))
                    .findFirst().orElse(null);
            // 相助方收到的评价（求助方对相助方的评价）
            Rating helperRating = ratings.stream()
                    .filter(r -> r.getToUserId().equals(app.getHelperId()))
                    .findFirst().orElse(null);

            // 感想是评分人自己的感受：
            // requesterRating = 相助方→求助方的评价，其 feedback 是相助方的感想
            // helperRating     = 求助方→相助方的评价，其 feedback 是求助方的感想
            List<Object> row = new ArrayList<>();
            row.add(helpRequest.getTitle() != null && !helpRequest.getTitle().isEmpty()
                    ? helpRequest.getTitle() : "（无标题）");
            row.add(requester != null ? UserFormatter.formatRoom(requester) : "（用户已删除）");
            row.add(requester != null ? requester.getName() : "（用户已删除）");
            row.add(requesterRating != null ? requesterRating.getScore() + "分" : "对方未评分");
            row.add(helperRating != null && helperRating.getFeedback() != null
                    ? helperRating.getFeedback() : "");                                         // 互助感想（求助人自己的感想）
            row.add(helper != null ? UserFormatter.formatRoom(helper) : "（用户已删除）");
            row.add(helper != null ? helper.getName() : "（用户已删除）");
            row.add(helperRating != null ? helperRating.getScore() + "分" : "对方未评分");
            row.add(requesterRating != null && requesterRating.getFeedback() != null
                    ? requesterRating.getFeedback() : "");                                      // 互助感想（相助人自己的感想）
            row.add(fmt(helpRequest.getCreatedAt()));
            row.add(app.getCompletedAt() != null
                    ? fmt(app.getCompletedAt()) : fmt(app.getUpdatedAt()));
            data.add(row);
        }

        // 按发布时间倒序（最新在最上面）
        data.sort((a, b) -> {
            String ta = (String) a.get(9);  // 发布时间在第 10 列（索引 9）
            String tb = (String) b.get(9);
            if (ta == null && tb == null) return 0;
            if (ta == null) return 1;
            if (tb == null) return -1;
            return tb.compareTo(ta);
        });

        return data;
    }

    // ──────────────────────────────────────────────
    // 内容下架记录 Sheet
    // ──────────────────────────────────────────────

    /** 内容下架记录 Sheet 表头 */
    private List<List<String>> removalsHead() {
        return Arrays.asList(
                Arrays.asList("操作时间"),
                Arrays.asList("管理员"),
                Arrays.asList("操作动作"),
                Arrays.asList("目标类型"),
                Arrays.asList("发布住户"),
                Arrays.asList("发布人姓名"),
                Arrays.asList("发布标题"),
                Arrays.asList("详情说明"),
                Arrays.asList("下架原因")
        );
    }

    /**
     * 构建内容下架记录数据：从 operation_logs 中筛选 action='remove_content' 的记录。
     *
     * <p>乐观锁上线前，同一内容的单次下架操作可能被重复记录，产生多条仅时间/原因略有差异的日志。
     * 通过时间窗口（60 秒）区分：间隔 ≤60s 的视为脏重复，仅保留最新一条；
     * 间隔 >60s 的视为两次独立的下架操作（如 AI 自动驳回后管理员再次下架），均予保留。</p>
     */
    private List<List<Object>> buildRemovalsData(Long tenantId, LocalDate startDate, LocalDate endDate) {
        // 去重阈值：同一内容 60 秒内的多条日志视为脏重复
        final long DEDUP_WINDOW_SECONDS = 60;

        List<OperationLog> logs = operationLogRepository.findAll().stream()
                .filter(l -> tenantMatches(tenantId, l.getTenantId()) && "remove_content".equals(l.getAction()))
                .filter(l -> inRange(l.getCreatedAt(), startDate, endDate))
                .sorted(Comparator.comparing(OperationLog::getCreatedAt))
                .collect(Collectors.toList());

        // 按 (targetType, targetId) 分组，时间窗口内去重
        Map<String, List<OperationLog>> grouped = new LinkedHashMap<>();
        for (OperationLog log : logs) {
            String key = log.getTargetType() + "_" + log.getTargetId();
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(log);
        }

        List<OperationLog> deduped = new ArrayList<>();
        for (List<OperationLog> group : grouped.values()) {
            OperationLog last = null;
            for (OperationLog log : group) {
                if (last == null) {
                    last = log;
                } else if (java.time.Duration.between(last.getCreatedAt(), log.getCreatedAt()).getSeconds() <= DEDUP_WINDOW_SECONDS) {
                    // 时间窗口内重复：用当前（更新）的替换上一个
                    last = log;
                } else {
                    // 间隔超过阈值：上一条落库，当前作为新起点
                    deduped.add(last);
                    last = log;
                }
            }
            if (last != null) {
                deduped.add(last);
            }
        }

        List<List<Object>> data = new ArrayList<>();
        for (OperationLog log : deduped) {
            User admin = userRepository.findById(log.getAdminId()).orElse(null);

            // 查找被下架内容的发布者信息和标题
            String publisherRoom = "";
            String publisherName = "";
            User publisher = findRemovedContentPublisher(log.getTargetType(), log.getTargetId());
            if (publisher != null) {
                publisherRoom = UserFormatter.formatRoom(publisher);
                publisherName = publisher.getName();
            }
            String contentTitle = findRemovedContentTitle(log.getTargetType(), log.getTargetId());
            String contentDescription = findRemovedContentDescription(log.getTargetType(), log.getTargetId());

            List<Object> row = new ArrayList<>();
            row.add(fmt(log.getCreatedAt()));
            row.add(admin != null ? admin.getName() : "");
            row.add(mapAction(log.getAction()));
            row.add(mapTargetType(log.getTargetType()));
            row.add(publisherRoom);
            row.add(publisherName);
            row.add(contentTitle);
            row.add(contentDescription);
            row.add(log.getDetail() != null ? log.getDetail() : "");
            data.add(row);
        }

        // 按操作时间倒序排列（最新在最上面）
        data.sort((a, b) -> {
            String ta = (String) a.get(0);
            String tb = (String) b.get(0);
            if (ta == null && tb == null) return 0;
            if (ta == null) return 1;
            if (tb == null) return -1;
            return tb.compareTo(ta);
        });

        return data;
    }

    /**
     * 根据下架操作日志中的 targetType 和 targetId 查找被下架内容的发布者。
     *
     * @param targetType 操作目标类型（idle / help）
     * @param targetId   操作目标 ID
     * @return 发布者用户实体，找不到时返回 null
     */
    private User findRemovedContentPublisher(String targetType, Long targetId) {
        if (targetId == null) return null;
        try {
            Long publisherId = null;
            if ("idle".equals(targetType)) {
                IdleItem item = idleItemRepository.findById(targetId).orElse(null);
                if (item != null) publisherId = item.getUserId();
            } else if ("help".equals(targetType)) {
                HelpRequest hr = helpRequestRepository.findById(targetId).orElse(null);
                if (hr != null) publisherId = hr.getUserId();
            }
            if (publisherId != null) {
                return userRepository.findById(publisherId).orElse(null);
            }
        } catch (Exception e) {
            log.debug("查找下架内容发布者失败 targetType={} targetId={}", targetType, targetId, e);
        }
        return null;
    }

    /**
     * 根据下架操作日志中的 targetType 和 targetId 查找被下架内容的标题。
     *
     * @param targetType 操作目标类型（idle / help）
     * @param targetId   操作目标 ID
     * @return 内容标题，找不到时返回空字符串
     */
    private String findRemovedContentTitle(String targetType, Long targetId) {
        if (targetId == null) return "";
        try {
            if ("idle".equals(targetType)) {
                IdleItem item = idleItemRepository.findById(targetId).orElse(null);
                if (item != null && item.getTitle() != null) return item.getTitle();
            } else if ("help".equals(targetType)) {
                HelpRequest hr = helpRequestRepository.findById(targetId).orElse(null);
                if (hr != null && hr.getTitle() != null) return hr.getTitle();
            }
        } catch (Exception e) {
            log.debug("查找下架内容标题失败 targetType={} targetId={}", targetType, targetId, e);
        }
        return "";
    }

    /**
     * 根据下架操作日志中的 targetType 和 targetId 查找被下架内容的详情说明。
     *
     * @param targetType 操作目标类型（idle / help）
     * @param targetId   操作目标 ID
     * @return 内容详情说明，找不到时返回空字符串
     */
    private String findRemovedContentDescription(String targetType, Long targetId) {
        if (targetId == null) return "";
        try {
            if ("idle".equals(targetType)) {
                IdleItem item = idleItemRepository.findById(targetId).orElse(null);
                if (item != null && item.getDescription() != null) return item.getDescription();
            } else if ("help".equals(targetType)) {
                HelpRequest hr = helpRequestRepository.findById(targetId).orElse(null);
                if (hr != null && hr.getDescription() != null) return hr.getDescription();
            }
        } catch (Exception e) {
            log.debug("查找下架内容详情说明失败 targetType={} targetId={}", targetType, targetId, e);
        }
        return "";
    }

    // ──────────────────────────────────────────────
    // 评分数据 Sheet
    // ──────────────────────────────────────────────

    /** 评分数据 Sheet 表头 */
    private List<List<String>> ratingsHead() {
        return Arrays.asList(
                Arrays.asList("评分住户"),
                Arrays.asList("评分人姓名"),
                Arrays.asList("被评住户"),
                Arrays.asList("被评人姓名"),
                Arrays.asList("综合评分"),
                Arrays.asList("互助感想"),
                Arrays.asList("评分时间")
        );
    }

    /**
     * 构建评分数据。
     */
    private List<List<Object>> buildRatingsData(Long tenantId, LocalDate startDate, LocalDate endDate) {
        List<List<Object>> data = new ArrayList<>();
        List<Rating> ratings = ratingRepository.findAll().stream()
                .filter(r -> {
                    if (r.getBorrowId() != null) {
                        BorrowRequest br = borrowRequestRepository.findById(r.getBorrowId()).orElse(null);
                        if (br != null) {
                            IdleItem idle = idleItemRepository.findById(br.getIdleId()).orElse(null);
                            return idle != null && tenantMatches(tenantId, idle.getTenantId());
                        }
                    }
                    if (r.getHelpApplicationId() != null) {
                        HelpApplication app = helpApplicationRepository.findById(r.getHelpApplicationId()).orElse(null);
                        if (app != null) {
                            HelpRequest hr = helpRequestRepository.findById(app.getHelpId()).orElse(null);
                            return hr != null && tenantMatches(tenantId, hr.getTenantId());
                        }
                    }
                    return false;
                })
                .collect(Collectors.toList());

        for (Rating r : ratings) {
            if (!inRange(r.getCreatedAt(), startDate, endDate)) continue;
            User fromUser = userRepository.findById(r.getFromUserId()).orElse(null);
            User toUser = userRepository.findById(r.getToUserId()).orElse(null);

            List<Object> row = new ArrayList<>();
            row.add(fromUser != null ? UserFormatter.formatRoom(fromUser) : "");
            row.add(fromUser != null ? fromUser.getName() : "");
            row.add(toUser != null ? UserFormatter.formatRoom(toUser) : "");
            row.add(toUser != null ? toUser.getName() : "");
            row.add(r.getScore() + "分");
            row.add(r.getFeedback() != null ? r.getFeedback() : "");
            row.add(fmt(r.getCreatedAt()));
            data.add(row);
        }

        // 按评分时间倒序排列（最新在最上面）
        data.sort((a, b) -> {
            String ta = (String) a.get(6);  // 评分时间在第 7 列（索引 6）
            String tb = (String) b.get(6);
            if (ta == null && tb == null) return 0;
            if (ta == null) return 1;
            if (tb == null) return -1;
            return tb.compareTo(ta);
        });

        return data;
    }

    // ──────────────────────────────────────────────
    // 导出日志
    // ──────────────────────────────────────────────

    /**
     * 记录本次导出操作到 export_logs 表。
     */
    private void saveExportLog(Long adminId, Long tenantId, ExportRequest req,
                               int[] counts, String fileName) {
        ExportLog log = ExportLog.builder()
                .adminId(adminId)
                .tenantId(tenantId)
                .exportFormat(req.getFormat() != null ? req.getFormat() : "xlsx")
                .selectedOptions(String.join(",", req.getOptions()))
                .dateRangeStart(req.getDateStart())
                .dateRangeEnd(req.getDateEnd())
                .residentsCount(counts[0])
                .postsCount(counts[1])
                .borrowsCount(counts[2])
                .helpsCount(counts[3])
                .removalsCount(counts[4])
                .ratingsCount(counts[5])
                .fileName(fileName)
                .build();
        exportLogRepository.save(log);
    }

    /**
     * 查询导出日志（按时间倒序，一次性返回全部）。
     *
     * @param adminId 当前管理员ID
     * @return 全部导出日志 DTO 列表
     */
    public List<ExportLogDTO> getExportLogs(Long adminId) {
        User caller = findAdmin(adminId);
        requireSeniorAdmin(caller);
        Long tenantId = getAdminTenantId(adminId);
        // super_admin 查看全部导出记录，senior_admin 仅查看本小区
        List<ExportLog> allLogs = tenantId != null
                ? exportLogRepository.findByTenantIdOrderByCreatedAtDesc(tenantId)
                : exportLogRepository.findAll().stream()
                        .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                        .collect(Collectors.toList());

        // 导出选项的中文映射
        Map<String, String> optionLabels = Map.of(
            "residents", "住户",
            "posts", "发布",
            "borrows", "互借",
            "helps", "互助",
            "removals", "下架",
            "ratings", "评分"
        );

        List<ExportLogDTO> dtos = new ArrayList<>();
        for (ExportLog log : allLogs) {
            User admin = userRepository.findById(log.getAdminId()).orElse(null);
            // senior_admin 不展示 super_admin 的导出记录
            if (tenantId != null && admin != null && UserType.SUPER_ADMIN.equals(admin.getUserType())) {
                continue;
            }
            // 将英文选项键转为中文标签
            String rawOptions = log.getSelectedOptions();
            String optionsChinese = rawOptions;
            if (rawOptions != null && !rawOptions.isEmpty()) {
                String[] keys = rawOptions.split(",");
                optionsChinese = java.util.Arrays.stream(keys)
                        .map(k -> optionLabels.getOrDefault(k.trim(), k.trim()))
                        .collect(Collectors.joining("、"));
            }
            StringBuilder sb = new StringBuilder();
            if (log.getResidentsCount() > 0) sb.append("住户:").append(log.getResidentsCount()).append(" ");
            if (log.getPostsCount() > 0) sb.append("发布:").append(log.getPostsCount()).append(" ");
            if (log.getBorrowsCount() > 0) sb.append("互借:").append(log.getBorrowsCount()).append(" ");
            if (log.getHelpsCount() != null && log.getHelpsCount() > 0) sb.append("互助:").append(log.getHelpsCount()).append(" ");
            if (log.getRemovalsCount() > 0) sb.append("下架:").append(log.getRemovalsCount()).append(" ");
            if (log.getRatingsCount() > 0) sb.append("评分:").append(log.getRatingsCount());
            dtos.add(ExportLogDTO.builder()
                    .id(log.getId())
                    .adminName(admin != null ? admin.getName() : "未知")
                    .createdAt(log.getCreatedAt())
                    .exportFormat(log.getExportFormat())
                    .selectedOptions(optionsChinese)
                    .countSummary(sb.toString().trim())
                    .fileName(log.getFileName())
                    .build());
        }

        // 一次性返回全部导出日志
        return dtos;
    }

    // ──────────────────────────────────────────────
    // 导出辅助方法
    // ──────────────────────────────────────────────

    /** 判断选项列表中是否包含指定项目 */
    private boolean contains(List<String> options, String key) {
        return options != null && options.contains(key);
    }

    /** 解析日期字符串为 LocalDate，无效或为空时返回 null */
    private LocalDate parseDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) return null;
        try {
            return LocalDate.parse(dateStr);
        } catch (Exception e) {
            return null;
        }
    }

    /** 判断时间戳是否在起止日期范围内（闭区间） */
    private boolean inRange(LocalDateTime time, LocalDate start, LocalDate end) {
        if (time == null) return true;
        if (start != null && time.toLocalDate().isBefore(start)) return false;
        if (end != null && !time.toLocalDate().isBefore(end)) return false;  // end 已 +1 天
        return true;
    }


    /** 闲置物品状态的中文映射 */
    /** 闲置物品状态的中文映射（统一五类：在线中/待审批/进行中/已完成/已下架） */
    private String mapIdleStatus(String status) {
        if (status == null) return "";
        return switch (status) {
            case BizStatus.ONLINE              -> "在线中";
            case BizStatus.PENDING             -> "待审批";
            case BizStatus.ACTIVE              -> "进行中";
            case BizStatus.COMPLETED           -> "已完成";
            case BizStatus.OFFLINE -> "已下架";
            // 兼容存量数据中的旧值（迁移后不再出现，兜底）
            case "reserved", "borrowing"       -> "进行中";
            default                            -> "";
        };
    }

    /** 技能求助状态的中文映射（统一五类：在线中/待审批/进行中/已完成/已下架） */
    private String mapHelpStatus(String status) {
        if (status == null) return "";
        return switch (status) {
            case BizStatus.ONLINE              -> "在线中";
            case BizStatus.PENDING             -> "待审批";
            case BizStatus.ACTIVE              -> "进行中";
            case BizStatus.COMPLETED           -> "已完成";
            case BizStatus.OFFLINE -> "已下架";
            // 兼容存量数据中的旧值（迁移后不再出现，兜底）
            case "reserved", "helping"         -> "进行中";
            default                            -> "";
        };
    }

    /** 借用记录状态的中文映射 */
    private String mapBorrowStatus(String status) {
        if (status == null) return "";
        return switch (status) {
            case BizStatus.PENDING -> "待确认";
            case BizStatus.APPROVED -> "已同意";
            case BizStatus.ACTIVE -> "进行中";
            case BizStatus.RETURNED -> "已归还";
            case BizStatus.REJECTED -> "已拒绝";
            default -> "";
        };
    }


    /** 归还状态的中文映射（对齐 C端：ontime/delayed/not_returned），兼容历史遗留值 */
    private String mapReturnStatus(String returnStatus) {
        if (returnStatus == null) return "";
        return switch (returnStatus) {
            // 实际 C端值（return-detail 页面发送）
            case ReturnStatus.ON_TIME      -> "按时归还";
            case ReturnStatus.DELAYED      -> "逾期归还";
            case ReturnStatus.NOT_RETURNED -> "未归还";
            // 兼容 my-posts 硬编码值（存量迁移已转为 ontime，此处兜底）
            case "normal"                  -> "正常归还";
            // 兼容 schema 设计遗留值（存量迁移已处理，此处兜底）
            case "perfect"                 -> "完好";
            case "damaged"                 -> "有损坏";
            case "lost"                    -> "丢失";
            default                        -> returnStatus;
        };
    }

    /** 下架原因的中文映射（兼容历史数据中遗留的英文值） */
    private String mapDelistReason(String reason) {
        if (reason == null) return "";
        return switch (reason) {
            case "violation" -> "违规下架";
            case "违规下架" -> "违规下架";
            default -> reason;
        };
    }

    /** 操作动作的中文映射 */
    private String mapAction(String action) {
        if (action == null) return "";
        return switch (action) {
            case "approve_user" -> "审核通过";
            case "reject_user" -> "审核拒绝";
            case "remove_content" -> "内容下架";
            case "proxy_publish_idle" -> "代发闲置";
            case "proxy_publish_help" -> "代发求助";
            case "create_admin" -> "创建管理员";
            case "delete_admin" -> "删除管理员";
            default -> "";
        };
    }

    /** 操作对象类型的中文映射 */
    private String mapTargetType(String targetType) {
        if (targetType == null) return "";
        return switch (targetType) {
            case "idle" -> "闲置物品";
            case "help" -> "技能求助";
            case "user" -> "住户";
            default -> "";
        };
    }

    // ==================== 楼栋 ====================

    /**
     * 获取管理员所属小区的全部楼栋名称。
     */
    public List<BuildingDTO> getBuildings(Long adminId) {
        Long tenantId = getAdminTenantId(adminId);
        List<Building> buildings = (tenantId != null ? buildingRepository.findByTenantId(tenantId) : buildingRepository.findAll());
        return buildings.stream()
                .map(BuildingDTO::from)
                .collect(Collectors.toList());
    }

    // ==================== 小区数据 ====================

    /**
     * 获取管理员所属小区的嵌套小区数据（小区 + 楼栋 + 单元）。
     * 供前端在登录后缓存使用。
     */
    /**
     * 获取所有小区列表，供 super_admin 创建管理员时选择目标小区。
     */
    public List<TenantDTO> getAllTenants() {
        return tenantRepository.findAll().stream()
                .map(TenantDTO::from)
                .collect(Collectors.toList());
    }

    public CommunityDTO getCommunityData(Long adminId) {
        Long tenantId = getAdminTenantId(adminId);

        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new BizException("小区不存在"));

        List<Building> buildings = (tenantId != null ? buildingRepository.findByTenantId(tenantId) : buildingRepository.findAll());
        // 按楼栋号排序（数值直接排，不再从字符串抽数字）
        buildings.sort(Comparator.comparingInt(Building::getBuildingNo));

        List<CommunityBuildingDTO> buildingList = new ArrayList<>();
        for (Building building : buildings) {
            List<Unit> units = unitRepository.findByBuildingId(building.getId());
            units.sort(Comparator.comparingInt(Unit::getUnitNo));
            List<CommunityUnitDTO> unitList = units.stream()
                    .map(u -> CommunityUnitDTO.builder()
                            .id(u.getId())
                            .unitNo(u.getUnitNo())
                            .build())
                    .collect(Collectors.toList());
            buildingList.add(CommunityBuildingDTO.builder()
                    .id(building.getId())
                    .buildingNo(building.getBuildingNo())
                    .units(unitList)
                    .build());
        }

        return CommunityDTO.builder()
                .tenantName(tenant.getName())
                .buildings(buildingList)
                .build();
    }

    // ==================== 个人资料与密码 ====================

    public ProfileResponseDTO updateProfile(Long adminId, String name) {
        User admin = findAdmin(adminId);
        if (name != null && !name.trim().isEmpty()) {
            admin.setName(name.trim());
            userRepository.save(admin);
        }
        return ProfileResponseDTO.builder()
                .name(admin.getName())
                .userType(admin.getUserType())
                .build();
    }

    public void updatePassword(Long adminId, String oldPassword, String newPassword) {
        User admin = findAdmin(adminId);
        if (admin.getPasswordHash() == null
                || !passwordEncoder.matches(oldPassword, admin.getPasswordHash())) {
            throw new BizException("当前密码错误");
        }
        if (newPassword == null || newPassword.length() < 6) {
            throw new BizException("新密码长度不能少于6位");
        }
        admin.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(admin);
    }

    // ==================== 管理员账号管理 ====================

    /**
     * 列出同小区下的全部管理员用户。仅 super_admin 可调用。
     */
    public List<AdminDTO> getAdmins(Long adminId) {
        User admin = findAdmin(adminId);
        requireSeniorAdmin(admin);
        Long tenantId = getAdminTenantId(adminId);

        // super_admin（tenantId=null）查全部小区的管理员，senior_admin 仅查本小区
        List<User> admins = tenantId != null
                ? userRepository.findByTenantIdAndUserTypeIn(tenantId,
                        Arrays.asList(UserType.SUPER_ADMIN, UserType.SENIOR_ADMIN, UserType.ADMIN))
                : userRepository.findByUserTypeIn(
                        Arrays.asList(UserType.SUPER_ADMIN, UserType.SENIOR_ADMIN, UserType.ADMIN));

        // 预取所有小区名称映射，避免 N+1 查询
        Map<Long, String> tenantNameMap = tenantRepository.findAll().stream()
                .collect(Collectors.toMap(Tenant::getId, Tenant::getName));

        return admins.stream()
                .sorted(Comparator.comparing(User::getCreatedAt))
                .map(u -> AdminDTO.builder()
                        .id(u.getId())
                        .name(u.getName())
                        .userType(u.getUserType())
                        .userTypeLabel(
                                UserType.SUPER_ADMIN.equals(u.getUserType()) ? "超级管理员" :
                                UserType.SENIOR_ADMIN.equals(u.getUserType()) ? "高级管理员" : "普通管理员")
                        .tenantName(u.getTenantId() != null
                                ? tenantNameMap.getOrDefault(u.getTenantId(), "—") : "全部小区")
                        .phone(maskPhone(u.getPhone()))
                        .createdAt(u.getCreatedAt())
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * 创建子管理员账号。仅 super_admin 可调用。
     */
    @Transactional
    public AdminDTO createAdmin(Long adminId, String name, String phone, String password,
                                Long targetTenantId, String userType) {
        User creator = findAdmin(adminId);
        requireSuperAdmin(creator);

        if (targetTenantId == null) {
            throw new BizException("请选择目标小区");
        }
        // 默认普通管理员，仅 super_admin 可创建 senior_admin
        String resolvedType = (userType != null && !userType.isEmpty()) ? userType : UserType.ADMIN;
        if (!UserType.ADMIN.equals(resolvedType) && !UserType.SENIOR_ADMIN.equals(resolvedType)) {
            throw new BizException("无效的管理员类型");
        }
        if (name == null || name.trim().isEmpty()) {
            throw new BizException("姓名不能为空");
        }
        if (phone == null || phone.trim().isEmpty()) {
            throw new BizException("手机号不能为空");
        }
        if (password == null || password.length() < 6) {
            throw new BizException("密码长度不能少于6位");
        }

        // 校验手机号在小区内唯一
        userRepository.findByPhoneAndTenantId(phone.trim(), targetTenantId)
                .ifPresent(u -> { throw new BizException("该手机号已存在"); });

        String username = phone.trim();
        User newAdmin = User.builder()
                .username(username)
                .passwordHash(passwordEncoder.encode(password))
                .name(name.trim())
                .phone(phone.trim())
                .userType(resolvedType)
                .tenantId(targetTenantId)
                .authStatus(BizStatus.APPROVED)
                .build();
        newAdmin = userRepository.save(newAdmin);

        OperationLog log = new OperationLog();
        log.setAdminId(adminId);
        log.setTenantId(targetTenantId);
        log.setAction("create_admin");
        log.setTargetType("user");
        log.setTargetId(newAdmin.getId());
        log.setDetail("创建子账号：" + name);
        log.setCreatedAt(LocalDateTime.now());
        operationLogRepository.save(log);

        return AdminDTO.builder()
                .id(newAdmin.getId())
                .name(newAdmin.getName())
                .userType(newAdmin.getUserType())
                .phone(maskPhone(newAdmin.getPhone()))
                .createdAt(newAdmin.getCreatedAt())
                .build();
    }

    /**
     * 删除子管理员账号。仅 super_admin 可调用，且不能删除自己。
     */
    @Transactional
    public void deleteAdmin(Long adminId, Long targetId) {
        User admin = findAdmin(adminId);
        requireSuperAdmin(admin);

        if (adminId.equals(targetId)) {
            throw new BizException("不能删除自己");
        }

        User target = findAdmin(targetId);
        Long tenantId = getAdminTenantId(adminId);
        if (!tenantMatches(tenantId, target.getTenantId())) {
            throw new BizException("该管理员不属于当前小区");
        }

        if (UserType.SUPER_ADMIN.equals(target.getUserType())) {
            throw new BizException("不能删除超级管理员");
        }

        userRepository.delete(target);

        OperationLog log = new OperationLog();
        log.setAdminId(adminId);
        log.setTenantId(tenantId);
        log.setAction("delete_admin");
        log.setTargetType("user");
        log.setTargetId(targetId);
        log.setDetail("删除子账号：" + target.getName());
        log.setCreatedAt(LocalDateTime.now());
        operationLogRepository.save(log);
    }

    // ==================== 私有辅助方法：管理员鉴权 ====================

    /**
     * 校验当前管理员是否为超级管理员（仅 super_admin 模块的公开权限入口）。
     *
     * <p>敏感词管理等平台级配置模块复用本入口：按现有模式先 findAdmin 再 requireSuperAdmin，
     * 非 super_admin 一律拒绝（业务异常「权限不足，仅超级管理员可操作」，403 语义）。</p>
     *
     * @param adminId 管理员用户 ID
     * @throws RuntimeException 账号不存在或非超级管理员时抛出
     */
    public void requireSuperAdmin(Long adminId) {
        requireSuperAdmin(findAdmin(adminId));
    }

    private User findAdmin(Long adminId) {
        return userRepository.findById(adminId)
                .orElseThrow(() -> new BizException("管理员不存在"));
    }

    /**
     * 获取管理员所属小区名称，用于导出文件命名等场景。
     * @param adminId 管理员用户ID
     * @return 小区名称，找不到时返回 "community"
     */
    public String getTenantName(Long adminId) {
        User admin = findAdmin(adminId);
        Long tenantId = admin.getTenantId();
        if (tenantId != null) {
            Tenant tenant = tenantRepository.findById(tenantId).orElse(null);
            if (tenant != null) return tenant.getName();
        }
        return "community";
    }

    /**
     * 获取管理员对应的小区 ID。
     * super_admin 返回 null（平台级视角，可查看全部小区数据），
     * 普通 admin 返回其绑定的 tenantId。
     */
    private Long getAdminTenantId(Long adminId) {
        User admin = findAdmin(adminId);
        if (UserType.SUPER_ADMIN.equals(admin.getUserType())) {
            return null;  // 平台级视角
        }
        Long tenantId = admin.getTenantId();
        if (tenantId == null) {
            throw new BizException("管理员未关联小区");
        }
        return tenantId;
    }

    /**
     * 判断目标 tenantId 是否在管理员管辖范围内。
     * adminTenantId 为 null（super_admin）时匹配全部小区。
     */
    private boolean tenantMatches(Long adminTenantId, Long targetTenantId) {
        return adminTenantId == null || adminTenantId.equals(targetTenantId);
    }

    private void requireSuperAdmin(User admin) {
        if (!UserType.SUPER_ADMIN.equals(admin.getUserType())) {
            throw new BizException("权限不足，仅超级管理员可操作");
        }
    }

    /**
     * 要求 senior_admin 及以上权限（super_admin 或 senior_admin）。
     * 用于数据导入、系统设置、管理员列表、操作日志等高级功能。
     */
    private void requireSeniorAdmin(User admin) {
        if (!UserType.SUPER_ADMIN.equals(admin.getUserType())
                && !UserType.SENIOR_ADMIN.equals(admin.getUserType())) {
            throw new BizException("权限不足，仅高级管理员及以上可操作");
        }
    }

    /**
     * 禁止超级管理员访问业务数据。
     * super_admin 仅管理平台本身（管理员账号、操作日志、系统设置），不接触小区业务数据。
     */
    private void requireNotSuperAdmin(User admin) {
        if (UserType.SUPER_ADMIN.equals(admin.getUserType())) {
            throw new BizException("超级管理员无权查看业务数据，仅可管理系统设置");
        }
    }

    // ==================== 私有辅助方法：格式化 ====================

    /**
     * 按条件搜索住户，一次性返回全部匹配住户（按创建时间倒序）。
     *
     * @param adminId   当前管理员 ID
     * @param buildingNo 楼栋号筛选（数值精确匹配）
     * @param unitNo     单元号筛选（数值精确匹配）
     * @param room     房间号筛选（模糊匹配）
     * @param userType "业主" | "租客" | null
     * @param keyword  姓名或手机号关键词
     * @return 全部匹配住户 DTO 列表
     */
    public List<ResidentDTO> searchResidents(Long adminId, Integer buildingNo, Integer unitNo, String room,
                                             String userType, String keyword) {
        requireNotSuperAdmin(findAdmin(adminId));
        Long tenantId = getAdminTenantId(adminId);
        // 将前端英文编码转换为数据库中的中文值
        String dbUserType = switch (userType != null ? userType : "") {
            case "owner"  -> "业主";
            case "tenant" -> "租客";
            default       -> userType;
        };
        List<User> users = userRepository.findResidentsAll(tenantId, buildingNo, unitNo, room, dbUserType, keyword);

        return users.stream()
                .map(u -> ResidentDTO.builder()
                        .id(u.getId())
                        .name(maskName(u.getName()))
                        .room(UserFormatter.formatRoom(u))
                        .userType(UserFormatter.getUserTypeLabel(u.getUserType()))
                        .phone(maskPhone(u.getPhone()))
                        .build())
                .collect(Collectors.toList());
    }

    // ==================== 私有辅助方法：DTO 转换 ====================

    private UserDTO toUserDTO(User user) {
        java.util.List<String> docImages = java.util.Collections.emptyList();
        if (user.getDocImages() != null && !user.getDocImages().isEmpty()) {
            try {
                docImages = OBJECT_MAPPER.readValue(user.getDocImages(), new TypeReference<java.util.List<String>>() {});
            } catch (Exception e) {
                log.debug("解析用户证件图片JSON失败 userId={}", user.getId(), e);
            }
        }

        return UserDTO.builder()
                .id(user.getId())
                .openid(user.getOpenid())
                .username(user.getUsername())
                .name(user.getName())
                .phone(user.getPhone())
                .userType(user.getUserType())
                .authStatus(user.getAuthStatus())
                .roomId(user.getRoom() != null ? user.getRoom().getId() : null)
                .userRoom(UserFormatter.formatRoom(user))
                .tenantName(resolveTenantName(user))
                .docImages(docImages)
                .rejectReason(user.getRejectReason())
                .bannedReason(user.getBannedReason())
                .createdAt(user.getCreatedAt())
                .build();
    }

    private String resolveTenantName(User user) {
        try {
            if (user.getRoom() != null
                    && user.getRoom().getUnit() != null
                    && user.getRoom().getUnit().getBuilding() != null) {
                Long tenantId = user.getRoom().getUnit().getBuilding().getTenantId();
                if (tenantId != null) {
                    return tenantRepository.findById(tenantId)
                            .map(t -> t.getName())
                            .orElse("");
                }
            }
        } catch (Exception e) {
            log.debug("解析租户名称失败", e);
        }
        return "";
    }

    private OperationLogDTO toOperationLogDTO(OperationLog log) {
        User admin = userRepository.findById(log.getAdminId()).orElse(null);
        return OperationLogDTO.builder()
                .id(log.getId())
                .adminId(log.getAdminId())
                .adminName(admin != null ? admin.getName() : "未知管理员")
                .action(log.getAction())
                .targetType(log.getTargetType())
                .targetId(log.getTargetId())
                .detail(log.getDetail())
                .createdAt(log.getCreatedAt())
                .build();
    }

    // ==================== 私有辅助方法：内容转换 ====================

    /**
     * 将 IdleItem 实体转换为包含完整对方/评价/违规信息的 ContentItemDTO。
     */
    /**
     * 将 IdleItem 实体转换为包含完整对方/评价/违规信息的 ContentItemDTO。
     */
    private ContentItemDTO toContentItemDTO(IdleItem item) {
        User user = userRepository.findById(item.getUserId()).orElse(null);

        ContentItemDTO.ContentItemDTOBuilder builder = ContentItemDTO.builder()
                .id(item.getId())
                .type("idle")
                .postType(item.getPostType())
                .title(item.getTitle())
                .description(item.getDescription())
                .images(parseImages(item.getImages()))
                .category(item.getCategory())
                .price(item.getPrice())
                .condition(item.getCondition())
                .publisherName(user != null ? user.getName() : "未知用户")
                .publisherRoom(user != null ? UserFormatter.formatRoomWithType(user) : "")
                .displayStatus(displayStatus(item.getStatus()))
                .rawStatus(item.getStatus())
                .isProxy(item.getIsProxy())
                .createdAt(item.getCreatedAt())
                .maxDuration(item.getMaxDuration())
                .durationUnit(item.getDurationUnit())
                .applicantName(user != null ? user.getName() : "未知用户")
                .building(user != null ? getBuildingName(user) : null)
                .moderationStatus(item.getModerationStatus())
                .delistReason(item.getDelistReason())
                .reviewedByName(getUserName(item.getReviewedBy()))
                .updatedAt(item.getUpdatedAt());

        // 根据状态填充对方信息、评价和时间线
        if (BizStatus.ACTIVE.equals(item.getStatus())) {
            fillBorrowingPeerInfo(item, builder);
        } else if (BizStatus.COMPLETED.equals(item.getStatus())) {
            fillCompletedPeerInfo(item, builder);
        }

        return builder.build();
    }

    /**
     * 填充借用中状态的对方信息和时间范围到 builder。
     */
    private void fillBorrowingPeerInfo(IdleItem item, ContentItemDTO.ContentItemDTOBuilder builder) {
        BorrowRequest activeBorrow = findActiveBorrowRequest(item.getId());
        if (activeBorrow == null) {
            return;
        }
        User borrower = userRepository.findById(activeBorrow.getBorrowerId()).orElse(null);
        if (borrower != null) {
            builder.peerName(borrower.getName());
            builder.peerRoom(UserFormatter.formatRoomWithType(borrower));
        }
        if (activeBorrow.getStartDate() != null) {
            builder.timeStart(activeBorrow.getStartDate().atStartOfDay());
            if (activeBorrow.getDurationDays() != null) {
                builder.timeEnd(activeBorrow.getStartDate().plusDays(activeBorrow.getDurationDays()).atStartOfDay());
            }
        }
    }

    /**
     * 填充已完成状态的对方信息、评价和时间线到 builder。
     */
    private void fillCompletedPeerInfo(IdleItem item, ContentItemDTO.ContentItemDTOBuilder builder) {
        BorrowRequest completedBorrow = findCompletedBorrowRequest(item.getId());
        if (completedBorrow == null) {
            return;
        }
        User borrower = userRepository.findById(completedBorrow.getBorrowerId()).orElse(null);
        if (borrower != null) {
            builder.peerName(borrower.getName());
            builder.peerRoom(UserFormatter.formatRoomWithType(borrower));
            builder.peerRating(ratingRepository.getAverageScore(borrower.getId()));
        }

        // 时间范围
        LocalDateTime timeStart = null;
        if (completedBorrow.getStartDate() != null) {
            timeStart = completedBorrow.getStartDate().atStartOfDay();
            builder.timeStart(timeStart);
            if (completedBorrow.getDurationDays() != null) {
                builder.timeEnd(completedBorrow.getStartDate().plusDays(completedBorrow.getDurationDays()).atStartOfDay());
            }
        }

        // 发布者评价：借入方对物主的评价
        Rating pubRating = ratingRepository
                .findFirstByBorrowIdAndFromUserId(completedBorrow.getId(), completedBorrow.getBorrowerId())
                .orElse(null);
        if (pubRating != null) {
            builder.publisherRatingScore((double) pubRating.getScore());
            builder.publisherRatingStars(scoreToStars(pubRating.getScore()));
            builder.publisherRatingComment(null);
        }

        // 对方评价：物主对借入方的评价
        Rating peerR = ratingRepository
                .findFirstByBorrowIdAndFromUserId(completedBorrow.getId(), item.getUserId())
                .orElse(null);
        if (peerR != null) {
            builder.peerRatingScore((double) peerR.getScore());
            builder.peerRatingStars(scoreToStars(peerR.getScore()));
            builder.peerRatingComment(null);
        }

        // 时间线：借入申请 → 同意借出 → 已完成
        builder.applyAt(completedBorrow.getCreatedAt());
        builder.approveAt(timeStart != null ? timeStart : completedBorrow.getCreatedAt());
        builder.completeAt(completedBorrow.getReturnedAt());
    }

    /**
     * 将 HelpRequest 实体转换为包含完整对方/评价/违规信息的 ContentItemDTO。
     */
    private ContentItemDTO toContentItemDTO(HelpRequest item) {
        User user = userRepository.findById(item.getUserId()).orElse(null);

        ContentItemDTO.ContentItemDTOBuilder builder = ContentItemDTO.builder()
                .id(item.getId())
                .type("help")
                .postType("HELP")
                .title(item.getTitle())
                .description(item.getDescription())
                .images(parseImages(item.getImages()))
                .category(item.getCategory())
                .publisherName(user != null ? user.getName() : "未知用户")
                .publisherRoom(user != null ? UserFormatter.formatRoomWithType(user) : "")
                .displayStatus(displayStatus(item.getStatus()))
                .rawStatus(item.getStatus())
                .isProxy(item.getIsProxy())
                .isUrgent(item.getIsUrgent())
                .createdAt(item.getCreatedAt())
                .timeStart(item.getTimeStart())
                .timeEnd(item.getTimeEnd())
                .applicantName(user != null ? user.getName() : "未知用户")
                .building(user != null ? getBuildingName(user) : null)
                .moderationStatus(item.getModerationStatus())
                .delistReason(item.getDelistReason())
                .reviewedByName(getUserName(item.getReviewedBy()))
                .updatedAt(item.getUpdatedAt());

        // 根据状态填充对方信息、评价和时间线
        if (BizStatus.ACTIVE.equals(item.getStatus())) {
            fillHelpingPeerInfo(item, builder);
        } else if (BizStatus.COMPLETED.equals(item.getStatus())) {
            fillHelpCompletedPeerInfo(item, builder);
        }

        return builder.build();
    }

    /**
     * 填充帮助中状态的对方信息到 builder。
     */
    private void fillHelpingPeerInfo(HelpRequest item, ContentItemDTO.ContentItemDTOBuilder builder) {
        HelpApplication activeApp = findActiveHelpApplication(item.getId());
        if (activeApp == null) {
            return;
        }
        User helper = userRepository.findById(activeApp.getHelperId()).orElse(null);
        if (helper != null) {
            builder.peerName(helper.getName());
            builder.peerRoom(UserFormatter.formatRoomWithType(helper));
        }
    }

    /**
     * 填充帮助完成状态的对方信息、评价和时间线到 builder。
     */
    private void fillHelpCompletedPeerInfo(HelpRequest item, ContentItemDTO.ContentItemDTOBuilder builder) {
        HelpApplication completedApp = findCompletedHelpApplication(item.getId());
        if (completedApp == null) {
            return;
        }
        User helper = userRepository.findById(completedApp.getHelperId()).orElse(null);
        if (helper != null) {
            builder.peerName(helper.getName());
            builder.peerRoom(UserFormatter.formatRoomWithType(helper));
            builder.peerRating(ratingRepository.getAverageScore(helper.getId()));
        }

        // 发布者评价：帮助者对求助者的评价
        Rating pubRating = ratingRepository
                .findFirstByHelpApplicationIdAndFromUserId(completedApp.getId(), completedApp.getHelperId())
                .orElse(null);
        if (pubRating != null) {
            builder.publisherRatingScore((double) pubRating.getScore());
            builder.publisherRatingStars(scoreToStars(pubRating.getScore()));
            builder.publisherRatingComment(null);
        }

        // 对方评价：求助者对帮助者的评价
        Rating peerR = ratingRepository
                .findFirstByHelpApplicationIdAndFromUserId(completedApp.getId(), item.getUserId())
                .orElse(null);
        if (peerR != null) {
            builder.peerRatingScore((double) peerR.getScore());
            builder.peerRatingStars(scoreToStars(peerR.getScore()));
            builder.peerRatingComment(null);
        }

        // 时间线：帮忙申请 → 同意帮忙 → 已完成
        builder.applyAt(completedApp.getCreatedAt());
        builder.approveAt(item.getTimeStart() != null ? item.getTimeStart() : completedApp.getCreatedAt());
        builder.completeAt(completedApp.getCompletedAt());
    }

    // ==================== 私有辅助方法：数据查询 ====================

    /**
     * 将 JSON 数组格式的图片字符串解析为 URL 列表。
     * 值为 null/空白或非法 JSON 时返回空列表。
     * 同时过滤掉解析结果中的空白/空 URL。
     */
    private List<String> parseImages(String imagesJson) {
        if (imagesJson == null || imagesJson.isBlank()) {
            return java.util.Collections.emptyList();
        }
        try {
            List<String> urls = OBJECT_MAPPER.readValue(imagesJson, new TypeReference<List<String>>() {});
            return urls.stream()
                    .filter(url -> url != null && !url.isBlank())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            return java.util.Collections.emptyList();
        }
    }

    /**
     * 按状态、楼栋用户 ID 和搜索关键词筛选查询闲置物品。
     */
    private List<IdleItem> fetchIdleItems(List<String> statuses, List<Long> buildingUserIds, Long tenantId, String search) {
        List<IdleItem> allItems = new ArrayList<>();
        for (String status : statuses) {
            allItems.addAll(idleItemRepository.findByStatus(status));
        }

        // 应用租户过滤
        allItems = allItems.stream()
                .filter(i -> tenantMatches(tenantId, i.getTenantId()))
                .collect(Collectors.toList());

        // 应用楼栋过滤
        if (buildingUserIds != null) {
            allItems = allItems.stream()
                    .filter(i -> buildingUserIds.contains(i.getUserId()))
                    .collect(Collectors.toList());
        }

        // 应用搜索过滤
        if (search != null && !search.isEmpty()) {
            String keyword = search.toLowerCase();
            allItems = allItems.stream()
                    .filter(i -> i.getTitle() != null && i.getTitle().toLowerCase().contains(keyword))
                    .collect(Collectors.toList());
        }

        return allItems;
    }

    /**
     * 按状态、楼栋用户 ID 和搜索关键词筛选查询求助信息。
     */
    private List<HelpRequest> fetchHelpItems(List<String> statuses, List<Long> buildingUserIds, Long tenantId, String search) {
        List<HelpRequest> allItems = new ArrayList<>();
        for (String status : statuses) {
            allItems.addAll(helpRequestRepository.findByStatus(status));
        }

        // 应用租户过滤
        allItems = allItems.stream()
                .filter(h -> tenantMatches(tenantId, h.getTenantId()))
                .collect(Collectors.toList());

        // 应用楼栋过滤
        if (buildingUserIds != null) {
            allItems = allItems.stream()
                    .filter(h -> buildingUserIds.contains(h.getUserId()))
                    .collect(Collectors.toList());
        }

        // 应用搜索过滤
        if (search != null && !search.isEmpty()) {
            String keyword = search.toLowerCase();
            allItems = allItems.stream()
                    .filter(h -> h.getTitle() != null && h.getTitle().toLowerCase().contains(keyword))
                    .collect(Collectors.toList());
        }

        return allItems;
    }

    // ==================== 待审批 tab：borrow_requests / help_applications ====================

    /**
     * 获取该租户下所有待审批的借用申请，转换为 ContentItemDTO。
     * 数据来源为 borrow_requests（status=pending），与 C端「管理页→审批」一致。
     */
    private List<ContentItemDTO> fetchPendingBorrowItems(Long tenantId, List<Long> buildingUserIds, String search) {
        List<ContentItemDTO> result = new ArrayList<>();
        List<BorrowRequest> pendingBorrows = borrowRequestRepository.findByStatus(BizStatus.PENDING);
        for (BorrowRequest br : pendingBorrows) {
            IdleItem idleItem = idleItemRepository.findById(br.getIdleId()).orElse(null);
            if (idleItem == null || !tenantMatches(tenantId, idleItem.getTenantId())) {
                continue;
            }
            // 楼栋筛选
            if (buildingUserIds != null && !buildingUserIds.contains(idleItem.getUserId())) {
                continue;
            }
            // 搜索筛选
            if (search != null && !search.isEmpty()) {
                String keyword = search.toLowerCase();
                if (idleItem.getTitle() == null || !idleItem.getTitle().toLowerCase().contains(keyword)) {
                    continue;
                }
            }
            User owner = userRepository.findById(idleItem.getUserId()).orElse(null);
            User borrower = userRepository.findById(br.getBorrowerId()).orElse(null);
            result.add(ContentItemDTO.builder()
                    .id(idleItem.getId())
                    .type("idle")
                    .title(idleItem.getTitle())
                    .description(idleItem.getDescription())
                    .images(parseImages(idleItem.getImages()))
                    .category(idleItem.getCategory())
                    .publisherName(owner != null ? owner.getName() : "未知用户")
                    .publisherRoom(owner != null ? UserFormatter.formatRoomWithType(owner) : "")
                    .displayStatus("待审批")
                    .rawStatus(BizStatus.PENDING)
                    .isProxy(idleItem.getIsProxy())
                    .createdAt(br.getCreatedAt())
                    .maxDuration(idleItem.getMaxDuration())
                    .durationUnit(idleItem.getDurationUnit())
                    .approverName(owner != null ? UserFormatter.formatRoomWithType(owner) : "未知用户")
                    .applicantName(borrower != null ? UserFormatter.formatRoomWithType(borrower) : "未知用户")
                    .building(owner != null ? getBuildingName(owner) : null)
                    .build());
        }
        return result;
    }

    /**
     * 获取该租户下所有待审批的帮助申请，转换为 ContentItemDTO。
     * 数据来源为 help_applications（status=pending），与 C端「管理页→审批」一致。
     */
    private List<ContentItemDTO> fetchPendingHelpItems(Long tenantId, List<Long> buildingUserIds, String search) {
        List<ContentItemDTO> result = new ArrayList<>();
        List<HelpApplication> pendingApps = helpApplicationRepository.findByStatus(BizStatus.PENDING);
        for (HelpApplication app : pendingApps) {
            HelpRequest helpRequest = helpRequestRepository.findById(app.getHelpId()).orElse(null);
            if (helpRequest == null || !tenantMatches(tenantId, helpRequest.getTenantId())) {
                continue;
            }
            // 楼栋筛选
            if (buildingUserIds != null && !buildingUserIds.contains(helpRequest.getUserId())) {
                continue;
            }
            // 搜索筛选
            if (search != null && !search.isEmpty()) {
                String keyword = search.toLowerCase();
                if (helpRequest.getTitle() == null || !helpRequest.getTitle().toLowerCase().contains(keyword)) {
                    continue;
                }
            }
            User requester = userRepository.findById(helpRequest.getUserId()).orElse(null);
            User helper = userRepository.findById(app.getHelperId()).orElse(null);
            result.add(ContentItemDTO.builder()
                    .id(helpRequest.getId())
                    .type("help")
                    .title(helpRequest.getTitle())
                    .description(helpRequest.getDescription())
                    .images(parseImages(helpRequest.getImages()))
                    .category(helpRequest.getCategory())
                    .publisherName(requester != null ? requester.getName() : "未知用户")
                    .publisherRoom(requester != null ? UserFormatter.formatRoomWithType(requester) : "")
                    .displayStatus("待审批")
                    .rawStatus(BizStatus.PENDING)
                    .isProxy(helpRequest.getIsProxy())
                    .isUrgent(helpRequest.getIsUrgent())
                    .createdAt(app.getCreatedAt())
                    .timeStart(helpRequest.getTimeStart())
                    .timeEnd(helpRequest.getTimeEnd())
                    .approverName(requester != null ? UserFormatter.formatRoomWithType(requester) : "未知用户")
                    .applicantName(helper != null ? UserFormatter.formatRoomWithType(helper) : "未知用户")
                    .building(requester != null ? getBuildingName(requester) : null)
                    .build());
        }
        return result;
    }

    private BorrowRequest findActiveBorrowRequest(Long idleId) {
        List<BorrowRequest> borrows = borrowRequestRepository.findByIdleId(idleId);
        return borrows.stream()
                .filter(b -> BizStatus.ACTIVE.equals(b.getStatus()) || BizStatus.APPROVED.equals(b.getStatus()))
                .findFirst().orElse(null);
    }

    private BorrowRequest findCompletedBorrowRequest(Long idleId) {
        List<BorrowRequest> borrows = borrowRequestRepository.findByIdleId(idleId);
        return borrows.stream()
                .filter(b -> BizStatus.RETURNED.equals(b.getStatus()))
                .findFirst().orElse(null);
    }

    private HelpApplication findActiveHelpApplication(Long helpId) {
        List<HelpApplication> apps = helpApplicationRepository.findByHelpId(helpId);
        return apps.stream()
                .filter(a -> BizStatus.APPROVED.equals(a.getStatus()))
                .findFirst().orElse(null);
    }

    private HelpApplication findCompletedHelpApplication(Long helpId) {
        List<HelpApplication> apps = helpApplicationRepository.findByHelpId(helpId);
        return apps.stream()
                .filter(a -> BizStatus.COMPLETED.equals(a.getStatus()))
                .findFirst().orElse(null);
    }

    // ==================== 私有辅助方法：楼栋解析 ====================

    /**
     * 将楼栋号解析为居住在该楼栋的用户 ID 列表（按数值匹配，不再用名称 contains）。
     */
    private List<Long> resolveBuildingUserIds(Integer buildingNo, Integer unitNo) {
        if (buildingNo == null) {
            return new ArrayList<>();
        }
        List<Building> allBuildings = buildingRepository.findAll();
        Building targetBuilding = allBuildings.stream()
                .filter(b -> b.getBuildingNo() != null && b.getBuildingNo().equals(buildingNo))
                .findFirst().orElse(null);
        if (targetBuilding == null) {
            return new ArrayList<>();
        }

        List<Unit> units = unitRepository.findByBuildingId(targetBuilding.getId());
        // 若指定了单元则按数值单元号过滤
        if (unitNo != null) {
            units = units.stream()
                    .filter(u -> u.getUnitNo() != null && u.getUnitNo().equals(unitNo))
                    .collect(Collectors.toList());
        }

        List<Long> roomIds = new ArrayList<>();
        for (Unit unit : units) {
            List<Room> rooms = roomRepository.findByUnitId(unit.getId());
            roomIds.addAll(rooms.stream().map(Room::getId).collect(Collectors.toList()));
        }

        if (roomIds.isEmpty()) {
            return new ArrayList<>();
        }

        List<User> users = userRepository.findByRoomIdIn(roomIds);
        return users.stream().map(User::getId).collect(Collectors.toList());
    }

    // ==================== 私有辅助方法：状态映射 ====================

    /**
     * 将 UI 状态页签映射为 IdleItem 的数据库状态值列表。
     */
    private List<String> mapStatusTabToIdleStatuses(String statusTab) {
        switch (statusTab) {
            case "showing":
                return java.util.Collections.singletonList(BizStatus.ONLINE);
            case "progressing":
                return java.util.Collections.singletonList(BizStatus.ACTIVE);
            case "completed":
                return java.util.Collections.singletonList(BizStatus.COMPLETED);
            case "violation":
                return java.util.Collections.singletonList(BizStatus.OFFLINE);
            case "all":
            default:
                return java.util.Arrays.asList(BizStatus.ONLINE, BizStatus.ACTIVE, BizStatus.COMPLETED, BizStatus.OFFLINE);
        }
    }

    /**
     * 将 UI 状态页签映射为 HelpRequest 的数据库状态值列表。
     */
    private List<String> mapStatusTabToHelpStatuses(String statusTab) {
        switch (statusTab) {
            case "showing":
                return java.util.Collections.singletonList(BizStatus.ONLINE);
            case "progressing":
                return java.util.Collections.singletonList(BizStatus.ACTIVE);
            case "completed":
                return java.util.Collections.singletonList(BizStatus.COMPLETED);
            case "violation":
                return java.util.Collections.singletonList(BizStatus.OFFLINE);
            case "all":
            default:
                return java.util.Arrays.asList(BizStatus.ONLINE, BizStatus.ACTIVE, BizStatus.COMPLETED, BizStatus.OFFLINE);
        }
    }

    /**
     * 将数据库原始状态映射为中文显示状态。
     */
    private String displayStatus(String rawStatus) {
        if (rawStatus == null) return "未知";
        switch (rawStatus) {
            case BizStatus.ONLINE:
                return "在线中";
            case BizStatus.PENDING:
                return "待审批";
            case BizStatus.ACTIVE:
                return "进行中";
            case BizStatus.COMPLETED:
                return "已完成";
            case BizStatus.OFFLINE:
                return "已下架";
            case BizStatus.PENDING_REVIEW:
                return "待AI审核";
            default:
                return rawStatus;
        }
    }

    /**
     * 从用户的房间关联链提取楼栋展示名（数值楼栋号拼 "x栋"）。
     */
    private String getBuildingName(User user) {
        if (user == null) return null;
        try {
            if (user.getRoom() != null
                    && user.getRoom().getUnit() != null
                    && user.getRoom().getUnit().getBuilding() != null
                    && user.getRoom().getUnit().getBuilding().getBuildingNo() != null) {
                return user.getRoom().getUnit().getBuilding().getBuildingNo() + "栋";
            }
        } catch (Exception e) {
            log.debug("解析楼栋名称失败", e);
        }
        return null;
    }

    /**
     * 将数字评分（1-5）转换为星级字符串。
     */
    private String scoreToStars(int score) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            sb.append(i < score ? "★" : "☆");
        }
        return sb.toString();
    }


    /**
     * 手机号脱敏，仅显示前 3 位和后 4 位。
     */
    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    /**
     * 若姓名为纯 ASCII（如英文微信昵称）则脱敏，
     * 避免在管理端住户列表中显示非中文姓名。
     */
    private String maskName(String name) {
        if (name == null || name.isEmpty()) return "——";
        // 姓名为纯 ASCII 时返回占位符
        if (name.matches("[\\p{ASCII}\\s]+")) return "——";
        return name;
    }

    // ==================== 私有辅助方法：通知 ====================

    private String getUserName(Long userId) {
        if (userId == null) return null;
        return userRepository.findById(userId).map(User::getName).orElse(null);
    }

    private void createNotification(Long userId, String type, String title, String content, Long relatedId) {
        notificationService.create(userId, type, title, content, relatedId);
    }

    // ==================== 私有辅助方法：审核查询 ====================

    /** 查询待 AI 审核的闲置物品，按 moderationStatus 和 moderatedBy 筛选。 */
    private List<IdleItem> fetchModerationIdleItems(Long tenantId, List<Long> buildingUserIds,
                                                     String search, String moderationStatus, String moderatedBy) {
        return idleItemRepository.findAll().stream()
                .filter(i -> tenantMatches(tenantId, i.getTenantId()))
                .filter(i -> i.getModerationStatus() != null)
                .filter(i -> matchesModerationStatus(
                        moderationStatus == null || moderationStatus.isEmpty() ? "yellow,red" : moderationStatus,
                        i.getModerationStatus(), i.getStatus()))
                .filter(i -> moderatedBy == null || "".equals(moderatedBy)
                        || ("ai".equals(moderatedBy) && i.getReviewedBy() == null)
                        || ("admin".equals(moderatedBy) && i.getReviewedBy() != null))
                .filter(i -> buildingUserIds == null || buildingUserIds.contains(i.getUserId()))
                .filter(i -> search == null || search.isEmpty()
                        || (i.getTitle() != null && i.getTitle().toLowerCase().contains(search.toLowerCase())))
                .collect(Collectors.toList());
    }

    /** 查询待 AI 审核的求助信息，按 moderationStatus 和 moderatedBy 筛选。 */
    private List<HelpRequest> fetchModerationHelpItems(Long tenantId, List<Long> buildingUserIds,
                                                        String search, String moderationStatus, String moderatedBy) {
        return helpRequestRepository.findAll().stream()
                .filter(h -> tenantMatches(tenantId, h.getTenantId()))
                .filter(h -> h.getModerationStatus() != null)
                .filter(h -> matchesModerationStatus(
                        moderationStatus == null || moderationStatus.isEmpty() ? "yellow,red" : moderationStatus,
                        h.getModerationStatus(), h.getStatus()))
                .filter(h -> moderatedBy == null || "".equals(moderatedBy)
                        || ("ai".equals(moderatedBy) && h.getReviewedBy() == null)
                        || ("admin".equals(moderatedBy) && h.getReviewedBy() != null))
                .filter(h -> buildingUserIds == null || buildingUserIds.contains(h.getUserId()))
                .filter(h -> search == null || search.isEmpty()
                        || (h.getTitle() != null && h.getTitle().toLowerCase().contains(search.toLowerCase())))
                .collect(Collectors.toList());
    }

    /**
     * 匹配 moderationStatus 筛选条件。
     * ModerationStatus.RED 匹配 AI 自动驳回 和 管理员驳回(REVIEWED + OFFLINE)。
     * "green,reviewed" 匹配 AI 通过(green) 和 管理员通过(reviewed + online/其他)。
     */
    private boolean matchesModerationStatus(String filter, String actual, String itemStatus) {
        if (filter.contains(",")) {
            for (String part : filter.split(",")) {
                if (matchesModerationStatus(part.trim(), actual, itemStatus)) return true;
            }
            return false;
        }
        if (ModerationStatus.RED.equals(filter)) {
            return ModerationStatus.RED.equals(actual)
                || (ModerationStatus.REVIEWED.equals(actual) && BizStatus.OFFLINE.equals(itemStatus));
        }
        return filter.equals(actual);
    }
}
