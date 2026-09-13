package com.platform.service;

import com.platform.common.AppTimeZone;
import com.platform.common.PostType;
import com.platform.model.dto.IdleItemDTO;
import com.platform.model.dto.IdleItemRequest;
import com.platform.model.dto.PageDTO;
import com.platform.model.entity.IdleItem;
import com.platform.model.entity.User;
import com.platform.ai.embedding.EmbeddingService;
import com.platform.ai.matching.MatchingScheduler;
import com.platform.ai.moderation.ModerationService;
import com.platform.ai.search.SemanticSearchService;
import com.platform.repository.BorrowRequestRepository;
import com.platform.repository.IdleItemRepository;
import com.platform.repository.RatingRepository;
import com.platform.repository.RoomRepository;
import com.platform.repository.UserRepository;
import com.platform.common.BizStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("IdleService 单元测试")
class IdleServiceTest {

    @Mock
    private IdleItemRepository idleItemRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private RoomRepository roomRepository;
    @Mock
    private UserActivityService userActivityService;
    @Mock
    private RatingRepository ratingRepository;
    @Mock
    private BorrowRequestRepository borrowRequestRepository;
    @Mock
    private EmbeddingService embeddingService;
    @Mock
    private MatchingScheduler matchingScheduler;
    @Mock
    private SemanticSearchService semanticSearchService;
    @Mock
    private ModerationService moderationService;

    @InjectMocks
    private IdleService idleService;

    private Long userId;
    private Long itemId;
    private IdleItem idleItem;
    private User user;

    @BeforeEach
    void setUp() {
        userId = 1L;
        itemId = 100L;

        user = User.builder()
                .id(userId)
                .name("张三")
                .userType("业主")
                .build();

        idleItem = IdleItem.builder()
                .id(itemId)
                .userId(userId)
                .title("测试物品")
                .description("物品描述")
                .postType(PostType.LEND)
                .category("数码")
                .condition("good")
                .price(BigDecimal.ZERO)
                .images("[\"img.jpg\"]")
                .maxDuration(7)
                .durationUnit("day")
                .pickupMethod("self_pickup")
                .status(BizStatus.ONLINE)
                .createdAt(LocalDateTime.now(AppTimeZone.APP_ZONE))
                .build();
    }

    // ==================== publish ====================

    @Test
    @DisplayName("发布物品 - 正常发布物品成功")
    void should_publishItem_when_validInput() {
        // 准备
        IdleItemRequest req = new IdleItemRequest();
        req.setTitle("闲置手机");
        req.setDescription("9成新");
        req.setPostType(PostType.LEND);
        req.setCategory("数码");
        req.setCondition("good");
        req.setPrice(new BigDecimal("50"));
        req.setImages("[\"img.jpg\"]");
        req.setMaxDuration(14);
        req.setDurationUnit("day");
        req.setPickupMethod("self_pickup");

        when(idleItemRepository.save(any(IdleItem.class))).thenAnswer(inv -> {
            IdleItem item = inv.getArgument(0);
            item.setId(itemId);
            return item;
        });
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        // 执行
        IdleItemDTO result = idleService.publish(userId, req);

        // 断言：发布后挂起等待 AI 异步审核（pending_review）
        assertThat(result).isNotNull();
        assertThat(result.getTitle()).isEqualTo("闲置手机");
        assertThat(result.getPostType()).isEqualTo("LEND");
        assertThat(result.getStatus()).isEqualTo(BizStatus.PENDING_REVIEW);
        assertThat(result.getPrice()).isEqualByComparingTo(new BigDecimal("50"));
        verify(idleItemRepository).save(any(IdleItem.class));
    }

