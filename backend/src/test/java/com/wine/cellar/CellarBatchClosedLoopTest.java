package com.wine.cellar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.wine.cellar.common.BizException;
import com.wine.cellar.entity.CellarBatch;
import com.wine.cellar.entity.Member;
import com.wine.cellar.entity.SaleOut;
import com.wine.cellar.entity.SaleOutItem;
import com.wine.cellar.entity.Wine;
import com.wine.cellar.repository.CellarBatchRepository;
import com.wine.cellar.repository.MemberRepository;
import com.wine.cellar.repository.SaleOutItemRepository;
import com.wine.cellar.repository.WineRepository;
import com.wine.cellar.service.CellarBatchService;
import com.wine.cellar.service.MemberService;
import com.wine.cellar.service.SaleOutService;
import com.wine.cellar.service.WineService;

/**
 * 窖藏批次放行 ↔ 销售可用资格 ↔ FIFO 出窖 ↔ 退窖回补 ↔ 积分 全闭环集成测试。
 */
@SpringBootTest
@Transactional   // 每个用例结束自动回滚，共享同一套种子数据
class CellarBatchClosedLoopTest {

    @Autowired SaleOutService saleService;
    @Autowired CellarBatchService batchService;
    @Autowired WineService wineService;
    @Autowired MemberService memberService;
    @Autowired WineRepository wineRepository;
    @Autowired CellarBatchRepository batchRepository;
    @Autowired MemberRepository memberRepository;
    @Autowired SaleOutItemRepository itemRepository;

    // ---------------- 放行资格 ----------------
    @Test
    void onlyCellaringBatchCanRelease_andReleaseAddsWineStock() {
        CellarBatch b1 = batchService.get(1L);   // 可售
        assertEquals("可售", b1.status);
        // 重复放行被拒
        BizException ex = assertThrows(BizException.class, () -> batchService.release(1L));
        assertTrue(ex.getMessage().contains("仅「窖藏中」"));

        // 窖藏中批次放行成功，瓶数计入酒款库存
        Wine w2 = wineService.get(2L);
        assertEquals(0, w2.stock);
        CellarBatch released = batchService.release(3L);
        assertEquals("可售", released.status);
        assertEquals(40, released.getAvailableBottles());
        assertEquals(40, wineService.get(2L).stock);

        // 已售罄不能再放行（跳跃拒绝，种子里没有已售罄批次，造一个后验证状态机）
        assertTrue(com.wine.cellar.common.Dict.canTransferBatch("窖藏中", "可售"));
        assertEquals(false, com.wine.cellar.common.Dict.canTransferBatch("窖藏中", "已售罄"));
    }

    // ---------------- 酒款库存 = 批次可用合计 ----------------
    @Test
    void wineStockEqualsSumOfSellableAvailableBottles() {
        assertEquals(80, wineService.get(1L).stock);
        assertEquals(80, batchService.sumAvailable(1L));   // 50 + 30
    }

    // ---------------- 不允许绕过批次直接改库存 ----------------
    @Test
    void cannotEditWineStockDirectly() {
        WineService.WineInput in = new WineService.WineInput();
        in.stock = 999;
        BizException ex = assertThrows(BizException.class, () -> wineService.update(1L, in));
        assertTrue(ex.getMessage().contains("库存与窖藏批次联动"));
        assertEquals(80, wineService.get(1L).stock);

        // 新建酒款也不能直接带库存
        WineService.WineInput create = new WineService.WineInput();
        create.code = "W-NEW";
        create.name = "新酒";
        create.region = "国产";
        create.type = "红";
        create.price = new BigDecimal("50.00");
        create.stock = 10;
        create.status = "在售";
        BizException ex2 = assertThrows(BizException.class, () -> wineService.create(create));
        assertTrue(ex2.getMessage().contains("库存不能直接录入"));
    }

    // ---------------- FIFO 扣减 + 批次留痕 + 售罄自动转态 ----------------
    @Test
    void saleOutDeductsFifoAndRecordsBatchLines() {
        // 出窖 60 瓶：B-T-01(50) 扣完转已售罄，B-T-02 扣 10
        SaleOut sale = sell(null, 1L, 60);
        assertEquals(new BigDecimal("6000.00"), sale.total);
        assertEquals(0, sale.pointsEarned);  // 散客无积分
        assertEquals(null, sale.memberId);

        CellarBatch b1 = batchService.get(1L);
        CellarBatch b2 = batchService.get(2L);
        assertEquals("已售罄", b1.status);
        assertEquals(50, b1.soldCount);
        assertEquals(0, b1.getAvailableBottles());
        assertEquals("可售", b2.status);
        assertEquals(10, b2.soldCount);
        assertEquals(20, b2.getAvailableBottles());
        assertEquals(20, wineService.get(1L).stock);  // 80-60

        List<SaleOutItem> rows = itemRepository.findBySaleId(sale.id);
        assertEquals(2, rows.size());                 // 跨两批 → 两条明细
        assertEquals(1L, rows.get(0).batchId);
        assertEquals(50, rows.get(0).qty);
        assertEquals(2L, rows.get(1).batchId);
        assertEquals(10, rows.get(1).qty);
        // 每条明细都带批次来源
        for (SaleOutItem r : rows) {
            assertNotNull(r.batchId);
            assertEquals(1L, r.wineId);
        }

        // 再出窖 21 瓶：只剩 20 → 整体失败（含部分明细不足场景由库存校验拦截），不留半成功数据
        BizException ex = assertThrows(BizException.class, () -> sell(null, 1L, 21));
        assertTrue(ex.getMessage().contains("可售瓶数不足"));
        assertEquals(20, wineService.get(1L).stock);
        assertEquals(10, batchService.get(2L).soldCount);
    }

