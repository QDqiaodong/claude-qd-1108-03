package com.wine.cellar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.wine.cellar.common.BizException;
import com.wine.cellar.entity.SaleOut;
import com.wine.cellar.entity.Wine;
import com.wine.cellar.repository.WineRepository;
import com.wine.cellar.service.CellarBatchService;
import com.wine.cellar.service.SaleOutService;
import com.wine.cellar.service.WineSaleStatusService;
import com.wine.cellar.service.WineService;

/**
 * 酒款停售与复售资格保护：
 * 停售拦截在途业务（未完成出窖单/待处理退窖明细）、复售重新核验批次/库存/冲正，
 * 重复提交与非法跳跃一律拒绝，出窖闸门与前台展示共用同一套资格结果。
 */
@SpringBootTest
@Transactional   // 每个用例结束自动回滚，共享同一套种子数据
class WineSaleStatusProtectionTest {

    @Autowired WineSaleStatusService saleStatusService;
    @Autowired SaleOutService saleService;
    @Autowired CellarBatchService batchService;
    @Autowired WineService wineService;
    @Autowired WineRepository wineRepository;

    // ---------------- 停售：在途出窖单阻断，退窖结案后放行 ----------------
    @Test
    void delistBlockedByInFlightSale_thenRefundUnblocks() {
        // 造一笔在途出窖单（已出窖未退窖）
        SaleOut sale = sell(null, 1L, 5);

        BizException ex = assertThrows(BizException.class, () -> saleStatusService.delist(1L));
        assertTrue(ex.getMessage().contains("在途业务"), ex.getMessage());
        assertTrue(ex.getMessage().contains("未完成出窖单 1 笔"), ex.getMessage());
        assertTrue(ex.getMessage().contains("#" + sale.id), ex.getMessage());
        assertTrue(ex.getMessage().contains("待处理退窖明细"), ex.getMessage());
        assertTrue(ex.getMessage().contains("销售出窖"), ex.getMessage());   // 可继续处理的入口
        // 保持原状态
        assertEquals("在售", wineService.get(1L).status);

        // 退窖结案后停售成功
        saleService.refund(sale.id);
        Wine delisted = saleStatusService.delist(1L);
        assertEquals("停售", delisted.status);
        assertEquals("停售", wineService.get(1L).status);
    }

    // ---------------- 停售：多笔在途单聚合提示 ----------------
    @Test
    void delistBlockMessageAggregatesAllInFlightOrders() {
        SaleOut s1 = sell(null, 1L, 2);
        SaleOut s2 = sell(1L, 1L, 3);
        BizException ex = assertThrows(BizException.class, () -> saleStatusService.delist(1L));
        assertTrue(ex.getMessage().contains("未完成出窖单 2 笔"), ex.getMessage());
        assertTrue(ex.getMessage().contains("#" + s1.id), ex.getMessage());
        assertTrue(ex.getMessage().contains("#" + s2.id), ex.getMessage());
        assertTrue(ex.getMessage().contains("共 5 瓶"), ex.getMessage());
        assertEquals("在售", wineService.get(1L).status);
    }

    // ---------------- 停售成功：同一套资格结果同步关闭销售入口 ----------------
    @Test
    void delistClosesSaleEntryAndEligibilityEverywhere() {
        // 停售前资格：可售
        WineSaleStatusService.Eligibility before = saleStatusService.eligibilityOf(1L);
        assertTrue(before.saleEligible);
        assertEquals(80, before.sellableQty);
        assertNull(before.blockReason());

        saleStatusService.delist(1L);

        // 资格结果：不可售，原因=已停售（列表填充与单点查询同一口径）
        WineSaleStatusService.Eligibility after = saleStatusService.eligibilityOf(1L);
        assertFalse(after.saleEligible);
        assertTrue(after.blockReason().contains("停售"));

        List<Wine> wines = wineService.list(null, null, null);
        saleStatusService.fillEligibility(wines);
        Wine w1 = wines.stream().filter(w -> w.id == 1L).findFirst().orElseThrow();
        assertEquals(Boolean.FALSE, w1.saleEligible);
        assertEquals(80, w1.sellableQty);   // 批次可售量仍如实展示，但资格已关闭
        assertNotNull(w1.saleBlockReason);

        // 出窖闸门同步拦截（后台已停售，前台不可再选购）
        BizException ex = assertThrows(BizException.class, () -> sell(null, 1L, 1));
        assertTrue(ex.getMessage().contains("停售"), ex.getMessage());
        // 库存与批次未被停售动作扰动
        assertEquals(80, wineService.get(1L).stock);
        assertEquals(80, batchService.sumAvailable(1L));
    }

