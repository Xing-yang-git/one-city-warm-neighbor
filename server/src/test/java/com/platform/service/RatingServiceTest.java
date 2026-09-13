package com.platform.service;

import com.platform.model.dto.RatingRequest;
import com.platform.model.dto.UserRatingsDTO;
import com.platform.model.entity.*;
import com.platform.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RatingService 单元测试")
class RatingServiceTest {

    @Mock
    private RatingRepository ratingRepository;
    @Mock
    private BorrowRequestRepository borrowRequestRepository;
    @Mock
    private HelpApplicationRepository helpApplicationRepository;
    @Mock
    private HelpRequestRepository helpRequestRepository;
    @Mock
    private IdleItemRepository idleItemRepository;
    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private RatingService ratingService;

    private Long fromUserId;
    private Long ownerId;
    private Long borrowId;
    private Long helpApplicationId;
    private Long idleId;
    private Long helpId;

    @BeforeEach
    void setUp() {
        // 各实体使用不同的 Long 字面量，保证测试内 ID 互不冲突
        fromUserId = 1L;
        ownerId = 2L;
        borrowId = 100L;
        helpApplicationId = 200L;
        idleId = 300L;
        helpId = 400L;
    }

    // ==================== submitRating - borrow ====================

    @Test
    @DisplayName("提交借入评价 - 正常评价成功")
    void should_submitBorrowRating_when_validBorrow() {
        // 准备
        RatingRequest req = new RatingRequest();
        req.setBorrowId(borrowId);
        req.setScore(5);

        BorrowRequest borrowRequest = BorrowRequest.builder()
                .id(borrowId)
                .idleId(idleId)
                .borrowerId(fromUserId)
                .status("returned")
                .build();

        IdleItem idleItem = IdleItem.builder()
                .id(idleId)
                .userId(ownerId)
                .build();

        when(borrowRequestRepository.findById(borrowId)).thenReturn(Optional.of(borrowRequest));
        when(ratingRepository.findFirstByBorrowIdAndFromUserId(borrowId, fromUserId)).thenReturn(Optional.empty());
        when(idleItemRepository.findById(idleId)).thenReturn(Optional.of(idleItem));
        when(ratingRepository.save(any(Rating.class))).thenAnswer(inv -> inv.getArgument(0));

        // 执行
        Map<String, Object> result = ratingService.submitRating(fromUserId, req);

        // 断言
        assertThat(result).containsEntry("success", true);
        assertThat(result).containsEntry("message", "评价成功");
        verify(ratingRepository).save(any(Rating.class));
    }

    @Test
    @DisplayName("提交借入评价 - 借入记录不存在时抛出异常")
    void should_throwException_when_borrowNotFound() {
        // 准备
        RatingRequest req = new RatingRequest();
        req.setBorrowId(borrowId);
        req.setScore(5);

        when(borrowRequestRepository.findById(borrowId)).thenReturn(Optional.empty());

        // 执行 & 断言
        assertThatThrownBy(() -> ratingService.submitRating(fromUserId, req))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("借入记录不存在");
    }

    @Test
    @DisplayName("提交借入评价 - 借入未归还时抛出异常")
    void should_throwException_when_borrowNotReturned() {
        // 准备
        RatingRequest req = new RatingRequest();
        req.setBorrowId(borrowId);
        req.setScore(5);

        BorrowRequest borrowRequest = BorrowRequest.builder()
                .id(borrowId)
                .borrowerId(fromUserId)
                .status("approved")
                .build();

        when(borrowRequestRepository.findById(borrowId)).thenReturn(Optional.of(borrowRequest));

        // 执行 & 断言
        assertThatThrownBy(() -> ratingService.submitRating(fromUserId, req))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("只能对已归还的借入记录进行评价");
    }

    @Test
    @DisplayName("提交借入评价 - 非借入双方评价时抛出异常")
    void should_throwException_when_notBorrower() {
        // 准备
        RatingRequest req = new RatingRequest();
        req.setBorrowId(borrowId);
        req.setScore(5);

        Long otherUserId = 99L;
        BorrowRequest borrowRequest = BorrowRequest.builder()
                .id(borrowId)
                .borrowerId(otherUserId)
                .status("returned")
                .build();

        when(borrowRequestRepository.findById(borrowId)).thenReturn(Optional.of(borrowRequest));

        // 执行 & 断言
        assertThatThrownBy(() -> ratingService.submitRating(fromUserId, req))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("无权评价该借入记录");
    }

