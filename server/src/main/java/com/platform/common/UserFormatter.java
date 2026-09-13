package com.platform.common;

import com.platform.model.entity.User;

/**
 * 用户信息格式化工具类 — 集中处理楼栋/单元/房号的拼接与用户类型标签转换。
 * <p>原先 5 个 Service 中各自私有的 {@code formatRoom} / {@code formatPersonName} / {@code getUserTypeLabel}
 * 方法实现完全一致，现提取为本类的静态方法以消除重复代码。</p>
 */
public final class UserFormatter {

    /** 工具类，禁止实例化 */
    private UserFormatter() {
    }

    /**
     * 格式化用户的完整地址字符串（不含用户类型后缀）。
     * <p>格式示例："3栋2单元1502号"；若 Room/Unit/Building 信息不完整则尽可能拼接可用部分。</p>
     *
     * @param user 用户实体，可为 null
     * @return 格式化的地址字符串，用户为 null 或 Room 为 null 时返回空字符串
     */
    public static String formatRoom(User user) {
        if (user == null || user.getRoom() == null) {
            return "";
        }
        try {
            String buildingName = "";
            String unitName = "";
            String roomNumber = "";

            // 展示串由数值字段拼"栋/单元"后缀（数据层存数值，避免字符串拼接脏数据）
            if (user.getRoom().getUnit() != null) {
                if (user.getRoom().getUnit().getUnitNo() != null) {
                    unitName = user.getRoom().getUnit().getUnitNo() + "单元";
                }
                if (user.getRoom().getUnit().getBuilding() != null
                        && user.getRoom().getUnit().getBuilding().getBuildingNo() != null) {
                    buildingName = user.getRoom().getUnit().getBuilding().getBuildingNo() + "栋";
                }
            }
            roomNumber = user.getRoom().getRoomNumber() != null
                    ? user.getRoom().getRoomNumber() : "";

            return buildingName + unitName + roomNumber + "号";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 格式化用户地址并附加用户类型后缀。
     * <p>格式示例："3栋2单元1502号(业主)"；地址为空时仅返回用户类型标签。</p>
     *
     * @param user 用户实体
     * @return 带类型后缀的地址字符串
     */
    public static String formatRoomWithType(User user) {
        String baseRoom = formatRoom(user);
        if (baseRoom == null || baseRoom.isEmpty()) {
            return "";
        }
        String userType = getUserTypeLabel(user != null ? user.getUserType() : null);
        return userType.isEmpty() ? baseRoom : baseRoom + "(" + userType + ")";
    }

    /**
     * 格式化用户显示名：优先使用地址+类型，地址不可用时回退到用户姓名+类型。
     *
     * @param user 用户实体，可为 null
     * @return 格式化的用户显示名，user 为 null 时返回 "未知用户"
     */
    public static String formatPersonName(User user) {
        if (user == null) return "未知用户";
        String roomPart = formatRoomWithType(user);
        if (!roomPart.isEmpty()) {
            return roomPart;
        }
        String name = user.getName() != null ? user.getName() : "未知用户";
        String typeLabel = getUserTypeLabel(user.getUserType());
        return typeLabel.isEmpty() ? name : name + "(" + typeLabel + ")";
    }

    /**
     * 将数据库存储的用户类型值转换为中文显示标签。
     *
     * @param userType 数据库存储的用户类型值（owner/tenant/admin/super_admin）
     * @return 中文标签，null 时返回空字符串
     */
    public static String getUserTypeLabel(String userType) {
        if (userType == null) return "";
        return switch (userType) {
            case UserType.OWNER -> "业主";
            case UserType.TENANT -> "租客";
            case UserType.ADMIN -> "管理员";
            case UserType.SENIOR_ADMIN -> "高级管理员";
            case UserType.SUPER_ADMIN -> "超级管理员";
            default -> userType;
        };
    }
}
