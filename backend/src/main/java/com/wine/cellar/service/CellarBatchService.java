package com.wine.cellar.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wine.cellar.common.BizException;
import com.wine.cellar.common.Dict;
import com.wine.cellar.entity.CellarBatch;
import com.wine.cellar.entity.Wine;
import com.wine.cellar.repository.CellarBatchRepository;
import com.wine.cellar.repository.WineRepository;

@Service
public class CellarBatchService {

    private final CellarBatchRepository batchRepository;
    private final WineRepository wineRepository;
    private final WineService wineService;

    public CellarBatchService(CellarBatchRepository batchRepository,
                              WineRepository wineRepository,
                              WineService wineService) {
        this.batchRepository = batchRepository;
        this.wineRepository = wineRepository;
        this.wineService = wineService;
    }

    // ---------------- 查询 ----------------
    public List<CellarBatch> list(String status) {
        List<CellarBatch> all = batchRepository.findAll();
        if (status != null && !status.isBlank()) {
            all = all.stream().filter(b -> status.equals(b.status)).toList();
        }
        return all;
    }

    public CellarBatch get(Long id) {
        return batchRepository.findById(id)
                .orElseThrow(() -> new BizException("窖藏批次不存在"));
    }

    /** 行锁读取批次，调用方须在事务内 */
    public CellarBatch getLocked(Long id) {
        return batchRepository.findLockingById(id)
                .orElseThrow(() -> new BizException("窖藏批次不存在"));
    }

    /** 行锁读取某酒款全部可售批次（FIFO 顺序），调用方须在事务内 */
    public List<CellarBatch> listSellableForUpdate(Long wineId) {
        return batchRepository.findForUpdateByWineIdAndStatus(wineId, Dict.BATCH_SELLABLE);
    }

    /** 各可售批次可用瓶数合计（不加锁，仅展示用） */
    public int sumAvailable(Long wineId) {
        return batchRepository.findByWineIdOrderByCellarDateAscIdAsc(wineId).stream()
                .filter(b -> Dict.BATCH_SELLABLE.equals(b.status))
                .mapToInt(CellarBatch::getAvailableBottles)
                .sum();
    }

    /** 各可售批次可用瓶数合计（行锁，联动维护时使用） */
    public int sumAvailableForUpdate(Long wineId) {
        return listSellableForUpdate(wineId).stream()
                .mapToInt(CellarBatch::getAvailableBottles)
                .sum();
    }

    /**
     * 联动不变量：酒款账面库存必须等于各可售批次可用瓶数合计。
     * 不一致时给出可理解的错误，并提示恢复入口（调用方不应继续写入，避免半成功数据）。
     */
    public void assertStockConsistent(Wine w) {
        int batchAvailable = sumAvailableForUpdate(w.id);
        if (w.stock == null || w.stock != batchAvailable) {
            throw new BizException("酒款「" + w.name + "」账面库存（" + w.stock
                    + "）与各可售批次可用瓶数合计（" + batchAvailable
                    + "）不一致，请先在酒款台账执行「按批次校准库存」后再继续");
        }
    }

    // ---------------- 新建（一律窖藏中，不允许直接建可售/已售罄） ----------------
    public static class BatchInput {
        public Long wineId;
        public String batchNo;
        public Integer bottleCount;
        public java.time.LocalDate cellarDate;
        public String status;
    }

    @Transactional
    public CellarBatch create(BatchInput input) {
        if (input.wineId == null) {
            throw new BizException("关联酒款不能为空");
        }
        Wine wine = wineRepository.findById(input.wineId)
                .orElseThrow(() -> new BizException("关联酒款不存在"));
        if (input.batchNo == null || input.batchNo.isBlank()) {
            throw new BizException("批次号不能为空");
        }
        if (batchRepository.existsByBatchNo(input.batchNo)) {
            throw new BizException("批次号已存在，不可重复");
        }
        if (input.bottleCount == null || input.bottleCount <= 0) {
            throw new BizException("瓶数必须大于 0");
        }
        if (input.cellarDate == null) {
            throw new BizException("入窖日不能为空");
        }
        if (input.status != null && !input.status.isBlank() && !Dict.BATCH_CELLARING.equals(input.status)) {
            throw new BizException("新批次必须从「窖藏中」开始，放行后才能变为「可售」，不允许跳跃建单");
        }

        CellarBatch b = new CellarBatch();
        b.wineId = wine.id;
        b.batchNo = input.batchNo;
        b.bottleCount = input.bottleCount;
        b.soldCount = 0;
        b.cellarDate = input.cellarDate;
        b.status = Dict.BATCH_CELLARING;
        return batchRepository.save(b);
    }

    // ---------------- 更新（部分字段；进入流转后关键字段锁定） ----------------
    @Transactional
    public CellarBatch update(Long id, BatchInput input) {
        CellarBatch b = getLocked(id);
        boolean flowing = !Dict.BATCH_CELLARING.equals(b.status);
        if (input.wineId != null) {
            if (flowing) {
                throw new BizException("批次已" + b.status + "，关联酒款不可变更（仅窖藏中批次可改）");
            }
            Wine wine = wineRepository.findById(input.wineId)
                    .orElseThrow(() -> new BizException("关联酒款不存在"));
            b.wineId = wine.id;
        }
        if (input.batchNo != null) {
            if (input.batchNo.isBlank()) {
                throw new BizException("批次号不能为空");
            }
            if (!input.batchNo.equals(b.batchNo) && batchRepository.existsByBatchNoAndIdNot(input.batchNo, id)) {
                throw new BizException("批次号已存在，不可重复");
            }
            b.batchNo = input.batchNo;
        }
        if (input.bottleCount != null) {
            if (input.bottleCount <= 0) {
                throw new BizException("瓶数必须大于 0");
            }
            if (flowing) {
                throw new BizException("批次已" + b.status + "，瓶数不可直接修改（仅窖藏中批次可改，已放行批次请走退窖回补）");
            }
            b.bottleCount = input.bottleCount;
        }
        if (input.cellarDate != null) {
            b.cellarDate = input.cellarDate;
        }
        // 状态只能通过动作接口（放行/出窖/退窖）流转，PUT 一律拒绝，防止跳跃与重复操作
        if (input.status != null && !input.status.isBlank() && !input.status.equals(b.status)) {
            throw new BizException("批次状态不能手工改写，请使用「放行可售」等业务动作");
        }
        return batchRepository.save(b);
    }