    @Test
    @DisplayName("提交借入评价 - 重复评价时抛出异常")
    void should_throwException_when_alreadyRatedBorrow() {
        // 准备
        RatingRequest req = new RatingRequest();
        req.setBorrowId(borrowId);
        req.setScore(5);

        BorrowRequest borrowRequest = BorrowRequest.builder()
                .id(borrowId)
                .idleId(idleId)
                .borrowerId(fromUserId)
                .status("returned")
                .build();

        when(borrowRequestRepository.findById(borrowId)).thenReturn(Optional.of(borrowRequest));
        // 物品必须存在，否则会先触发"对方用户信息缺失"防御而到不了重复评价检查
        when(idleItemRepository.findById(idleId)).thenReturn(Optional.of(
                IdleItem.builder().id(idleId).userId(ownerId).build()));
        when(ratingRepository.findFirstByBorrowIdAndFromUserId(borrowId, fromUserId))
                .thenReturn(Optional.of(new Rating()));

        // 执行 & 断言
        assertThatThrownBy(() -> ratingService.submitRating(fromUserId, req))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("您已经评价过该借入记录");
    }

    @Test
    @DisplayName("提交借入评价 - 物品已删除导致对方缺失时抛出异常")
    void should_throwException_when_ownerMissing() {
        // 准备
        RatingRequest req = new RatingRequest();
        req.setBorrowId(borrowId);
        req.setScore(5);

        BorrowRequest borrowRequest = BorrowRequest.builder()
                .id(borrowId)
                .idleId(idleId)
                .borrowerId(fromUserId)
                .status("returned")
                .build();

        when(borrowRequestRepository.findById(borrowId)).thenReturn(Optional.of(borrowRequest));
        // 物品已被删除——评价目标用户无法解析，应给出业务报错而非数据库约束异常
        when(idleItemRepository.findById(idleId)).thenReturn(Optional.empty());

        // 执行 & 断言
        assertThatThrownBy(() -> ratingService.submitRating(fromUserId, req))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("对方用户信息缺失，无法评价");
    }

    @Test
    @DisplayName("提交借入评价 - 通过targetId和ratingType别名提交")
    void should_submitRating_when_usingTargetIdAlias() {
        // 准备
        RatingRequest req = new RatingRequest();
        req.setTargetId(borrowId);
        req.setRatingType("borrow");
        req.setOverallScore(4);

        BorrowRequest borrowRequest = BorrowRequest.builder()
                .id(borrowId)
                .idleId(idleId)
                .borrowerId(fromUserId)
                .status("returned")
                .build();

        IdleItem idleItem = IdleItem.builder()
                .id(idleId)
                .userId(ownerId)
                .build();

        when(borrowRequestRepository.findById(borrowId)).thenReturn(Optional.of(borrowRequest));
        when(ratingRepository.findFirstByBorrowIdAndFromUserId(borrowId, fromUserId)).thenReturn(Optional.empty());
        when(idleItemRepository.findById(idleId)).thenReturn(Optional.of(idleItem));
        when(ratingRepository.save(any(Rating.class))).thenAnswer(inv -> inv.getArgument(0));

        // 执行
        Map<String, Object> result = ratingService.submitRating(fromUserId, req);

        // 断言
        assertThat(result).containsEntry("success", true);
        assertThat(req.getScore()).isEqualTo(4);
    }

    // ==================== submitRating - help ====================

    @Test
    @DisplayName("提交帮助评价 - 正常评价成功")
    void should_submitHelpRating_when_validHelp() {
        // 准备
        RatingRequest req = new RatingRequest();
        req.setHelpApplicationId(helpApplicationId);
        req.setScore(5);

        HelpApplication application = HelpApplication.builder()
                .id(helpApplicationId)
                .helpId(helpId)
                .helperId(fromUserId)
                .status("completed")
                .build();

        HelpRequest helpRequest = HelpRequest.builder()
                .id(helpId)
                .userId(ownerId)
                .build();

        when(helpApplicationRepository.findById(helpApplicationId)).thenReturn(Optional.of(application));
        when(ratingRepository.findFirstByHelpApplicationIdAndFromUserId(helpApplicationId, fromUserId))
                .thenReturn(Optional.empty());
        when(helpRequestRepository.findById(helpId)).thenReturn(Optional.of(helpRequest));
        when(ratingRepository.save(any(Rating.class))).thenAnswer(inv -> inv.getArgument(0));

        // 执行
        Map<String, Object> result = ratingService.submitRating(fromUserId, req);

        // 断言
        assertThat(result).containsEntry("success", true);
        assertThat(result).containsEntry("message", "评价成功");
    }

