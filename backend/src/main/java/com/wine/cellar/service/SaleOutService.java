package com.wine.cellar.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wine.cellar.common.BizException;
import com.wine.cellar.common.Dict;
import com.wine.cellar.entity.CellarBatch;
import com.wine.cellar.entity.Member;
import com.wine.cellar.entity.SaleOut;
import com.wine.cellar.entity.SaleOutItem;
import com.wine.cellar.entity.Wine;
import com.wine.cellar.repository.SaleOutItemRepository;
import com.wine.cellar.repository.SaleOutRepository;
import com.wine.cellar.repository.WineRepository;

@Service
public class SaleOutService {

    private final SaleOutRepository saleRepository;
    private final SaleOutItemRepository itemRepository;
    private final WineRepository wineRepository;
    private final WineService wineService;
    private final MemberService memberService;
    private final CellarBatchService batchService;
    private final WineSaleStatusService saleStatusService;

    public SaleOutService(SaleOutRepository saleRepository,
                          SaleOutItemRepository itemRepository,
                          WineRepository wineRepository,
                          WineService wineService,
                          MemberService memberService,
                          CellarBatchService batchService,
                          WineSaleStatusService saleStatusService) {
        this.saleRepository = saleRepository;
        this.itemRepository = itemRepository;
        this.wineRepository = wineRepository;
        this.wineService = wineService;
        this.memberService = memberService;
        this.batchService = batchService;
        this.saleStatusService = saleStatusService;
    }

    // ---------------- 查询 ----------------
    public List<SaleOut> list(String status) {
        List<SaleOut> all = saleRepository.findAll();
        if (status != null && !status.isBlank()) {
            all = all.stream().filter(s -> status.equals(s.status)).toList();
        }
        return all;
    }

    public SaleOut get(Long id) {
        return saleRepository.findById(id)
                .orElseThrow(() -> new BizException("出窖单不存在"));
    }

    public List<SaleOutItem> items(Long saleId) {
        return itemRepository.findBySaleId(saleId);
    }

    // ---------------- 入参 ----------------
    public static class SaleItemInput {
        public Long wineId;
        public Integer qty;
    }

    public static class SaleCreateInput {
        public Long memberId;                 // 可空（散客）
        public List<SaleItemInput> items;     // 出窖明细（按酒款）
    }

    // ---------------- 新建即出窖（批次 FIFO 扣减 + 同步酒款库存 + 积分） ----------------
    @Transactional
    public SaleOut create(SaleCreateInput input) {
        if (input.items == null || input.items.isEmpty()) {
            throw new BizException("出窖明细不能为空");
        }

        // 1) 参数校验 + 同一酒款多行合并（保持前端传参顺序的稳定感，后续统一按酒款 id 升序加锁）
        Map<Long, Integer> needByWine = new LinkedHashMap<>();
        for (SaleItemInput it : input.items) {
            if (it.wineId == null) {
                throw new BizException("出窖明细缺少酒款");
            }
            if (it.qty == null || it.qty <= 0) {
                throw new BizException("出窖数量必须大于 0");
            }
            needByWine.merge(it.wineId, it.qty, Integer::sum);
        }

        Member member = null;
        if (input.memberId != null) {
            member = memberService.get(input.memberId);   // 仅校验存在；真正加锁在扣减完成后
        }

        // 2) 先按酒款 id 升序加行锁并做全部校验（与前台展示同一套可售资格口径：在售 / 账实一致 / 库存充足），
        //    校验阶段不写任何数据——任何一条明细不满足都整体回滚，绝不留半成功数据。
        List<Long> wineIds = needByWine.keySet().stream().sorted().toList();
        Map<Long, Wine> wineCache = new LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Long wineId : wineIds) {
            Wine w = wineService.getLocked(wineId);
            saleStatusService.assertSaleable(w, needByWine.get(wineId));
            wineCache.put(wineId, w);
            total = total.add(w.price.multiply(BigDecimal.valueOf(needByWine.get(wineId))));
        }

