package com.platform.service;

import com.platform.common.AppTimeZone;
import com.platform.common.BizException;
import com.platform.common.BorrowStatus;
import com.platform.common.PostStatus;
import com.platform.common.NotificationType;
import com.platform.common.PostType;
import com.platform.model.dto.ApproveRequest;
import com.platform.model.dto.BorrowRequestDTO;
import com.platform.model.dto.BorrowResponseDTO;
import com.platform.model.dto.ReturnRequest;
import com.platform.model.entity.BorrowRequest;
import com.platform.model.entity.IdleItem;
import com.platform.model.entity.User;
import com.platform.repository.BorrowRequestRepository;
import com.platform.repository.IdleItemRepository;
import com.platform.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 借入业务逻辑 — 申请借入/借出、审批、归还确认、物品状况补充。
 *
 * <p>申请与审批都用「物品行悲观锁 + 申请状态条件更新」保证同一物品不会被重复申领，
 * 且并发重复审批只有一次生效。</p>
 */
@Service
public class BorrowService {

    private final BorrowRequestRepository borrowRequestRepository;
    private final IdleItemRepository idleItemRepository;
    private final NotificationService notificationService;
    private final UserRepository userRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public BorrowService(BorrowRequestRepository borrowRequestRepository,
                         IdleItemRepository idleItemRepository,
                         NotificationService notificationService,
                         UserRepository userRepository) {
        this.borrowRequestRepository = borrowRequestRepository;
        this.idleItemRepository = idleItemRepository;
        this.notificationService = notificationService;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public BorrowResponseDTO getDetail(Long borrowId) {
        BorrowRequest br = borrowRequestRepository.findById(borrowId)
                .orElseThrow(() -> new BizException("借入记录不存在"));
        return toDTO(br);
    }

    @Transactional
    public BorrowResponseDTO apply(Long borrowerId, BorrowRequestDTO req) {
        // 悲观写锁（SELECT ... FOR UPDATE）：防止两个住户同时申请借入同一物品，
        // 确保"检查状态 → 创建申请 → 标记 reserved"三步在锁保护下原子执行
        IdleItem idleItem = idleItemRepository.findByIdWithLock(req.getIdleId())
                .orElseThrow(() -> new BizException("物品不存在"));

        if (!PostStatus.ONLINE.equals(idleItem.getStatus())) {
            throw new BizException(unavailableMessage(idleItem.getStatus()));
        }

        if (idleItem.getUserId().equals(borrowerId)) {
            throw new BizException("不能借入自己的物品");
        }

        // 防重复：同一用户对该物品已有待审批申请时拒绝。申请成功会把物品状态改为 pending，
        // 因此同一人再点通常先被上面的状态校验拦下，但那里提示的是「已被其他住户抢先申请」，
        // 对本人是误导；此处给出准确语义，同时覆盖状态被改回 online 后并发重复提交的窄缝。
        if (borrowRequestRepository.existsByBorrowerIdAndIdleIdAndStatus(
                borrowerId, idleItem.getId(), BorrowStatus.PENDING)) {
            throw new BizException("您已申请过该物品，请勿重复提交");
        }

        BorrowRequest borrowRequest = new BorrowRequest();
        borrowRequest.setIdleId(req.getIdleId());
        borrowRequest.setBorrowerId(borrowerId);
        borrowRequest.setDurationType(req.getDurationType() != null ? req.getDurationType() : "day");
        borrowRequest.setDurationDays(req.getDurationDays() != null ? req.getDurationDays() : 7);
        borrowRequest.setNote(req.getNote());
        borrowRequest.setStatus(BorrowStatus.PENDING);
        borrowRequest.setCreatedAt(LocalDateTime.now(AppTimeZone.APP_ZONE));
        borrowRequest = borrowRequestRepository.save(borrowRequest);

        // 标记物品为"已被预定"，使详情页按钮显示"已申请"而非"我要借出"
        idleItem.setStatus(PostStatus.PENDING);
        idleItemRepository.save(idleItem);

        boolean wanted = PostType.WANTED.equals(idleItem.getPostType());
        createNotification(idleItem.getUserId(), NotificationType.BORROW_REQUEST,
                wanted ? "新的借出意向" : "新的借入申请",
                wanted ? ("有人愿意借出给您：" + idleItem.getTitle())
                        : ("有人想借入您的物品：" + idleItem.getTitle()),
                borrowRequest.getId());

        // 通知申请人：申请已提交（服务通知展示"待回应"）
        // 先清理该用户对同一物品的旧借入/借出申请通知，避免上一轮申请的通知仍显示为待回应
        notificationService.deleteByUserIdAndTypeAndRelatedId(borrowerId, NotificationType.BORROW_APPLICATION, idleItem.getId());
        createNotification(borrowerId, NotificationType.BORROW_APPLICATION,
                wanted ? "借出申请已提交" : "借入申请已提交",
                wanted ? ("你已成功申请借出「" + idleItem.getTitle() + "」，等待对方确认")
                        : ("你已成功申请借入「" + idleItem.getTitle() + "」，等待对方确认"),
                idleItem.getId());

        return toDTO(borrowRequest);
    }

    /**
     * 审批借入申请：同意或拒绝。
     *
     * <p>并发策略：先用投影查询取出所属物品 ID（不加载申请实体，避免一级缓存中的旧值
     * 影响后续校验），再对物品行加悲观写锁——与「申请」「编辑」「下架」共用同一把锁，
     * 使状态流转串行。申请状态本身用条件更新判定（{@link BorrowRequestRepository#decideIfPending}），
     * 因此并发重复审批只有一次能生效。全程只持有一把锁，不会与其他写路径形成互相等待。</p>
     *
     * @param ownerId  物品所有者用户 ID
     * @param borrowId 借入申请 ID
     * @param req      审批请求（approved 为 true 表示同意）
     * @return 审批后的借入申请
     * @throws BizException 申请不存在、无权操作或该申请已被处理时抛出
     */
    @Transactional
    public BorrowResponseDTO approveReject(Long ownerId, Long borrowId, ApproveRequest req) {
        Long idleId = borrowRequestRepository.findIdleIdById(borrowId)
                .orElseThrow(() -> new BizException("借入申请不存在"));
        IdleItem idleItem = idleItemRepository.findByIdWithLock(idleId)
                .orElseThrow(() -> new BizException("物品不存在"));

        if (!idleItem.getUserId().equals(ownerId)) {
            throw new BizException("无权操作该申请");
        }

        boolean approved = req.getApproved();
        int updated = borrowRequestRepository.decideIfPending(borrowId,
                approved ? BorrowStatus.APPROVED : BorrowStatus.REJECTED,
                approved ? LocalDateTime.now(AppTimeZone.APP_ZONE) : null);
        if (updated == 0) {
            throw new BizException("该申请已被处理，无法重复操作");
        }

        BorrowRequest borrowRequest = borrowRequestRepository.findById(borrowId)
                .orElseThrow(() -> new BizException("借入申请不存在"));

        syncIdleItemAfterApproveReject(idleItem, borrowRequest, approved);
        notifyBorrowResult(borrowRequest, idleItem, req);

        return toDTO(borrowRequest);
    }

    /**
     * 按物品当前状态生成申请失败的提示文案。
     *
     * <p>「审核中」「已下架」与「被别人抢先申请」是三种不同的业务原因，
     * 统一提示会让用户误以为物品被他人抢走。</p>
     *
     * @param status 物品当前状态
     * @return 对应的提示文案
     */
    private static String unavailableMessage(String status) {
        if (PostStatus.PENDING_REVIEW.equals(status)) {
            return "该帖子正在审核中，暂时无法申请，请稍后再试";
        }
        if (PostStatus.DRAFT.equals(status) || PostStatus.OFFLINE.equals(status)) {
            return "该帖子已下架，无法申请";
        }
        return "该物品已被其他住户抢先申请，请浏览其他物品";
    }

    /**
     * 审批通过/拒绝后同步闲置物品状态。
     */
    private void syncIdleItemAfterApproveReject(IdleItem idleItem, BorrowRequest borrowRequest, boolean approved) {
        if (approved) {
            idleItem.setStatus(PostStatus.ACTIVE);
            borrowRequest.setStartDate(LocalDate.now(AppTimeZone.APP_ZONE));
            idleItemRepository.save(idleItem);
        } else {
            // 拒绝时：若该物品没有其他待审批的申请，恢复为 online
            List<BorrowRequest> pendingForItem = borrowRequestRepository
                    .findByIdleIdInAndStatus(List.of(borrowRequest.getIdleId()), BorrowStatus.PENDING);
            if (pendingForItem.isEmpty()) {
                idleItem.setStatus(PostStatus.ONLINE);
                idleItemRepository.save(idleItem);
            }
        }
    }

    /**
     * 审批后发送通知给借入申请人。
     */
    private void notifyBorrowResult(BorrowRequest borrowRequest, IdleItem idleItem, ApproveRequest req) {
        boolean approved = req.getApproved();
        boolean wanted = PostType.WANTED.equals(idleItem.getPostType());

        String title = approved
                ? (wanted ? "借出意向已被确认" : "借入申请已通过")
                : (wanted ? "借出意向被拒绝" : "借入申请被拒绝");

        String content;
        if (approved) {
            content = wanted
                    ? "您对「" + idleItem.getTitle() + "」的借出意向已被确认"
                    : "您对物品「" + idleItem.getTitle() + "」的借入申请已通过";
        } else {
            String reason = req.getReason() != null ? "，原因：" + req.getReason() : "";
            content = (wanted
                    ? "您对「" + idleItem.getTitle() + "」的借出意向被拒绝"
                    : "您对物品「" + idleItem.getTitle() + "」的借入申请被拒绝") + reason;
        }

        createNotification(borrowRequest.getBorrowerId(), NotificationType.BORROW_RESULT, title, content, borrowRequest.getId());
    }

    @Transactional(readOnly = true)
    public List<BorrowResponseDTO> getMyApplications(Long userId) {
        List<BorrowRequest> requests = borrowRequestRepository.findByBorrowerId(userId);
        return requests.stream().map(this::toDTO).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<BorrowResponseDTO> getPendingApprovals(Long userId) {
        List<IdleItem> myItems = idleItemRepository.findByUserId(userId);
        List<Long> myItemIds = myItems.stream().map(IdleItem::getId).collect(Collectors.toList());
        if (myItemIds.isEmpty()) return new ArrayList<>();
        List<BorrowRequest> pendingRequests = borrowRequestRepository
                .findByIdleIdInAndStatus(myItemIds, BorrowStatus.PENDING);
        return pendingRequests.stream().map(this::toDTO).collect(Collectors.toList());
    }

    /**
     * 确认归还 —— 借出双方（借入方 / 物品所有者）任意一方均可发起。
     * 借入(borrow)与借出(lend)是同一条 BorrowRequest 的两个视角，状态共享，
     * 因此任意一方确认归还后，双方的该笔记录都会从「进行中」进入「已完成」。
     */
    @Transactional
    public BorrowResponseDTO confirmReturn(Long actorId, Long borrowId, ReturnRequest req) {
        BorrowRequest borrowRequest = borrowRequestRepository.findById(borrowId)
                .orElseThrow(() -> new BizException("借入记录不存在"));

        IdleItem idleItem = idleItemRepository.findById(borrowRequest.getIdleId()).orElse(null);
        Long ownerId = idleItem != null ? idleItem.getUserId() : null;

        boolean isBorrower = borrowRequest.getBorrowerId().equals(actorId);
        boolean isOwner = ownerId != null && ownerId.equals(actorId);
        if (!isBorrower && !isOwner) {
            throw new BizException("无权操作该记录");
        }

        if (!BorrowStatus.APPROVED.equals(borrowRequest.getStatus())) {
            throw new BizException("该借入不在进行中，无法归还");
        }

        borrowRequest.setReturnStatus(req.getReturnStatus());
        borrowRequest.setReturnNote(req.getReturnNote());
        // 仅当请求中有值时才覆盖，避免借入方归还时把借出方已填的物品状况冲掉
        if (req.getDamageType() != null) {
            borrowRequest.setDamageType(req.getDamageType());
        }
        if (req.getDamageNote() != null) {
            borrowRequest.setDamageNote(req.getDamageNote());
        }
        borrowRequest.setIsOnTime(req.getIsOnTime());
        borrowRequest.setReturnPhotos(req.getReturnPhotos());
        borrowRequest.setStatus(BorrowStatus.RETURNED);
        borrowRequest.setReturnedAt(LocalDateTime.now(AppTimeZone.APP_ZONE));
        borrowRequest = borrowRequestRepository.save(borrowRequest);

        if (idleItem != null) {
            idleItem.setStatus(PostStatus.COMPLETED);
            idleItemRepository.save(idleItem);
        }

        // 通知对方（发起人是借入方则通知所有者，反之亦然）
        Long peerId = isBorrower ? ownerId : borrowRequest.getBorrowerId();
        if (peerId != null) {
            String itemTitle = idleItem != null ? idleItem.getTitle() : "物品";
            createNotification(peerId, NotificationType.RETURN_CONFIRM,
                    "物品已归还", "「" + itemTitle + "」的借用已归还，交易完成，请及时评价此次互助",
                    borrowRequest.getId());
        }

        return toDTO(borrowRequest);
    }

    /**
     * 补充归还后物品状况（damageType）。
     * 仅物品所有者可在已完成归还的记录上补填，防止借入方归还时跳过了物主确认。
     */
    @Transactional
    public void updateDamage(Long userId, Long borrowId, String damageType) {
        BorrowRequest br = borrowRequestRepository.findById(borrowId)
                .orElseThrow(() -> new BizException("借入记录不存在"));
        IdleItem idleItem = idleItemRepository.findById(br.getIdleId()).orElse(null);
        Long ownerId = idleItem != null ? idleItem.getUserId() : null;
        if (ownerId == null || !ownerId.equals(userId)) {
            throw new BizException("只有物品所有者可以填写物品状况");
        }
        if (!BorrowStatus.RETURNED.equals(br.getStatus())) {
            throw new BizException("仅已完成归还的记录可补充物品状况");
        }
        if (damageType == null || damageType.isEmpty()) {
            throw new BizException("请选择物品状况");
        }
        br.setDamageType(damageType);
        borrowRequestRepository.save(br);
    }

    private BorrowResponseDTO toDTO(BorrowRequest br) {
        IdleItem idleItem = idleItemRepository.findById(br.getIdleId()).orElse(null);
        Long ownerId = idleItem != null ? idleItem.getUserId() : null;
        User owner = ownerId != null ? userRepository.findById(ownerId).orElse(null) : null;
        User borrower = userRepository.findById(br.getBorrowerId()).orElse(null);

        return BorrowResponseDTO.builder()
                .id(br.getId())
                .idleId(br.getIdleId())
                .idleTitle(idleItem != null ? idleItem.getTitle() : "未知物品")
                .itemImage(extractFirstImage(idleItem))
                .ownerId(ownerId)
                .ownerName(owner != null ? owner.getName() : "未知用户")
                .borrowerId(br.getBorrowerId())
                .borrowerName(borrower != null ? borrower.getName() : "未知用户")
                .durationType(br.getDurationType())
                .durationDays(br.getDurationDays())
                .note(br.getNote())
                .status(br.getStatus())
                .returnStatus(br.getReturnStatus())
                .damageType(br.getDamageType())
                .isOnTime(br.getIsOnTime())
                .returnPhotos(br.getReturnPhotos())
                .createdAt(br.getCreatedAt())
                .build();
    }

    private String extractFirstImage(IdleItem idleItem) {
        if (idleItem == null || idleItem.getImages() == null || idleItem.getImages().isEmpty()) {
            return "";
        }
        try {
            List<String> urls = objectMapper.readValue(idleItem.getImages(), new TypeReference<List<String>>() {});
            return urls.isEmpty() ? "" : urls.get(0);
        } catch (Exception e) {
            return "";
        }
    }

    private void createNotification(Long userId, String type, String title, String content, Long relatedId) {
        notificationService.create(userId, type, title, content, relatedId);
    }
}
