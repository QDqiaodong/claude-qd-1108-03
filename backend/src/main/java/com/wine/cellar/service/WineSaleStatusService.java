package com.wine.cellar.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wine.cellar.common.BizException;
import com.wine.cellar.common.Dict;
import com.wine.cellar.entity.CellarBatch;
import com.wine.cellar.entity.SaleOutItem;
import com.wine.cellar.entity.Wine;
import com.wine.cellar.repository.CellarBatchRepository;
import com.wine.cellar.repository.SaleOutItemRepository;
import com.wine.cellar.repository.WineRepository;

/**
 * 酒款停售/复售资格保护：
 * <ul>
 *   <li>停售：行锁 + 单事务内核查在途业务（未完成出窖单 / 待处理退窖明细），存在即拒绝并保持原状态；
 *       无阻断项时一次性提交，前台销售入口、酒款可售标记、批次可售展示同源（均由 wine.status + 批次推导），
 *       不会出现后台已停售但前台仍可选购的半成功结果。</li>
 *   <li>复售：不是简单把状态改回在售——重新核验在途冲正、库存镜像与批次一致性、至少一个可售批次，
 *       任一不满足即拒绝并说明需先处理的事项，原停售状态不变；全部通过才恢复销售选择。</li>
 *   <li>可售资格：同一套 evaluate 口径供酒款列表、批次页、销售出窖页与出窖闸门共用。</li>
 * </ul>
 */
@Service
public class WineSaleStatusService {

    private final WineRepository wineRepository;
    private final CellarBatchRepository batchRepository;
    private final SaleOutItemRepository itemRepository;
    private final CellarBatchService batchService;

    public WineSaleStatusService(WineRepository wineRepository,
                                 CellarBatchRepository batchRepository,
                                 SaleOutItemRepository itemRepository,
                                 CellarBatchService batchService) {
        this.wineRepository = wineRepository;
        this.batchRepository = batchRepository;
        this.itemRepository = itemRepository;
        this.batchService = batchService;
    }

    // ---------------- 可售资格（同一套结果，三页面 + 出窖闸门共用） ----------------
    /** 某酒款的可售资格视图 */
    public static class Eligibility {
        public Long wineId;
        public boolean saleEligible;     // 可进入销售选择
        public int sellableQty;          // 可售批次可用瓶数合计（前台展示可售量以此为准）
        public Integer bookStock;        // 账面库存（镜像）
        public boolean stockConsistent;  // 账面库存 = 可售批次可用合计
        public List<String> reasons = new ArrayList<>();  // 不可售原因（可售时为空）

        public String blockReason() {
            return reasons.isEmpty() ? null : String.join("；", reasons);
        }
    }

    /** 纯计算：由酒款状态 + 可售批次推导资格（所有入口共用，杜绝各页面各算各的） */
    public Eligibility evaluate(Wine w, List<CellarBatch> sellableBatches) {
        Eligibility r = new Eligibility();
        r.wineId = w.id;
        r.bookStock = w.stock;
        r.sellableQty = sellableBatches.stream().mapToInt(CellarBatch::getAvailableBottles).sum();
        r.stockConsistent = w.stock != null && w.stock == r.sellableQty;
        if (!Dict.WINE_ON_SALE.equals(w.status)) {
            r.reasons.add("酒款已停售");
        }
        if (!r.stockConsistent) {
            r.reasons.add("库存镜像（账面 " + w.stock + "）与可售批次合计（" + r.sellableQty
                    + "）不一致，请先在酒款台账执行「按批次校准库存」");
        }
        if (r.sellableQty <= 0) {
            r.reasons.add("无可售批次瓶数（批次未放行或已全部售罄）");
        }
        r.saleEligible = r.reasons.isEmpty();
        return r;
    }

    /** 为酒款列表填充同一套资格结果（列表/批次/销售页展示用，不加锁） */
    public void fillEligibility(List<Wine> wines) {
        if (wines == null || wines.isEmpty()) {
            return;
        }
        Map<Long, List<CellarBatch>> sellableByWine = batchRepository.findAll().stream()
                .filter(b -> Dict.BATCH_SELLABLE.equals(b.status))
                .collect(Collectors.groupingBy(b -> b.wineId, LinkedHashMap::new, Collectors.toList()));
        for (Wine w : wines) {
            applyEligibility(w, evaluate(w, sellableByWine.getOrDefault(w.id, List.of())));
        }
    }

    /** 单个酒款的资格结果（不加锁，展示用） */
    public Eligibility eligibilityOf(Long wineId) {
        Wine w = wineRepository.findById(wineId)
                .orElseThrow(() -> new BizException("酒款不存在"));
        List<CellarBatch> sellable = batchRepository.findByWineIdOrderByCellarDateAscIdAsc(wineId).stream()
                .filter(b -> Dict.BATCH_SELLABLE.equals(b.status))
                .toList();
        return evaluate(w, sellable);
    }

    private void applyEligibility(Wine w, Eligibility r) {
        w.saleEligible = r.saleEligible;
        w.sellableQty = r.sellableQty;
        w.stockConsistent = r.stockConsistent;
        w.saleBlockReason = r.blockReason();
    }

