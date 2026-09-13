package com.platform.service;

import com.platform.common.AppTimeZone;
import com.platform.common.BizStatus;
import com.platform.common.DurationUnit;
import com.platform.model.dto.ApproveRequest;
import com.platform.model.dto.BorrowRequestDTO;
import com.platform.model.dto.BorrowResponseDTO;
import com.platform.model.dto.ReturnRequest;
import com.platform.model.entity.BorrowRequest;
import com.platform.model.entity.IdleItem;
import com.platform.model.dto.NotificationDTO;
import com.platform.model.entity.User;
import com.platform.repository.BorrowRequestRepository;
import com.platform.repository.IdleItemRepository;
import com.platform.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("BorrowService 单元测试")
class BorrowServiceTest {

    @Mock
    private BorrowRequestRepository borrowRequestRepository;
    @Mock
    private IdleItemRepository idleItemRepository;
    @Mock
    private NotificationService notificationService;
    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private BorrowService borrowService;

    private Long borrowerId;
    private Long ownerId;
    private Long borrowId;
    private Long idleId;
    private IdleItem idleItem;
    private BorrowRequest borrowRequest;
    private User owner;
    private User borrower;

    @BeforeEach
    void setUp() {
        // 各实体使用不同的 Long 字面量，保证测试内 ID 互不冲突
        borrowerId = 1L;
        ownerId = 2L;
        borrowId = 100L;
        idleId = 200L;

        idleItem = IdleItem.builder()
                .id(idleId)
                .userId(ownerId)
                .title("测试物品")
                .status(BizStatus.ONLINE)
                .images("[\"http://img1.jpg\"]")
                .build();

        borrowRequest = BorrowRequest.builder()
                .id(borrowId)
                .idleId(idleId)
                .borrowerId(borrowerId)
                .durationType(DurationUnit.DAY)
                .durationDays(7)
                .startDate(LocalDate.now(AppTimeZone.APP_ZONE))
                .status(BizStatus.PENDING)
                .createdAt(LocalDateTime.now(AppTimeZone.APP_ZONE))
                .build();

        owner = User.builder()
                .id(ownerId)
                .name("张三")
                .build();

        borrower = User.builder()
                .id(borrowerId)
                .name("李四")
                .build();
    }

    // ==================== getDetail ====================

    @Test
    @DisplayName("获取借入详情 - 正常返回详情DTO")
    void should_returnDetail_when_borrowExists() {
        // 准备
        when(borrowRequestRepository.findById(borrowId)).thenReturn(Optional.of(borrowRequest));
        when(idleItemRepository.findById(idleId)).thenReturn(Optional.of(idleItem));
        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(userRepository.findById(borrowerId)).thenReturn(Optional.of(borrower));

        // 执行
        BorrowResponseDTO result = borrowService.getDetail(borrowId);

        // 断言
        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(borrowId);
        assertThat(result.getIdleTitle()).isEqualTo("测试物品");
        assertThat(result.getOwnerName()).isEqualTo("张三");
        assertThat(result.getBorrowerName()).isEqualTo("李四");
    }

    @Test
    @DisplayName("获取借入详情 - 记录不存在时抛出异常")
    void should_throwException_when_borrowNotFound() {
        // 准备
        when(borrowRequestRepository.findById(borrowId)).thenReturn(Optional.empty());

        // 执行 & 断言
        assertThatThrownBy(() -> borrowService.getDetail(borrowId))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("借入记录不存在");
    }

    // ==================== apply ====================

    @Test
    @DisplayName("申请借入 - 正常申请成功")
    void should_applyBorrow_when_validInput() {
        // 准备
        BorrowRequestDTO req = new BorrowRequestDTO();
        req.setIdleId(idleId);
        req.setDurationType("week");
        req.setDurationDays(14);
        req.setNote("测试备注");

        when(idleItemRepository.findByIdWithLock(idleId)).thenReturn(Optional.of(idleItem));
        when(borrowRequestRepository.save(any(BorrowRequest.class))).thenAnswer(inv -> {
            BorrowRequest br = inv.getArgument(0);
            br.setId(borrowId);
            return br;
        });
        when(notificationService.create(any(), any(), any(), any(), any())).thenReturn(new NotificationDTO());

        // 执行
        BorrowResponseDTO result = borrowService.apply(borrowerId, req);

        // 断言
        assertThat(result).isNotNull();
        assertThat(result.getBorrowerId()).isEqualTo(borrowerId);
        assertThat(result.getStatus()).isEqualTo(BizStatus.PENDING);
        verify(notificationService, times(2)).create(any(), any(), any(), any(), any());
        verify(borrowRequestRepository).save(any(BorrowRequest.class));
    }

    @Test
    @DisplayName("申请借入 - 物品不存在时抛出异常")
    void should_throwException_when_idleNotFound() {
        // 准备
        BorrowRequestDTO req = new BorrowRequestDTO();
        req.setIdleId(idleId);
        when(idleItemRepository.findByIdWithLock(idleId)).thenReturn(Optional.empty());

        // 执行 & 断言
        assertThatThrownBy(() -> borrowService.apply(borrowerId, req))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("物品不存在");
    }