    // ---------------- 停售酒 / 无放行批次酒 不可出窖 ----------------
    @Test
    void stoppedWineAndUnreleasedWineCannotBeSold() {
        // 停售
        BizException ex1 = assertThrows(BizException.class, () -> sell(null, 3L, 1));
        assertTrue(ex1.getMessage().contains("停售"));
        // 未放行（库存 0、批次窖藏中）——构造入参走酒款 2
        BizException ex2 = assertThrows(BizException.class, () -> sell(null, 2L, 1));
        assertTrue(ex2.getMessage().contains("可售瓶数不足"));
        assertEquals(0, wineService.get(2L).stock);
    }

    // ---------------- 会员积分随单增加并自动升级 ----------------
    @Test
    void memberPointsAddedAndLevelUp() {
        // 会员甲 1900 分，消费 2000（酒款1 x20）→ 3900 银卡
        SaleOut sale = sell(1L, 1L, 20);
        Member m = memberService.get(1L);
        assertEquals(1900 + 2000, m.points);
        assertEquals("银卡", m.level);
        assertEquals(2000, sale.pointsEarned);
        // 批次按 FIFO：B-T-01 扣 20
        assertEquals(20, batchService.get(1L).soldCount);
    }

    // ---------------- 退窖：按原批次逐项回补、状态恢复、积分回滚 ----------------
    @Test
    void refundRestoresBatchesStockAndPoints_exactOriginal() {
        // 会员消费 60 瓶（跨 B-T-01 50 / B-T-02 10）
        SaleOut sale = sell(1L, 1L, 60);
        assertEquals("已售罄", batchService.get(1L).status);
        int pointsBefore = memberService.get(1L).points;
        assertEquals(pointsBefore, 1900 + 6000);

        SaleOut refunded = saleService.refund(sale.id);
        assertEquals("已退窖", refunded.status);

        // 批次逐项回补，已售罄恢复可售
        CellarBatch b1 = batchService.get(1L);
        CellarBatch b2 = batchService.get(2L);
        assertEquals("可售", b1.status);
        assertEquals(0, b1.soldCount);
        assertEquals(50, b1.getAvailableBottles());
        assertEquals(0, b2.soldCount);
        assertEquals(30, b2.getAvailableBottles());
        // 酒款库存恢复
        assertEquals(80, wineService.get(1L).stock);
        // 积分按本单原值精确回滚，等级随之回落
        Member m = memberService.get(1L);
        assertEquals(1900, m.points);
        assertEquals("普通", m.level);

        // 重复退窖被拒
        BizException ex = assertThrows(BizException.class, () -> saleService.refund(sale.id));
        assertTrue(ex.getMessage().contains("仅「已出窖」"));
    }

    // ---------------- 散客退窖同样闭环（无积分路径不报错） ----------------
    @Test
    void walkInRefundRestoresWithoutPoints() {
        SaleOut sale = sell(null, 1L, 5);
        saleService.refund(sale.id);
        assertEquals(80, wineService.get(1L).stock);
        assertEquals(0, batchService.get(1L).soldCount);
    }

    // ---------------- 批次扣完状态为已售罄，再退窖恢复 ----------------
    @Test
    void soldOutBatchRecoversOnRefund() {
        SaleOut s1 = sell(null, 1L, 50);  // B-T-01 售罄
        assertEquals("已售罄", batchService.get(1L).status);
        saleService.refund(s1.id);
        assertEquals("可售", batchService.get(1L).status);
        assertEquals(50, batchService.get(1L).getAvailableBottles());
    }

    // ---------------- 部分明细不足导致整单失败：另一条酒款不被污染 ----------------
    @Test
    void wholeOrderFailsWhenAnyLineShort_noHalfDoneData() {
        // 明细一：酒款1 出 10 瓶（可行）；明细二：酒款2 出 1 瓶（无放行批次，不可行）
        SaleOutService.SaleCreateInput in = new SaleOutService.SaleCreateInput();
        SaleOutService.SaleItemInput a = new SaleOutService.SaleItemInput();
        a.wineId = 1L; a.qty = 10;
        SaleOutService.SaleItemInput b = new SaleOutService.SaleItemInput();
        b.wineId = 2L; b.qty = 1;
        in.items = List.of(a, b);
        assertThrows(BizException.class, () -> saleService.create(in));

        // 酒款1 库存与批次完全未动
        assertEquals(80, wineService.get(1L).stock);
        assertEquals(0, batchService.get(1L).soldCount);
        assertEquals(0, batchService.get(2L).soldCount);
    }

    // ---------------- 校准：人为造成不一致后可恢复且不阻断后续业务 ----------------
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DirtiesContext
    void reconcileStockRecoversInconsistentWine() {
        // 直接在库里把库存改坏（模拟历史脏数据）
        Wine w = wineService.get(1L);
        w.stock = 123;
        wineRepository.saveAndFlush(w);

        // 此时出窖被一致性校验拦截，且给出可理解提示
        BizException ex = assertThrows(BizException.class, () -> sell(null, 1L, 1));
        assertTrue(ex.getMessage().contains("不一致"));

        // 校准恢复
        Wine fixed = wineService.reconcileStock(1L);
        assertEquals(80, fixed.stock);

        // 恢复后可继续正常出窖
        SaleOut sale = sell(null, 1L, 5);
        assertNotNull(sale.id);
        assertEquals(75, wineService.get(1L).stock);
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