    // ---------------- 动作：放行（窖藏中 → 可售，瓶数计入酒款库存） ----------------
    @Transactional
    public CellarBatch release(Long id) {
        // 先做存在性读取（不加锁）
        CellarBatch existing = get(id);
        // 与出窖/退窖保持同一加锁顺序：先酒款行，再批次行，杜绝交叉死锁
        Wine w = wineService.getLocked(existing.wineId);
        CellarBatch b = getLocked(id);
        // 拿锁后复查状态：拒绝重复放行（并发双提交时后到者在此被拦）
        if (!Dict.canTransferBatch(b.status, Dict.BATCH_SELLABLE)) {
            throw new BizException("仅「窖藏中」的批次可放行，当前为「" + b.status + "」，不可重复放行或跳跃流转");
        }
        // 放行前先校验账实一致，避免把历史偏差带进后续联动
        assertStockConsistent(w);

        b.status = Dict.BATCH_SELLABLE;
        CellarBatch saved = batchRepository.save(b);
        w.stock += b.getAvailableBottles();
        wineRepository.save(w);
        return saved;
    }

    // ---------------- 出窖联动：FIFO 扣减 ----------------
    /** 一次实际扣减分配 */
    public static class Allocation {
        public CellarBatch batch;
        public int qty;

        public Allocation(CellarBatch batch, int qty) {
            this.batch = batch;
            this.qty = qty;
        }
    }

    /**
     * 在行锁内按入窖最早批次优先（FIFO，同日按批次 id）从可售批次扣减 need 瓶。
     * 扣完的批次自动置「已售罄」。返回逐批扣减明细（供出窖单留痕）。
     * 调用方须保证 wine.stock 已通过 assertStockConsistent 校验且 stock >= need。
     */
    public List<Allocation> deductLocked(Wine w, int need) {
        List<CellarBatch> batches = listSellableForUpdate(w.id);
        int available = batches.stream().mapToInt(CellarBatch::getAvailableBottles).sum();
        if (available < need) {
            // 与账面库存不一致（理论上前面的一致性校验已拦截），防御性兜底
            throw new BizException("酒款「" + w.name + "」可售批次瓶数不足（批次合计仅剩 "
                    + available + "，需扣 " + need + "），请校准库存后再试");
        }

        List<Allocation> allocations = new ArrayList<>();
        int remaining = need;
        for (CellarBatch b : batches) {
            int can = b.getAvailableBottles();
            if (can <= 0 || remaining == 0) {
                continue;
            }
            int take = Math.min(can, remaining);
            b.soldCount += take;
            if (b.getAvailableBottles() == 0) {
                if (!Dict.canTransferBatch(b.status, Dict.BATCH_SOLD_OUT)) {
                    throw new BizException("批次「" + b.batchNo + "」状态为「" + b.status
                            + "」，无法转为已售罄，状态流转异常，已中止本次出窖");
                }
                b.status = Dict.BATCH_SOLD_OUT;
            }
            batchRepository.save(b);
            allocations.add(new Allocation(b, take));
            remaining -= take;
            if (remaining == 0) {
                break;
            }
        }
        if (remaining != 0) {
            throw new BizException("酒款「" + w.name + "」批次扣减未完成（剩余 " + remaining + " 瓶），已中止本次出窖");
        }
        return allocations;
    }

    // ---------------- 退窖联动：按原批次逐项回补 ----------------
    /**
     * 在行锁内向指定批次回补 qty 瓶；若批次原为「已售罄」，回补后恢复「可售」。
     */
    public void restoreLocked(CellarBatch b, int qty) {
        if (qty <= 0) {
            throw new BizException("批次「" + b.batchNo + "」回补数量必须大于 0");
        }
        if (b.soldCount < qty) {
            throw new BizException("批次「" + b.batchNo + "」已扣瓶数（" + b.soldCount
                    + "）小于待回补数量（" + qty + "），批次来源数据异常，已中止退窖");
        }
        boolean wasSoldOut = Dict.BATCH_SOLD_OUT.equals(b.status);
        b.soldCount -= qty;
        if (wasSoldOut) {
            if (!Dict.canTransferBatch(Dict.BATCH_SOLD_OUT, Dict.BATCH_SELLABLE)) {
                throw new BizException("批次「" + b.batchNo + "」无法从已售罄恢复为可售，状态流转异常");
            }
            b.status = Dict.BATCH_SELLABLE;
        } else if (!Dict.BATCH_SELLABLE.equals(b.status)) {
            throw new BizException("批次「" + b.batchNo + "」当前为「" + b.status
                    + "」，仅可售/已售罄批次允许退窖回补");
        }
        batchRepository.save(b);
    }
}
