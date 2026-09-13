package com.platform.service;

import com.platform.common.AppTimeZone;
import com.platform.common.BizException;
import com.platform.common.BizStatus;
import com.platform.common.RatingType;
import com.platform.model.dto.RatingDTO;
import com.platform.model.dto.RatingRequest;
import com.platform.model.dto.UserRatingsDTO;
import com.platform.model.entity.BorrowRequest;
import com.platform.model.entity.HelpApplication;
import com.platform.model.entity.HelpRequest;
import com.platform.model.entity.IdleItem;
import com.platform.model.entity.Rating;
import com.platform.model.entity.User;
import com.platform.repository.BorrowRequestRepository;
import com.platform.repository.HelpApplicationRepository;
import com.platform.repository.HelpRequestRepository;
import com.platform.repository.IdleItemRepository;
import com.platform.repository.RatingRepository;
import com.platform.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Transactional
public class RatingService {

    private final RatingRepository ratingRepository;
    private final BorrowRequestRepository borrowRequestRepository;
    private final HelpApplicationRepository helpApplicationRepository;
    private final HelpRequestRepository helpRequestRepository;
    private final IdleItemRepository idleItemRepository;
    private final UserRepository userRepository;

    public RatingService(RatingRepository ratingRepository,
                         BorrowRequestRepository borrowRequestRepository,
                         HelpApplicationRepository helpApplicationRepository,
                         HelpRequestRepository helpRequestRepository,
                         IdleItemRepository idleItemRepository,
                         UserRepository userRepository) {
        this.ratingRepository = ratingRepository;
        this.borrowRequestRepository = borrowRequestRepository;
        this.helpApplicationRepository = helpApplicationRepository;
        this.helpRequestRepository = helpRequestRepository;
        this.idleItemRepository = idleItemRepository;
        this.userRepository = userRepository;
    }

    /**
     * 提交评价（借用评价 / 帮助评价），由评价发起方调用。
     */
    public Map<String, Object> submitRating(Long fromUserId, RatingRequest req) {
        normalizeRatingRequest(req);

        if (req.getBorrowId() != null) {
            return submitBorrowRating(fromUserId, req);
        }
        if (req.getHelpApplicationId() != null) {
            return submitHelpRating(fromUserId, req);
        }
        throw new BizException("请指定要评价的借入记录或帮助申请");
    }

    /**
     * 将 C端字段名归一化为后端字段名。
     */
    private void normalizeRatingRequest(RatingRequest req) {
        if (req.getBorrowId() == null && req.getHelpApplicationId() == null && req.getTargetId() != null) {
            if (RatingType.HELP.equals(req.getRatingType())) {
                req.setHelpApplicationId(req.getTargetId());
            } else {
                req.setBorrowId(req.getTargetId());
            }
        }
        if (req.getScore() == null && req.getOverallScore() != null) {
            req.setScore(req.getOverallScore());
        }
    }

    /**
     * 提交借用评价：校验权限 → 防重复 → 保存评价。
     */
    private Map<String, Object> submitBorrowRating(Long fromUserId, RatingRequest req) {
        BorrowRequest borrowRequest = borrowRequestRepository.findById(req.getBorrowId())
                .orElseThrow(() -> new BizException("借入记录不存在"));

        if (!BizStatus.RETURNED.equals(borrowRequest.getStatus())) {
            throw new BizException("只能对已归还的借入记录进行评价");
        }

        IdleItem idleItem = idleItemRepository.findById(borrowRequest.getIdleId()).orElse(null);
        Long ownerId = idleItem != null ? idleItem.getUserId() : null;
        Long borrowerId = borrowRequest.getBorrowerId();

        // 借入双方任一方均可评价对方
        boolean isBorrower = borrowerId.equals(fromUserId);
        boolean isOwner = ownerId != null && ownerId.equals(fromUserId);
        if (!isBorrower && !isOwner) {
            throw new BizException("无权评价该借入记录");
        }
        Long toUserId = isBorrower ? ownerId : borrowerId;
        if (toUserId == null) {
            throw new BizException("对方用户信息缺失，无法评价");
        }

        if (ratingRepository.findFirstByBorrowIdAndFromUserId(req.getBorrowId(), fromUserId).isPresent()) {
            throw new BizException("您已经评价过该借入记录");
        }

        Rating rating = new Rating();
        rating.setFromUserId(fromUserId);
        rating.setToUserId(toUserId);
        rating.setBorrowId(req.getBorrowId());
        rating.setScore(req.getScore());
        rating.setFeedback(req.getFeedback());
        rating.setCreatedAt(LocalDateTime.now(AppTimeZone.APP_ZONE));
        ratingRepository.save(rating);

        return ratingSuccessResult();
    }

    /**
     * 提交帮助评价：校验权限 → 防重复 → 保存评价。
     */
    private Map<String, Object> submitHelpRating(Long fromUserId, RatingRequest req) {
        HelpApplication application = helpApplicationRepository.findById(req.getHelpApplicationId())
                .orElseThrow(() -> new BizException("帮助申请不存在"));

        if (!BizStatus.COMPLETED.equals(application.getStatus())) {
            throw new BizException("只能对已完成的帮助进行评价");
        }

        HelpRequest helpRequest = helpRequestRepository.findById(application.getHelpId()).orElse(null);
        Long requesterId = helpRequest != null ? helpRequest.getUserId() : null;
        Long helperId = application.getHelperId();

        // 互助双方任一方均可评价对方
        boolean isHelper = helperId.equals(fromUserId);
        boolean isRequester = requesterId != null && requesterId.equals(fromUserId);
        if (!isHelper && !isRequester) {
            throw new BizException("无权评价该帮助");
        }
        Long toUserId = isHelper ? requesterId : helperId;
        if (toUserId == null) {
            throw new BizException("对方用户信息缺失，无法评价");
        }

        if (ratingRepository.findFirstByHelpApplicationIdAndFromUserId(req.getHelpApplicationId(), fromUserId).isPresent()) {
            throw new BizException("您已经评价过该帮助");
        }

        Rating rating = new Rating();
        rating.setFromUserId(fromUserId);
        rating.setToUserId(toUserId);
        rating.setHelpApplicationId(req.getHelpApplicationId());
        rating.setScore(req.getScore());
        rating.setFeedback(req.getFeedback());
        rating.setCreatedAt(LocalDateTime.now(AppTimeZone.APP_ZONE));
        ratingRepository.save(rating);

        return ratingSuccessResult();
    }

    /**
     * 构建评价成功的结果 Map。
     */
    private Map<String, Object> ratingSuccessResult() {
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "评价成功");
        return result;
    }

    public UserRatingsDTO getUserRatings(Long userId) {
        List<Rating> ratings = ratingRepository.findByToUserId(userId);
        List<RatingDTO> ratingDTOs = ratings.stream().map(this::toDTO).collect(Collectors.toList());

        double averageScore = ratings.stream()
                .mapToInt(Rating::getScore)
                .average()
                .orElse(5.0);

        return UserRatingsDTO.builder()
                .ratings(ratingDTOs)
                .averageScore(Math.round(averageScore * 10.0) / 10.0)
                .totalRatings((long) ratings.size())
                .build();
    }

    private RatingDTO toDTO(Rating rating) {
        User fromUser = userRepository.findById(rating.getFromUserId()).orElse(null);

        return RatingDTO.builder()
                .id(rating.getId())
                .fromUserName(fromUser != null ? fromUser.getName() : "未知用户")
                .score(rating.getScore())
                .feedback(rating.getFeedback())
                .createdAt(rating.getCreatedAt())
                .build();
    }
}