    @Test
    @DisplayName("发布物品 - 参数为null时使用默认值")
    void should_useDefaultValues_when_requestFieldsNull() {
        // 准备
        IdleItemRequest req = new IdleItemRequest();
        req.setTitle("最小值物品");
        req.setDescription("desc");
        req.setPostType(PostType.LEND);
        req.setCategory("其他");

        when(idleItemRepository.save(any(IdleItem.class))).thenAnswer(inv -> {
            IdleItem item = inv.getArgument(0);
            item.setId(itemId);
            return item;
        });
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        // 执行
        IdleItemDTO result = idleService.publish(userId, req);

        // 断言
        assertThat(result).isNotNull();
        assertThat(result.getMaxDuration()).isEqualTo(7);
        assertThat(result.getDurationUnit()).isEqualTo("day");
        assertThat(result.getPickupMethod()).isEqualTo("self_pickup");
        assertThat(result.getCondition()).isEqualTo("normal");
        assertThat(result.getPrice()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ==================== getHomeList ====================

    @Test
    @DisplayName("获取首页列表 - 正常返回分页列表")
    void should_returnHomeList_when_itemsExist() {
        // 准备
        Page<IdleItem> itemPage = new PageImpl<>(List.of(idleItem),
                PageRequest.of(0, 10), 1);
        // 用户未关联小区（tenantId 为 null）时走不带 tenant 过滤的查询分支
        when(idleItemRepository.findByStatusAndPostType(eq("online"), eq("LEND"), any(PageRequest.class)))
                .thenReturn(itemPage);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        // 执行
        PageDTO<IdleItemDTO> result = idleService.getHomeList("LEND", userId, 0, 10);

        // 断言
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getTotalPages()).isEqualTo(1);
        assertThat(result.getCurrentPage()).isEqualTo(0);
    }

    @Test
    @DisplayName("获取首页列表 - 没有物品时返回空分页")
    void should_returnEmptyHomeList_when_noItems() {
        // 准备
        Page<IdleItem> emptyPage = new PageImpl<>(Collections.emptyList(),
                PageRequest.of(0, 10), 0);
        when(userRepository.findById(userId)).thenReturn(Optional.empty());
        when(idleItemRepository.findByStatusAndPostType(eq("online"), eq("WANTED"), any(PageRequest.class)))
                .thenReturn(emptyPage);

        // 执行
        PageDTO<IdleItemDTO> result = idleService.getHomeList("WANTED", userId, 0, 10);

        // 断言
        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
    }

    // ==================== getDetail ====================

    @Test
    @DisplayName("获取物品详情 - 正常返回详情并增加用户统计")
    void should_returnDetail_when_itemExists() {
        // 准备
        when(idleItemRepository.findById(itemId)).thenReturn(Optional.of(idleItem));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(ratingRepository.getAverageScore(userId)).thenReturn(4.5);
        when(userActivityService.interactionStats(userId))
                .thenReturn(new UserActivityService.InteractionStats(0, 10, 0, 0, 10, 8));

        // 执行
        IdleItemDTO result = idleService.getDetail(itemId);

        // 断言
        assertThat(result).isNotNull();
        assertThat(result.getRating()).isEqualTo(4.5);
        assertThat(result.getLendCount()).isEqualTo(10);
        assertThat(result.getReturnRate()).isEqualTo("80%");
    }

    @Test
    @DisplayName("获取物品详情 - 物品不存在时抛出异常")
    void should_throwException_when_itemNotFound() {
        // 准备
        when(idleItemRepository.findById(itemId)).thenReturn(Optional.empty());

        // 执行 & 断言
        assertThatThrownBy(() -> idleService.getDetail(itemId))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("物品不存在");
    }

    @Test
    @DisplayName("获取物品详情 - 用户无评分时默认5.0分")
    void should_defaultRatingTo5_when_noRatings() {
        // 准备
        when(idleItemRepository.findById(itemId)).thenReturn(Optional.of(idleItem));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(ratingRepository.getAverageScore(userId)).thenReturn(null);
        when(userActivityService.interactionStats(userId))
                .thenReturn(new UserActivityService.InteractionStats(0, 0, 0, 0, 0, 0));

        // 执行
        IdleItemDTO result = idleService.getDetail(itemId);

        // 断言
        assertThat(result.getRating()).isEqualTo(5.0);
    }

    // ==================== search ====================

    @Test
    @DisplayName("搜索物品 - 按用户所属小区隔离搜索")
    void should_searchItems_when_keywordMatches() {
        // 准备：用户归属小区 10L，搜索必须走带 tenantId 的隔离查询（与首页列表一致）
        User tenantUser = User.builder().id(userId).name("张三").tenantId(10L).build();
        Page<IdleItem> itemPage = new PageImpl<>(List.of(idleItem),
                PageRequest.of(0, 10), 1);
        when(userRepository.findById(userId)).thenReturn(Optional.of(tenantUser));
        when(idleItemRepository.searchByTenant(
                eq("online"), eq("LEND"), eq(10L), eq("测试"), eq("测试"), any(PageRequest.class)))
                .thenReturn(itemPage);

        // 执行
        PageDTO<IdleItemDTO> result = idleService.search(userId, "测试", "LEND", 0, 10);

        // 断言
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getTitle()).isEqualTo("测试物品");
    }

    // ==================== getMyPosts ====================

    @Test
    @DisplayName("获取我的发布 - 正常返回用户的物品列表")
    void should_returnMyPosts_when_userHasItems() {
        // 准备
        Page<IdleItem> itemPage = new PageImpl<>(List.of(idleItem));
        when(idleItemRepository.findByUserIdAndPostType(eq(userId), eq("LEND"), any(Pageable.class)))
                .thenReturn(itemPage);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        // 执行
        List<IdleItemDTO> result = idleService.getMyPosts(userId, "LEND");

        // 断言
        assertThat(result).hasSize(1);
    }

    // ==================== delist ====================

