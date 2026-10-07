package com.wine.cellar.common;

import java.util.List;

/**
 * 葡萄酒窖业务词典与规则参数表：酒款产区 / 类型 / 状态、窖藏批次状态与流转、
 * 会员等级、销售出窖状态。规则集中在此，便于出题时被要求调整或扩展。
 */
public final class Dict {

    private Dict() {
    }

    // ---------------- 酒款 wine ----------------
    /** 产区：国产 / 法国 / 智利 / 澳洲 / 其他 */
    public static final List<String> WINE_REGIONS = List.of("国产", "法国", "智利", "澳洲", "其他");

    /** 类型：红 / 白 / 起泡 / 甜 */
    public static final List<String> WINE_TYPES = List.of("红", "白", "起泡", "甜");

    /** 状态：在售 / 停售 */
    public static final List<String> WINE_STATUS = List.of("在售", "停售");

    /** 酒款状态名 */
    public static final String WINE_ON_SALE = "在售";
    public static final String WINE_DELISTED = "停售";

    // ---------------- 窖藏批次 cellar_batch ----------------
    /** 窖藏批次状态：窖藏中 / 可售 / 已售罄，逐级推进，不可跳级 */
    public static final List<String> CELLAR_BATCH_STATUS = List.of("窖藏中", "可售", "已售罄");

    /** 窖藏批次正向推进顺序：窖藏中 → 可售 → 已售罄 */
    public static final List<String> CELLAR_BATCH_FLOW = List.of("窖藏中", "可售", "已售罄");

    /** 批次状态名 */
    public static final String BATCH_CELLARING = "窖藏中";
    public static final String BATCH_SELLABLE = "可售";
    public static final String BATCH_SOLD_OUT = "已售罄";

    // ---------------- 会员 member ----------------
    /** 会员等级：普通 / 银卡 / 黑卡 */
    public static final List<String> MEMBER_LEVELS = List.of("普通", "银卡", "黑卡");

    /** 会员累计积分对应等级阈值（达到即升级） */
    public static final int SILVER_POINTS = 2000;

    public static final int BLACK_POINTS = 5000;

    // ---------------- 销售出窖 sale_out ----------------
    /** 出窖状态：已出窖 / 已退窖 */
    public static final List<String> SALE_OUT_STATUS = List.of("已出窖", "已退窖");

    /** 出窖状态名（「已出窖」即在途：仍可被退窖冲正，停售/复售前必须核查） */
    public static final String SALE_OUT_DONE = "已出窖";
    public static final String SALE_OUT_REFUNDED = "已退窖";

    // ---------------- 校验方法 ----------------

    public static boolean isValidWineRegion(String region) {
        return region != null && WINE_REGIONS.contains(region);
    }

    public static boolean isValidWineType(String type) {
        return type != null && WINE_TYPES.contains(type);
    }

    public static boolean isValidWineStatus(String status) {
        return status != null && WINE_STATUS.contains(status);
    }

    public static boolean isValidCellarBatchStatus(String status) {
        return status != null && CELLAR_BATCH_STATUS.contains(status);
    }

    /**
     * 窖藏批次合法状态转换（拒绝一切跳跃与反向手工流转）：
     *   窖藏中 → 可售：放行
     *   可售   → 已售罄：出窖扣完
     *   已售罄 → 可售：退窖回补（仅此一种「回退」，且只由退窖触发）
     */
    public static boolean canTransferBatch(String from, String to) {
        if (BATCH_CELLARING.equals(from) && BATCH_SELLABLE.equals(to)) {
            return true;
        }
        if (BATCH_SELLABLE.equals(from) && BATCH_SOLD_OUT.equals(to)) {
            return true;
        }
        return BATCH_SOLD_OUT.equals(from) && BATCH_SELLABLE.equals(to);
    }

    /**
     * 酒款合法状态转换（拒绝重复提交与非法跳跃）：
     *   在售 → 停售：停售保护（须无在途业务）
     *   停售 → 在售：复售（须重新核验批次/库存资格）
     */
    public static boolean canTransferWine(String from, String to) {
        if (WINE_ON_SALE.equals(from) && WINE_DELISTED.equals(to)) {
            return true;
        }
        return WINE_DELISTED.equals(from) && WINE_ON_SALE.equals(to);
    }

    public static boolean isValidMemberLevel(String level) {
        return level != null && MEMBER_LEVELS.contains(level);
    }

    /** 按累计积分自动判定会员等级 */
    public static String decideMemberLevel(int points) {
        if (points >= BLACK_POINTS) {
            return "黑卡";
        }
        if (points >= SILVER_POINTS) {
            return "银卡";
        }
        return "普通";
    }

    public static boolean isValidSaleOutStatus(String status) {
        return status != null && SALE_OUT_STATUS.contains(status);
    }
}
