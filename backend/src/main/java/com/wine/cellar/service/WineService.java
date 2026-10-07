package com.wine.cellar.service;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wine.cellar.common.BizException;
import com.wine.cellar.common.Dict;
import com.wine.cellar.entity.Wine;
import com.wine.cellar.repository.CellarBatchRepository;
import com.wine.cellar.repository.WineRepository;

@Service
public class WineService {

    private final WineRepository wineRepository;
    private final CellarBatchRepository batchRepository;

    public WineService(WineRepository wineRepository, CellarBatchRepository batchRepository) {
        this.wineRepository = wineRepository;
        this.batchRepository = batchRepository;
    }

    // ---------------- 查询 ----------------
    public List<Wine> list(String region, String type, String status) {
        List<Wine> all = wineRepository.findAll();
        if (region != null && !region.isBlank()) {
            all = all.stream().filter(w -> region.equals(w.region)).toList();
        }
        if (type != null && !type.isBlank()) {
            all = all.stream().filter(w -> type.equals(w.type)).toList();
        }
        if (status != null && !status.isBlank()) {
            all = all.stream().filter(w -> status.equals(w.status)).toList();
        }
        return all;
    }

    public Wine get(Long id) {
        return wineRepository.findById(id)
                .orElseThrow(() -> new BizException("酒款不存在"));
    }

    /** 行锁读取酒款（库存联动时使用），调用方须在事务内 */
    public Wine getLocked(Long id) {
        return wineRepository.findLockingById(id)
                .orElseThrow(() -> new BizException("酒款不存在"));
    }

    // ---------------- 新建 ----------------
    public static class WineInput {
        public String code;
        public String name;
        public String region;
        public String type;
        public Integer stock;
        public BigDecimal price;
        public String status;
    }

    public Wine create(WineInput input) {
        if (input.code == null || input.code.isBlank()) {
            throw new BizException("酒款编号不能为空");
        }
        if (input.name == null || input.name.isBlank()) {
            throw new BizException("酒名不能为空");
        }
        if (!Dict.isValidWineRegion(input.region)) {
            throw new BizException("产区必须是：国产/法国/智利/澳洲/其他 之一");
        }
        if (!Dict.isValidWineType(input.type)) {
            throw new BizException("类型必须是：红/白/起泡/甜 之一");
        }
        // 库存由窖藏批次联动：新酒款一律 0，放行批次后才有可售库存
        if (input.stock != null && input.stock != 0) {
            throw new BizException("酒款库存不能直接录入，请先新建窖藏批次并放行，库存将随批次自动增加");
        }
        if (input.price == null || input.price.compareTo(BigDecimal.ZERO) < 0) {
            throw new BizException("单价不能为负");
        }
        if (wineRepository.existsByCode(input.code)) {
            throw new BizException("酒款编号已存在，不可重复");
        }

        Wine w = new Wine();
        w.code = input.code;
        w.name = input.name;
        w.region = input.region;
        w.type = input.type;
        w.stock = 0;
        w.price = input.price;
        w.status = (input.status == null || input.status.isBlank()) ? "在售" : input.status;
        if (!Dict.isValidWineStatus(w.status)) {
            throw new BizException("状态必须是：在售/停售 之一");
        }
        return wineRepository.save(w);
    }

    // ---------------- 更新（部分字段） ----------------
    public Wine update(Long id, WineInput input) {
        Wine w = get(id);
        if (input.code != null) {
            if (input.code.isBlank()) {
                throw new BizException("酒款编号不能为空");
            }
            if (!input.code.equals(w.code) && wineRepository.existsByCodeAndIdNot(input.code, id)) {
                throw new BizException("酒款编号已存在，不可重复");
            }
            w.code = input.code;
        }
        if (input.name != null) {
            if (input.name.isBlank()) {
                throw new BizException("酒名不能为空");
            }
            w.name = input.name;
        }
        if (input.region != null) {
            if (!Dict.isValidWineRegion(input.region)) {
                throw new BizException("产区必须是：国产/法国/智利/澳洲/其他 之一");
            }
            w.region = input.region;
        }
        if (input.type != null) {
            if (!Dict.isValidWineType(input.type)) {
                throw new BizException("类型必须是：红/白/起泡/甜 之一");
            }
            w.type = input.type;
        }
        if (input.stock != null && input.stock != w.stock) {
            // 不允许绕过批次规则直接改酒款库存
            throw new BizException(
                    "酒款库存与窖藏批次联动，不能直接修改；如数据不一致，请使用「按批次校准库存」（当前账面 "
                            + w.stock + "，提交值 " + input.stock + "）");
        }
        if (input.price != null) {
            if (input.price.compareTo(BigDecimal.ZERO) < 0) {
                throw new BizException("单价不能为负");
            }
            w.price = input.price;
        }
        if (input.status != null && !input.status.isBlank() && !input.status.equals(w.status)) {
            // 在售/停售只能经「停售/复售」动作流转（含在途业务与批次资格核验），PUT 一律拒绝，防止绕过保护
            throw new BizException("酒款在售/停售状态不能手工改写，请使用「停售/复售」业务动作");
        }
        return wineRepository.save(w);
    }

    // ---------------- 库存校准：按各可售批次可用瓶数重算（数据不一致时的恢复入口） ----------------
    @Transactional
    public Wine reconcileStock(Long id) {
        Wine w = getLocked(id);
        int expected = batchRepository.findForUpdateByWineIdAndStatus(w.id, Dict.BATCH_SELLABLE).stream()
                .mapToInt(b -> b.bottleCount - b.soldCount)
                .sum();
        if (w.stock == expected) {
            throw new BizException("酒款「" + w.name + "」库存与批次可用瓶数一致（均为 " + expected + "），无需校准");
        }
        w.stock = expected;
        return wineRepository.save(w);
    }
}