        // 3) 逐酒款按最早入窖批次优先扣减，并为每次实际扣减留存批次明细行
        SaleOut sale = new SaleOut();
        sale.memberId = (member == null) ? null : member.id;
        sale.total = total;
        sale.pointsEarned = 0;
        sale.status = Dict.SALE_OUT_DONE;
        SaleOut saved = saleRepository.save(sale);

        for (Long wineId : wineIds) {
            Wine w = wineCache.get(wineId);
            int need = needByWine.get(wineId);

            List<CellarBatchService.Allocation> allocations = batchService.deductLocked(w, need);
            // 批次可用量与酒款库存同源，同步扣减以维持「酒款库存 = 各可售批次可用瓶数」
            w.stock -= need;
            wineRepository.save(w);

            for (CellarBatchService.Allocation a : allocations) {
                SaleOutItem row = new SaleOutItem();
                row.saleId = saved.id;
                row.wineId = w.id;
                row.batchId = a.batch.id;   // 实际扣减批次，出窖明细逐项留痕
                row.qty = a.qty;
                itemRepository.save(row);
            }
        }

        // 4) 关联会员才累加积分（1 元 = 1 积分，散客为 0），行锁内更新并自动复核等级
        int points = 0;
        if (member != null) {
            points = total.setScale(0, java.math.RoundingMode.FLOOR).intValue();
            if (points > 0) {
                memberService.addPoints(member.id, points);
            }
        }
        saved.pointsEarned = points;
        return saleRepository.save(saved);
    }

    // ---------------- 退窖（仅已出窖可退：按原批次逐项回补 + 库存 + 状态 + 积分） ----------------
    @Transactional
    public SaleOut refund(Long id) {
        // 先锁出窖单并复查状态：拒绝重复退窖与跳跃状态
        SaleOut sale = saleRepository.findLockingById(id)
                .orElseThrow(() -> new BizException("出窖单不存在"));
        if (!Dict.SALE_OUT_DONE.equals(sale.status)) {
            throw new BizException("仅「已出窖」的订单可退窖，当前为「" + sale.status + "」，请勿重复操作");
        }

        List<SaleOutItem> rows = itemRepository.findBySaleId(id);
        if (rows.isEmpty()) {
            throw new BizException("出窖单 #" + id + " 缺少批次扣减明细，无法按批次回补，退窖中止");
        }

        // 按酒款分组（酒款 id 升序，与出窖同一加锁顺序），每款内按批次 id 升序回补
        Map<Long, List<SaleOutItem>> rowsByWine = new LinkedHashMap<>();
        for (SaleOutItem row : rows) {
            rowsByWine.computeIfAbsent(row.wineId, k -> new ArrayList<>()).add(row);
        }

        for (Long wineId : rowsByWine.keySet().stream().sorted().toList()) {
            Wine w = wineService.getLocked(wineId);
            batchService.assertStockConsistent(w);

            List<SaleOutItem> wineRows = rowsByWine.get(wineId).stream()
                    .sorted(java.util.Comparator.comparingLong(r -> r.batchId))
                    .toList();

            int refundQty = 0;
            Map<Long, CellarBatch> batchCache = new LinkedHashMap<>();
            for (SaleOutItem row : wineRows) {
                CellarBatch b = batchCache.computeIfAbsent(row.batchId, batchService::getLocked);
                batchService.restoreLocked(b, row.qty);   // 逐项回补，已售罄自动恢复可售
                refundQty += row.qty;
            }

            w.stock += refundQty;
            wineRepository.save(w);
        }

        // 会员积分随订单最终状态回滚（按本单实际产生积分精确扣减；散客无积分，自然跳过）
        if (sale.memberId != null && sale.pointsEarned != null && sale.pointsEarned > 0) {
            memberService.addPoints(sale.memberId, -sale.pointsEarned);
        }

        sale.status = Dict.SALE_OUT_REFUNDED;
        return saleRepository.save(sale);
    }
}