    @Test
    @DisplayName("申请借入 - 物品已下架时抛出异常")
    void should_throwException_when_idleOffline() {
        // 准备
        idleItem.setStatus("offline");
        BorrowRequestDTO req = new BorrowRequestDTO();
        req.setIdleId(idleId);
        when(idleItemRepository.findByIdWithLock(idleId)).thenReturn(Optional.of(idleItem));

        // 执行 & 断言
        assertThatThrownBy(() -> borrowService.apply(borrowerId, req))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("该物品已被其他住户抢先申请，请浏览其他物品");
    }

    @Test
    @DisplayName("申请借入 - 借入自己的物品时抛出异常")
    void should_throwException_when_borrowingOwnItem() {
        // 准备
        idleItem.setUserId(borrowerId);
        BorrowRequestDTO req = new BorrowRequestDTO();
        req.setIdleId(idleId);
        when(idleItemRepository.findByIdWithLock(idleId)).thenReturn(Optional.of(idleItem));

        // 执行 & 断言
        assertThatThrownBy(() -> borrowService.apply(borrowerId, req))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("不能借入自己的物品");
    }

    @Test
    @DisplayName("申请借入 - 请求参数为null时使用默认值")
    void should_useDefaultValues_when_requestFieldsNull() {
        // 准备
        BorrowRequestDTO req = new BorrowRequestDTO();
        req.setIdleId(idleId);

        when(idleItemRepository.findByIdWithLock(idleId)).thenReturn(Optional.of(idleItem));
        when(borrowRequestRepository.save(any(BorrowRequest.class))).thenAnswer(inv -> {
            BorrowRequest br = inv.getArgument(0);
            br.setId(borrowId);
            return br;
        });
        when(notificationService.create(any(), any(), any(), any(), any())).thenReturn(new NotificationDTO());

        // 执行
        BorrowResponseDTO result = borrowService.apply(borrowerId, req);

        // 断言
        assertThat(result).isNotNull();
        verify(borrowRequestRepository).save(any(BorrowRequest.class));
    }

    // ==================== approveReject ====================