    @Test
    @DisplayName("下架物品 - 正常下架物品")
    void should_delistItem_when_userOwnsItem() {
        // 准备
        when(idleItemRepository.findById(itemId)).thenReturn(Optional.of(idleItem));
        when(idleItemRepository.save(any(IdleItem.class))).thenReturn(idleItem);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        // 执行
        IdleItemDTO result = idleService.delist(userId, itemId);

        // 断言
        assertThat(result.getStatus()).isEqualTo(BizStatus.DRAFT);
        assertThat(idleItem.getStatus()).isEqualTo(BizStatus.DRAFT);
        assertThat(idleItem.getDelistReason()).isEqualTo("用户自行下架");
    }

    @Test
    @DisplayName("下架物品 - 非所有者操作时抛出异常")
    void should_throwException_when_delistNotOwner() {
        // 准备
        Long otherUserId = 99L;
        when(idleItemRepository.findById(itemId)).thenReturn(Optional.of(idleItem));

        // 执行 & 断言
        assertThatThrownBy(() -> idleService.delist(otherUserId, itemId))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("无权操作该物品");
    }

    // ==================== deleteItem ====================

    @Test
    @DisplayName("删除物品 - 正常软删除物品")
    void should_deleteItem_when_userOwnsItem() {
        // 准备
        when(idleItemRepository.findById(itemId)).thenReturn(Optional.of(idleItem));
        when(idleItemRepository.save(any(IdleItem.class))).thenReturn(idleItem);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        // 执行
        IdleItemDTO result = idleService.deleteItem(userId, itemId);

        // 断言
        assertThat(result.getStatus()).isEqualTo(BizStatus.OFFLINE);
        assertThat(idleItem.getStatus()).isEqualTo(BizStatus.OFFLINE);
        assertThat(idleItem.getDelistReason()).isEqualTo("用户删除");
    }

    @Test
    @DisplayName("删除物品 - 非所有者操作时抛出异常")
    void should_throwException_when_deleteNotOwner() {
        // 准备
        Long otherUserId = 99L;
        when(idleItemRepository.findById(itemId)).thenReturn(Optional.of(idleItem));

        // 执行 & 断言
        assertThatThrownBy(() -> idleService.deleteItem(otherUserId, itemId))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("无权操作该物品");
    }

    // ==================== update ====================

    @Test
    @DisplayName("更新物品 - 正常更新物品信息")
    void should_updateItem_when_validUpdate() {
        // 准备
        IdleItemRequest req = new IdleItemRequest();
        req.setTitle("更新后的标题");
        req.setDescription("更新描述");

        when(idleItemRepository.findById(itemId)).thenReturn(Optional.of(idleItem));
        when(idleItemRepository.save(any(IdleItem.class))).thenReturn(idleItem);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        // 执行
        IdleItemDTO result = idleService.update(userId, itemId, req);

        // 断言
        assertThat(result.getTitle()).isEqualTo("更新后的标题");
        assertThat(result.getDescription()).isEqualTo("更新描述");
    }

    @Test
    @DisplayName("更新物品 - 请求字段null时保留原值")
    void should_keepOriginalValues_when_requestFieldsNull() {
        // 准备
        IdleItemRequest req = new IdleItemRequest();
        req.setTitle("只更新标题");

        when(idleItemRepository.findById(itemId)).thenReturn(Optional.of(idleItem));
        when(idleItemRepository.save(any(IdleItem.class))).thenReturn(idleItem);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        // 执行
        IdleItemDTO result = idleService.update(userId, itemId, req);

        // 断言
        assertThat(result.getTitle()).isEqualTo("只更新标题");
        assertThat(result.getCategory()).isEqualTo("数码"); // 原始值
    }

    @Test
    @DisplayName("更新物品 - completed状态编辑后退回 pending_review 重新审核")
    void should_autoRelist_when_statusIsCompleted() {
        // 准备
        idleItem.setStatus("completed");
        IdleItemRequest req = new IdleItemRequest();
        req.setTitle("重新上线");

        when(idleItemRepository.findById(itemId)).thenReturn(Optional.of(idleItem));
        when(idleItemRepository.save(any(IdleItem.class))).thenReturn(idleItem);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        // 执行
        IdleItemDTO result = idleService.update(userId, itemId, req);

        // 断言：重新发布走 AI 审核流程，先挂起而非直接上线
        assertThat(result.getStatus()).isEqualTo(BizStatus.PENDING_REVIEW);
    }

    @Test
    @DisplayName("更新物品 - 物品不存在时抛出异常")
    void should_throwException_when_updateItemNotFound() {
        // 准备
        when(idleItemRepository.findById(itemId)).thenReturn(Optional.empty());

        // 执行 & 断言
        assertThatThrownBy(() -> idleService.update(userId, itemId, new IdleItemRequest()))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("物品不存在");
    }

    @Test
    @DisplayName("更新物品 - 非所有者操作时抛出异常")
    void should_throwException_when_updateNotOwner() {
        // 准备
        Long otherUserId = 99L;
        when(idleItemRepository.findById(itemId)).thenReturn(Optional.of(idleItem));

        // 执行 & 断言
        assertThatThrownBy(() -> idleService.update(otherUserId, itemId, new IdleItemRequest()))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("无权操作该物品");
    }
}