    @Test
    @DisplayName("提交帮助评价 - 帮助申请不存在时抛出异常")
    void should_throwException_when_helpApplicationNotFound() {
        // 准备
        RatingRequest req = new RatingRequest();
        req.setHelpApplicationId(helpApplicationId);
        req.setScore(5);

        when(helpApplicationRepository.findById(helpApplicationId)).thenReturn(Optional.empty());

        // 执行 & 断言
        assertThatThrownBy(() -> ratingService.submitRating(fromUserId, req))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("帮助申请不存在");
    }

    @Test
    @DisplayName("提交帮助评价 - 帮助未完成时抛出异常")
    void should_throwException_when_helpNotCompleted() {
        // 准备
        RatingRequest req = new RatingRequest();
        req.setHelpApplicationId(helpApplicationId);
        req.setScore(5);

        HelpApplication application = HelpApplication.builder()
                .id(helpApplicationId)
                .helperId(fromUserId)
                .status("approved")
                .build();

        when(helpApplicationRepository.findById(helpApplicationId)).thenReturn(Optional.of(application));

        // 执行 & 断言
        assertThatThrownBy(() -> ratingService.submitRating(fromUserId, req))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("只能对已完成的帮助进行评价");
    }

    @Test
    @DisplayName("提交帮助评价 - 非互助双方评价时抛出异常")
    void should_throwException_when_notHelper() {
        // 准备
        RatingRequest req = new RatingRequest();
        req.setHelpApplicationId(helpApplicationId);
        req.setScore(5);

        Long otherUserId = 99L;
        HelpApplication application = HelpApplication.builder()
                .id(helpApplicationId)
                .helperId(otherUserId)
                .status("completed")
                .build();

        when(helpApplicationRepository.findById(helpApplicationId)).thenReturn(Optional.of(application));

        // 执行 & 断言
        assertThatThrownBy(() -> ratingService.submitRating(fromUserId, req))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("无权评价该帮助");
    }

    @Test
    @DisplayName("提交评价 - 既没有borrowId也没有helpApplicationId时抛出异常")
    void should_throwException_when_noTargetSpecified() {
        // 准备
        RatingRequest req = new RatingRequest();
        req.setScore(5);

        // 执行 & 断言
        assertThatThrownBy(() -> ratingService.submitRating(fromUserId, req))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("请指定要评价的借入记录或帮助申请");
    }

    // ==================== getUserRatings ====================

    @Test
    @DisplayName("获取用户评分 - 正常返回评分列表和平均分")
    void should_returnUserRatings_when_userHasRatings() {
        // 准备
        Rating rating1 = Rating.builder()
                .id(500L)
                .fromUserId(5L)
                .toUserId(ownerId)
                .score(5)
                .createdAt(LocalDateTime.now())
                .build();
        Rating rating2 = Rating.builder()
                .id(501L)
                .fromUserId(6L)
                .toUserId(ownerId)
                .score(4)
                .createdAt(LocalDateTime.now())
                .build();

        when(ratingRepository.findByToUserId(ownerId)).thenReturn(List.of(rating1, rating2));
        when(userRepository.findById(any(Long.class))).thenReturn(Optional.of(
                User.builder().name("评分者").build()));

        // 执行
        UserRatingsDTO result = ratingService.getUserRatings(ownerId);

        // 断言
        assertThat(result.getRatings()).hasSize(2);
        assertThat(result.getAverageScore()).isEqualTo(4.5);
        assertThat(result.getTotalRatings()).isEqualTo(2L);
    }

    @Test
    @DisplayName("获取用户评分 - 用户没有评分时返回空列表和0分")
    void should_returnEmptyRatings_when_userHasNoRatings() {
        // 准备
        when(ratingRepository.findByToUserId(ownerId)).thenReturn(Collections.emptyList());

        // 执行
        UserRatingsDTO result = ratingService.getUserRatings(ownerId);

        // 断言
        assertThat(result.getRatings()).isEmpty();
        assertThat(result.getAverageScore()).isEqualTo(5.0);
        assertThat(result.getTotalRatings()).isEqualTo(0L);
    }
}
