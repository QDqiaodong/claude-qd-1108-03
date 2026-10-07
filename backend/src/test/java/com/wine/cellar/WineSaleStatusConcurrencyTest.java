package com.wine.cellar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import com.wine.cellar.entity.Wine;
import com.wine.cellar.repository.SaleOutItemRepository;
import com.wine.cellar.repository.SaleOutRepository;
import com.wine.cellar.service.CellarBatchService;
import com.wine.cellar.service.SaleOutService;
import com.wine.cellar.service.WineSaleStatusService;
import com.wine.cellar.service.WineService;

/**
 * 停售/复售并发保护：行锁 + 状态复查保证并发提交只产生一个完整结果，不留部分更新。
 * 真实线程跑（不套外层事务），用独立 context，结束后销毁重建以免污染其他用例。
 */
@SpringBootTest
class WineSaleStatusConcurrencyTest {

    @Autowired WineSaleStatusService saleStatusService;
    @Autowired SaleOutService saleService;
    @Autowired WineService wineService;
    @Autowired CellarBatchService batchService;
    @Autowired SaleOutRepository saleRepository;
    @Autowired SaleOutItemRepository itemRepository;

    // ---------------- 停售 vs 出窖并发：只产生一个完整结果 ----------------
    @Test
    @DirtiesContext
    void concurrentDelistAndSaleOnlyOneCompletes() throws Exception {
        AtomicInteger delistOk = new AtomicInteger();
        AtomicInteger saleOk = new AtomicInteger();
        runTwoThreads(
                () -> { saleStatusService.delist(1L); delistOk.incrementAndGet(); },
                () -> { sell(1L, 50); saleOk.incrementAndGet(); });

        // 恰有一个成功：先拿到行锁的一方完成，另一方被状态/在途检查拒绝
        assertEquals(1, delistOk.get() + saleOk.get(), "停售与出窖并发只能有一个完整生效");

        Wine w = wineService.get(1L);
        // 无论哪种结果都不留部分更新：账实一致
        assertEquals(batchService.sumAvailable(1L), w.stock.intValue(), "账面库存必须等于可售批次合计");
        if ("停售".equals(w.status)) {
            // 停售先生效：无出窖单、库存未动
            assertEquals(80, w.stock);
            assertTrue(saleRepository.findAll().isEmpty());
            assertTrue(itemRepository.findAll().isEmpty());
        } else {
            // 出窖先生效：停售被在途业务拦截，酒款保持在售
            assertEquals("在售", w.status);
            assertEquals(30, w.stock);
            assertEquals(1, saleRepository.findAll().size());
            assertEquals(1, itemRepository.findAll().size());
        }
    }

    // ---------------- 重复停售并发：仅一次成功 ----------------
    @Test
    @DirtiesContext
    void concurrentDuplicateDelistOnlyOneSucceeds() throws Exception {
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        runTwoThreads(
                () -> { saleStatusService.delist(2L); ok.incrementAndGet(); },
                () -> { saleStatusService.delist(2L); ok.incrementAndGet(); },
                rejected);

        assertEquals(1, ok.get(), "重复停售只能成功一次");
        assertEquals(1, rejected.get(), "另一次必须被状态复查拒绝");
        assertEquals("停售", wineService.get(2L).status);
    }

    // ---------------- 重复复售并发：仅一次成功 ----------------
    @Test
    @DirtiesContext
    void concurrentDuplicateRelistOnlyOneSucceeds() throws Exception {
        assertEquals("停售", wineService.get(3L).status);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        runTwoThreads(
                () -> { saleStatusService.relist(3L); ok.incrementAndGet(); },
                () -> { saleStatusService.relist(3L); ok.incrementAndGet(); },
                rejected);

        assertEquals(1, ok.get(), "重复复售只能成功一次");
        assertEquals(1, rejected.get(), "另一次必须被状态复查拒绝");
        Wine w3 = wineService.get(3L);
        assertEquals("在售", w3.status);
        // 复售结果完整：资格恢复且可售量与批次合计一致
        assertTrue(saleStatusService.eligibilityOf(3L).saleEligible);
        assertEquals(batchService.sumAvailable(3L), w3.stock.intValue());
    }

    // ---------------- 工具 ----------------
    private void runTwoThreads(ThrowingTask a, ThrowingTask b) throws Exception {
        runTwoThreads(a, b, new AtomicInteger());
    }

    /** 两个任务同时起跑；未计入成功计数器的异常一律视为业务拒绝并计数 */
    private void runTwoThreads(ThrowingTask a, ThrowingTask b, AtomicInteger rejected) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        for (ThrowingTask task : List.of(a, b)) {
            pool.submit(() -> {
                try {
                    start.await();
                    task.run();
                } catch (Exception e) {
                    rejected.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));
    }

    private void sell(long wineId, int qty) {
        SaleOutService.SaleCreateInput in = new SaleOutService.SaleCreateInput();
        SaleOutService.SaleItemInput it = new SaleOutService.SaleItemInput();
        it.wineId = wineId;
        it.qty = qty;
        in.items = List.of(it);
        saleService.create(in);
    }

    @FunctionalInterface
    private interface ThrowingTask {
        void run() throws Exception;
    }
}