    // ---------------- 停售：重复提交与非法跳跃 ----------------
    @Test
    void delistRejectsDuplicateAndIllegalJump() {
        saleStatusService.delist(2L);   // 酒款2 无在途业务，可停售
        assertEquals("停售", wineService.get(2L).status);

        // 重复停售被拒
        BizException ex = assertThrows(BizException.class, () -> saleStatusService.delist(2L));
        assertTrue(ex.getMessage().contains("请勿重复提交或跳跃流转"), ex.getMessage());
        assertEquals("停售", wineService.get(2L).status);

        // 在售酒款直接复售 = 非法跳跃
        BizException ex2 = assertThrows(BizException.class, () -> saleStatusService.relist(1L));
        assertTrue(ex2.getMessage().contains("仅「停售」酒款可复售"), ex2.getMessage());
        assertEquals("在售", wineService.get(1L).status);
    }

    // ---------------- 复售：成功路径（种子酒款3 停售 + 可售批次10瓶 + 账实一致） ----------------
    @Test
    void relistSuccessRestoresSaleSelection() {
        Wine w3 = wineService.get(3L);
        assertEquals("停售", w3.status);
        assertFalse(saleStatusService.eligibilityOf(3L).saleEligible);

        Wine relisted = saleStatusService.relist(3L);
        assertEquals("在售", relisted.status);

        // 复售后资格恢复，前台可售量 = 可售批次合计
        WineSaleStatusService.Eligibility r = saleStatusService.eligibilityOf(3L);
        assertTrue(r.saleEligible);
        assertEquals(10, r.sellableQty);
        assertEquals(10, batchService.sumAvailable(3L));

        // 销售选择恢复：可正常出窖
        SaleOut sale = sell(null, 3L, 4);
        assertNotNull(sale.id);
        assertEquals(6, wineService.get(3L).stock);

        // 重复复售被拒
        BizException ex = assertThrows(BizException.class, () -> saleStatusService.relist(3L));
        assertTrue(ex.getMessage().contains("请勿重复提交或跳跃流转"), ex.getMessage());
    }

    // ---------------- 复售：无可售批次（全部售罄/未放行）被拒 ----------------
    @Test
    void relistBlockedWhenNoSellableBatch() {
        saleStatusService.delist(2L);   // 酒款2 仅有窖藏中批次
        BizException ex = assertThrows(BizException.class, () -> saleStatusService.relist(2L));
        assertTrue(ex.getMessage().contains("没有可用的可售批次"), ex.getMessage());
        assertTrue(ex.getMessage().contains("窖藏批次"), ex.getMessage());   // 处理入口
        assertEquals("停售", wineService.get(2L).status);   // 原停售状态不变

        // 先放行批次再复售 → 通过，库存随放行联动
        batchService.release(3L);
        Wine relisted = saleStatusService.relist(2L);
        assertEquals("在售", relisted.status);
        assertEquals(40, wineService.get(2L).stock);
        assertTrue(saleStatusService.eligibilityOf(2L).saleEligible);
    }

