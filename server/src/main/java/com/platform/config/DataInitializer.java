package com.platform.config;

import com.platform.common.BizStatus;
import com.platform.common.KnowledgeCategory;
import com.platform.common.UserType;
import com.platform.model.entity.Building;
import com.platform.model.entity.KnowledgeItem;
import com.platform.model.entity.Room;
import com.platform.model.entity.Tenant;
import com.platform.model.entity.Unit;
import com.platform.model.entity.User;
import com.platform.repository.BuildingRepository;
import com.platform.repository.KnowledgeItemRepository;
import com.platform.repository.RoomRepository;
import com.platform.repository.TenantRepository;
import com.platform.repository.UnitRepository;
import com.platform.repository.UserRepository;
import com.platform.service.KnowledgeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;


/**
 * 首次启动时初始化管理员账号和小区种子数据。
 * 可安全重复执行——已有数据时自动跳过。
 */
@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final BuildingRepository buildingRepository;
    private final UnitRepository unitRepository;
    private final RoomRepository roomRepository;
    private final KnowledgeItemRepository knowledgeItemRepository;
    private final KnowledgeService knowledgeService;
    private final PasswordEncoder passwordEncoder;

    public DataInitializer(UserRepository userRepository,
                           TenantRepository tenantRepository,
                           BuildingRepository buildingRepository,
                           UnitRepository unitRepository,
                           RoomRepository roomRepository,
                           KnowledgeItemRepository knowledgeItemRepository,
                           KnowledgeService knowledgeService,
                           PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.tenantRepository = tenantRepository;
        this.buildingRepository = buildingRepository;
        this.unitRepository = unitRepository;
        this.roomRepository = roomRepository;
        this.knowledgeItemRepository = knowledgeItemRepository;
        this.knowledgeService = knowledgeService;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(String... args) {
        Long tenantId = seedTenant();
        seedAdmin(tenantId);
        seedKnowledge(tenantId);
    }

    /**
     * 初始化 AI 助手「小邻」知识库种子数据 — 平台使用帮助（分类 help）。
     *
     * <p>小区规则/服务/办事指南等物业文档由 B端知识库管理页后续录入。
     * 平台帮助类条目与具体小区无关，绑定到第一个小区（翠湖花园）即可。</p>
     *
     * @param tenantId 第一个小区的 tenant ID
     */
    private void seedKnowledge(Long tenantId) {
        if (knowledgeItemRepository.count() > 0) {
            log.info("知识库已有数据，跳过平台帮助种子初始化");
            return;
        }
        if (tenantId == null) {
            return;
        }
        String[] titles = {
                "注册与实名认证流程",
                "发布闲置或求助的流程",
                "借入物品的流程",
                "互助评价规则",
                "违规内容处理规则"
        };
        String[] contents = {
                "注册流程：使用手机号注册并绑定微信。注册完成后需提交实名认证（业主上传房产证明，租客上传租房合同），" +
                        "认证审核通过后才能发布物品、发起借入或接单。审核结果会通过服务通知告知。",
                "发布流程：在首页点击发布，选择闲置借出、需求借入或技能求助，填写标题、描述、图片等信息。提交后内容会" +
                        "先经过 AI 审核，审核通过自动上线展示，审核不通过会收到驳回原因通知。",
                "借入流程：在物品详情页点击借入申请，填写借用时长和备注。物主审批同意后按约定线下交接，归还时确认物品状况。" +
                        "借出方也可在管理页查看待审批申请。",
                "评价规则：互助（借入归还或求助完成）结束后，双方可在管理页的评价弹框中互评，评分 1-5 星并填写互助感想。" +
                        "评价内容用于积累住户信用，请如实填写。",
                "违规处理：平台禁止发布违禁品（烟酒药品、武器、活体动物等）、纯广告和欺诈信息。违规内容会被 AI 审核驳回" +
                        "或管理员下架，情节严重的账号将被封禁。"
        };
        for (int i = 0; i < titles.length; i++) {
            KnowledgeItem item = KnowledgeItem.builder()
                    .tenantId(tenantId)
                    .category(KnowledgeCategory.HELP)
                    .title(titles[i])
                    .content(contents[i])
                    .source("平台使用帮助")
                    .status(BizStatus.ONLINE)
                    .createdBy(null)
                    .build();
            // 经 KnowledgeService 创建以自动生成 1024 维向量（生成失败留空，可 reindex 补齐）
            knowledgeService.create(item);
        }
        log.info("知识库平台帮助种子初始化完成: {} 条", titles.length);
    }

    private void seedAdmin(Long tenantId) {
        if (userRepository.findByUsername("admin").isPresent()) {
            log.info("Admin user already exists, skipping seed");
            return;
        }

        User admin = User.builder()
                .username("admin")
                .passwordHash(passwordEncoder.encode("admin123"))
                .name("系统管理员")
                .userType(UserType.SUPER_ADMIN)
                .tenantId(null)  // super_admin 为平台级，不绑定具体小区
                .authStatus(BizStatus.APPROVED)
                .build();
        userRepository.save(admin);
        log.info("Created super_admin: admin (tenant_id={})", tenantId);
    }

    private Long seedTenant() {
        if (tenantRepository.count() > 0) {
            log.info("Tenants already exist, skipping seed");
            return tenantRepository.findAll().get(0).getId();
        }

        Tenant tenant = Tenant.builder()
                .name("翠湖花园")
                .build();
        tenant = tenantRepository.save(tenant);
        log.info("Created tenant: 翠湖花园");

        Long tenantId = tenant.getId();

        // 创建 8 栋楼，每栋 3 个单元，每个单元 4 个房间 (1-8栋 × 1-3单元 × 101-804)
        for (int b = 1; b <= 8; b++) {
            Building building = Building.builder()
                    .tenantId(tenantId)
                    .buildingNo(b)
                    .build();
            building = buildingRepository.save(building);
            Long buildingId = building.getId();

            for (int u = 1; u <= 3; u++) {
                Unit unit = Unit.builder()
                        .buildingId(buildingId)
                        .unitNo(u)
                        .build();
                unit = unitRepository.save(unit);
                Long unitId = unit.getId();

                for (int r = 1; r <= 4; r++) {
                    String roomNumber = (b * 100 + r) + "";
                    Room room = Room.builder()
                            .unitId(unitId)
                            .roomNumber(roomNumber)
                            .build();
                    roomRepository.save(room);
                }
            }
        }
        log.info("Created buildings/units/rooms for 翠湖花园 (8栋 × 3单元 × 4房)");

        // 创建第二个小区，供下拉选择使用
        Tenant tenant2 = Tenant.builder()
                .name("阳光花园")
                .build();
        tenant2 = tenantRepository.save(tenant2);
        log.info("Created tenant: 阳光花园");

        Long t2Id = tenant2.getId();
        for (int b = 1; b <= 3; b++) {
            Building building = Building.builder()
                    .tenantId(t2Id)
                    .buildingNo(b)
                    .build();
            building = buildingRepository.save(building);
            Long buildingId = building.getId();

            for (int u = 1; u <= 2; u++) {
                Unit unit = Unit.builder()
                        .buildingId(buildingId)
                        .unitNo(u)
                        .build();
                unit = unitRepository.save(unit);
                Long unitId = unit.getId();

                for (int r = 1; r <= 6; r++) {
                    String roomNumber = (b * 100 + r) + "";
                    Room room = Room.builder()
                            .unitId(unitId)
                            .roomNumber(roomNumber)
                            .build();
                    roomRepository.save(room);
                }
            }
        }
        log.info("Created buildings/units/rooms for 阳光花园 (3栋 × 2单元 × 6房)");

        return tenantId;
    }
}