    /**
     * 出窖闸门：与资格展示同一口径（在售 + 账实一致 + 可售量充足）。
     * 调用方须在事务内且酒款行已加锁。
     */
    public void assertSaleable(Wine w, int need) {
        if (!Dict.WINE_ON_SALE.equals(w.status)) {
            throw new BizException("酒款「" + w.name + "」已停售，不可出窖");
        }
        batchService.assertStockConsistent(w);
        if (w.stock < need) {
            throw new BizException("酒款「" + w.name + "」可售瓶数不足（仅剩 "
                    + w.stock + "，需出窖 " + need + "），本单未扣减任何批次");
        }
    }

    // ---------------- 在途业务核查：未完成出窖单 / 待处理退窖明细 ----------------
    private static class InFlight {
        List<Long> saleIds;   // 未完成出窖单号（升序去重）
        int rows;             // 待处理退窖明细行数
        int bottles;          // 待处理退窖瓶数

        boolean exists() {
            return !saleIds.isEmpty();
        }

        String describe() {
            String orders = saleIds.stream().map(id -> "#" + id).collect(Collectors.joining("、"));
            return "未完成出窖单 " + saleIds.size() + " 笔（单号 " + orders + "），"
                    + "待处理退窖明细 " + rows + " 行共 " + bottles + " 瓶";
        }
    }

    private InFlight findInFlight(Long wineId) {
        List<SaleOutItem> items = itemRepository.findByWineIdAndSaleStatus(wineId, Dict.SALE_OUT_DONE);
        InFlight r = new InFlight();
        r.saleIds = items.stream().map(i -> i.saleId).distinct().sorted().toList();
        r.rows = items.size();
        r.bottles = items.stream().mapToInt(i -> i.qty).sum();
        return r;
    }

    // ---------------- 停售（在途业务保护 + 同一事务生效） ----------------
    @Transactional
    public Wine delist(Long id) {
        // 先拿酒款行锁再核查：与出窖/退窖/放行同一加锁入口，并发提交在此串行化，只产生一个完整结果
        Wine w = wineRepository.findLockingById(id)
                .orElseThrow(() -> new BizException("酒款不存在"));
        if (!Dict.canTransferWine(w.status, Dict.WINE_DELISTED)) {
            throw new BizException("酒款「" + w.name + "」当前为「" + w.status
                    + "」，仅「在售」酒款可停售，请勿重复提交或跳跃流转");
        }

        InFlight inFlight = findInFlight(id);
        if (inFlight.exists()) {
            throw new BizException("酒款「" + w.name + "」存在在途业务，不能停售："
                    + inFlight.describe()
                    + "。请先在「销售出窖」页办理退窖，待全部出窖单结案后再停售；"
                    + "本次操作未生效，酒款保持「在售」。");
        }

        // 无阻断项：同一事务内提交。前台销售入口、酒款可售标记、批次可售展示均由
        // wine.status + 批次同源推导，随本次提交一并生效，不留半成功结果。
        w.status = Dict.WINE_DELISTED;
        return wineRepository.save(w);
    }

    // ---------------- 复售（重新核验资格，全部通过才恢复销售） ----------------
    @Transactional
    public Wine relist(Long id) {
        Wine w = wineRepository.findLockingById(id)
                .orElseThrow(() -> new BizException("酒款不存在"));
        if (!Dict.canTransferWine(w.status, Dict.WINE_ON_SALE)) {
            throw new BizException("酒款「" + w.name + "」当前为「" + w.status
                    + "」，仅「停售」酒款可复售，请勿重复提交或跳跃流转");
        }

        List<String> blockers = new ArrayList<>();

        // 1) 未完成冲正：仍存在可被退窖冲正的在途出窖单（历史遗留或异常路径），复售会让可售量继续变动
        InFlight inFlight = findInFlight(id);
        if (inFlight.exists()) {
            blockers.add("存在未完成冲正（" + inFlight.describe() + "），请先在「销售出窖」页完成退窖结案");
        }

        // 2) 库存镜像与批次状态一致性（行锁可售批次，与放行/出窖/退窖同一加锁顺序：先酒款后批次）
        List<CellarBatch> sellable = batchRepository.findForUpdateByWineIdAndStatus(id, Dict.BATCH_SELLABLE);
        int sellableQty = sellable.stream().mapToInt(CellarBatch::getAvailableBottles).sum();
        if (w.stock == null || w.stock != sellableQty) {
            blockers.add("库存镜像（账面 " + w.stock + "）与可售批次合计（" + sellableQty
                    + "）不一致，请先在「酒款台账」执行「按批次校准库存」");
        }

        // 3) 至少一个可售批次且有可用瓶数
        if (sellable.isEmpty() || sellableQty <= 0) {
            blockers.add("没有可用的可售批次（批次已全部售罄或尚未放行），请先在「窖藏批次」页新建并放行批次");
        }

        if (!blockers.isEmpty()) {
            StringBuilder msg = new StringBuilder("酒款「" + w.name + "」暂不能复售：");
            for (int i = 0; i < blockers.size(); i++) {
                msg.append(i == 0 ? "" : "；").append(i + 1).append("）").append(blockers.get(i));
            }
            msg.append("。请处理完成后重试，酒款保持「停售」。");
            throw new BizException(msg.toString());
        }

        // 核验全部通过：同一事务内恢复在售，前台销售选择随之恢复，可售量 = 可售批次合计
        w.status = Dict.WINE_ON_SALE;
        return wineRepository.save(w);
    }
}