    // ---------------- 复售：库存镜像不一致被拒，校准后放行 ----------------
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DirtiesContext
    void relistBlockedByInconsistentStock_thenReconcileUnblocks() {
        // 人为改坏停售酒款3的账面库存（模拟历史脏数据）
        Wine w3 = wineService.get(3L);
        w3.stock = 99;
        wineRepository.saveAndFlush(w3);

        BizException ex = assertThrows(BizException.class, () -> saleStatusService.relist(3L));
        assertTrue(ex.getMessage().contains("不一致"), ex.getMessage());
        assertTrue(ex.getMessage().contains("按批次校准库存"), ex.getMessage());
        assertEquals("停售", wineService.get(3L).status);

        // 校准后复售成功
        wineService.reconcileStock(3L);
        Wine relisted = saleStatusService.relist(3L);
        assertEquals("在售", relisted.status);
        assertEquals(10, relisted.stock);
    }

    // ---------------- 复售：存在未完成冲正（在途出窖单）被拒 ----------------
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DirtiesContext
    void relistBlockedByPendingReversal() {
        // 在途出窖单 + 历史遗留停售（模拟保护上线前的脏路径：有单仍被停售）
        SaleOut sale = sell(null, 1L, 5);
        Wine w1 = wineService.get(1L);
        w1.status = "停售";
        wineRepository.saveAndFlush(w1);

        BizException ex = assertThrows(BizException.class, () -> saleStatusService.relist(1L));
        assertTrue(ex.getMessage().contains("未完成冲正"), ex.getMessage());
        assertTrue(ex.getMessage().contains("#" + sale.id), ex.getMessage());
        assertTrue(ex.getMessage().contains("销售出窖"), ex.getMessage());
        assertEquals("停售", wineService.get(1L).status);

        // 退窖结案后复售成功
        saleService.refund(sale.id);
        Wine relisted = saleStatusService.relist(1L);
        assertEquals("在售", relisted.status);
        assertEquals(80, wineService.get(1L).stock);
    }

    // ---------------- 资格口径：与出窖闸门一致（库存不足/不一致同样体现） ----------------
    @Test
    void eligibilityMatchesSaleGate() {
        // 酒款2：在售但无可售批次 → 资格不可售，出窖同样被拒
        WineSaleStatusService.Eligibility r2 = saleStatusService.eligibilityOf(2L);
        assertFalse(r2.saleEligible);
        assertEquals(0, r2.sellableQty);
        assertThrows(BizException.class, () -> sell(null, 2L, 1));

        // 酒款1：可售 80，资格与批次合计一致
        WineSaleStatusService.Eligibility r1 = saleStatusService.eligibilityOf(1L);
        assertTrue(r1.saleEligible);
        assertEquals(batchService.sumAvailable(1L), r1.sellableQty);
    }

    // ---------------- 状态不能手工改写（绕过停售/复售保护的通道已关闭） ----------------
    @Test
    void cannotHandEditWineStatus() {
        WineService.WineInput in = new WineService.WineInput();
        in.status = "停售";
        BizException ex = assertThrows(BizException.class, () -> wineService.update(1L, in));
        assertTrue(ex.getMessage().contains("停售/复售"), ex.getMessage());
        assertEquals("在售", wineService.get(1L).status);

        // 回传当前状态（不变）不视为改写，允许保存其他字段
        WineService.WineInput same = new WineService.WineInput();
        same.status = "在售";
        same.name = "测试解百纳·改";
        Wine updated = wineService.update(1L, same);
        assertEquals("在售", updated.status);
        assertEquals("测试解百纳·改", updated.name);
    }

    private SaleOut sell(Long memberId, long wineId, int qty) {
        SaleOutService.SaleCreateInput in = new SaleOutService.SaleCreateInput();
        in.memberId = memberId;
        SaleOutService.SaleItemInput it = new SaleOutService.SaleItemInput();
        it.wineId = wineId;
        it.qty = qty;
        in.items = List.of(it);
        return saleService.create(in);
    }
}