    @Test
    @DisplayName("审批通过 - 正常通过申请并更新物品状态")
    void should_approveBorrow_when_validApproval() {
        // 准备
        ApproveRequest req = new ApproveRequest();
        req.setApproved(true);

        when(borrowRequestRepository.findById(borrowId)).thenReturn(Optional.of(borrowRequest));
        when(idleItemRepository.findById(idleId)).thenReturn(Optional.of(idleItem));
        when(borrowRequestRepository.save(any(BorrowRequest.class))).thenReturn(borrowRequest);
        when(idleItemRepository.save(any(IdleItem.class))).thenReturn(idleItem);
        when(notificationService.create(any(), any(), any(), any(), any())).thenReturn(new NotificationDTO());
        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(userRepository.findById(borrowerId)).thenReturn(Optional.of(borrower));

        // 执行
        BorrowResponseDTO result = borrowService.approveReject(ownerId, borrowId, req);

        // 断言
        assertThat(result.getStatus()).isEqualTo(BizStatus.APPROVED);
        assertThat(idleItem.getStatus()).isEqualTo("active");
        verify(notificationService, atLeastOnce()).create(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("审批拒绝 - 正常拒绝申请")
    void should_rejectBorrow_when_validRejection() {
        // 准备
        ApproveRequest req = new ApproveRequest();
        req.setApproved(false);
        req.setReason("物品暂时不方便");

        when(borrowRequestRepository.findById(borrowId)).thenReturn(Optional.of(borrowRequest));
        when(idleItemRepository.findById(idleId)).thenReturn(Optional.of(idleItem));
        when(borrowRequestRepository.save(any(BorrowRequest.class))).thenReturn(borrowRequest);
        when(notificationService.create(any(), any(), any(), any(), any())).thenReturn(new NotificationDTO());
        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(userRepository.findById(borrowerId)).thenReturn(Optional.of(borrower));

        // 执行
        BorrowResponseDTO result = borrowService.approveReject(ownerId, borrowId, req);

        // 断言
        assertThat(result.getStatus()).isEqualTo("rejected");
        verify(notificationService, atLeastOnce()).create(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("审批 - 非物品所有者操作时抛出异常")
    void should_throwException_when_notOwnerApproving() {
        // 准备
        ApproveRequest req = new ApproveRequest();
        req.setApproved(true);
        Long otherUserId = 99L;

        when(borrowRequestRepository.findById(borrowId)).thenReturn(Optional.of(borrowRequest));
        when(idleItemRepository.findById(idleId)).thenReturn(Optional.of(idleItem));

        // 执行 & 断言
        assertThatThrownBy(() -> borrowService.approveReject(otherUserId, borrowId, req))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("无权操作该申请");
    }

    @Test
    @DisplayName("审批 - 申请已被处理时抛出异常")
    void should_throwException_when_alreadyProcessed() {
        // 准备
        borrowRequest.setStatus(BizStatus.APPROVED);
        ApproveRequest req = new ApproveRequest();
        req.setApproved(true);

        when(borrowRequestRepository.findById(borrowId)).thenReturn(Optional.of(borrowRequest));
        when(idleItemRepository.findById(idleId)).thenReturn(Optional.of(idleItem));

        // 执行 & 断言
        assertThatThrownBy(() -> borrowService.approveReject(ownerId, borrowId, req))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("该申请已被处理，无法重复操作");
    }

    // ==================== getMyApplications ====================

    @Test
    @DisplayName("获取我的申请 - 正常返回申请列表")
    void should_returnMyApplications_when_userHasApplications() {
        // 准备
        when(borrowRequestRepository.findByBorrowerId(borrowerId)).thenReturn(List.of(borrowRequest));
        when(idleItemRepository.findById(idleId)).thenReturn(Optional.of(idleItem));
        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(userRepository.findById(borrowerId)).thenReturn(Optional.of(borrower));

        // 执行
        List<BorrowResponseDTO> result = borrowService.getMyApplications(borrowerId);

        // 断言
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getBorrowerId()).isEqualTo(borrowerId);
    }

    @Test
    @DisplayName("获取我的申请 - 没有申请时返回空列表")
    void should_returnEmptyList_when_noApplications() {
        // 准备
        when(borrowRequestRepository.findByBorrowerId(borrowerId)).thenReturn(Collections.emptyList());

        // 执行
        List<BorrowResponseDTO> result = borrowService.getMyApplications(borrowerId);

        // 断言
        assertThat(result).isEmpty();
    }

    // ==================== getPendingApprovals ====================

    @Test
    @DisplayName("获取待审批 - 正常返回待审批列表")
    void should_returnPendingApprovals_when_hasPending() {
        // 准备
        when(idleItemRepository.findByUserId(ownerId)).thenReturn(List.of(idleItem));
        when(borrowRequestRepository.findByIdleIdInAndStatus(List.of(idleId), "pending"))
                .thenReturn(List.of(borrowRequest));
        when(idleItemRepository.findById(idleId)).thenReturn(Optional.of(idleItem));
        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(userRepository.findById(borrowerId)).thenReturn(Optional.of(borrower));

        // 执行
        List<BorrowResponseDTO> result = borrowService.getPendingApprovals(ownerId);

        // 断言
        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("获取待审批 - 没有物品时返回空列表")
    void should_returnEmpty_when_noItems() {
        // 准备
        when(idleItemRepository.findByUserId(ownerId)).thenReturn(Collections.emptyList());

        // 执行
        List<BorrowResponseDTO> result = borrowService.getPendingApprovals(ownerId);

        // 断言
        assertThat(result).isEmpty();
    }

    // ==================== confirmReturn ====================

    @Test
    @DisplayName("确认归还 - 正常确认归还并更新状态")
    void should_confirmReturn_when_validReturn() {
        // 准备
        BorrowRequest returnedBorrow = BorrowRequest.builder()
                .id(borrowId)
                .idleId(idleId)
                .borrowerId(borrowerId)
                .status(BizStatus.APPROVED)
                .build();

        ReturnRequest req = new ReturnRequest();
        req.setReturnStatus("good");
        req.setDamageType("none");
        req.setIsOnTime(true);

        when(borrowRequestRepository.findById(borrowId)).thenReturn(Optional.of(returnedBorrow));
        when(borrowRequestRepository.save(any(BorrowRequest.class))).thenReturn(returnedBorrow);
        when(idleItemRepository.findById(idleId)).thenReturn(Optional.of(idleItem));
        when(idleItemRepository.save(any(IdleItem.class))).thenReturn(idleItem);
        when(notificationService.create(any(), any(), any(), any(), any())).thenReturn(new NotificationDTO());
        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(userRepository.findById(borrowerId)).thenReturn(Optional.of(borrower));

        // 执行
        BorrowResponseDTO result = borrowService.confirmReturn(borrowerId, borrowId, req);

        // 断言
        assertThat(result.getStatus()).isEqualTo("returned");
        assertThat(result.getReturnStatus()).isEqualTo("good");
        assertThat(idleItem.getStatus()).isEqualTo(BizStatus.COMPLETED);
    }

    @Test
    @DisplayName("确认归还 - 记录不存在时抛出异常")
    void should_throwException_when_returnRecordNotFound() {
        // 准备
        when(borrowRequestRepository.findById(borrowId)).thenReturn(Optional.empty());

        // 执行 & 断言
        assertThatThrownBy(() -> borrowService.confirmReturn(borrowerId, borrowId, new ReturnRequest()))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("借入记录不存在");
    }

    @Test
    @DisplayName("确认归还 - 非借入方操作时抛出异常")
    void should_throwException_when_notBorrowerReturning() {
        // 准备
        Long otherUserId = 99L;
        when(borrowRequestRepository.findById(borrowId)).thenReturn(Optional.of(borrowRequest));

        // 执行 & 断言
        assertThatThrownBy(() -> borrowService.confirmReturn(otherUserId, borrowId, new ReturnRequest()))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("无权操作该记录");
    }
}
