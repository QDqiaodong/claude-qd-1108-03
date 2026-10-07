package com.wine.cellar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.wine.cellar.service.SaleOutService;

/**
 * 并发出窖：两单竞争 80 瓶库存（各要 50），只能成 1 单，不允许超卖、不允许留半成功数据。
 */
@SpringBootTest
class SaleConcurrencyTest {

    @Autowired SaleOutService saleService;
    @Autowired com.wine.cellar.service.WineService wineService;
    @Autowired com.wine.cellar.service.CellarBatchService batchService;

    @Test
    @org.springframework.test.annotation.DirtiesContext
    void concurrentSalesCannotOversell() throws Exception {
        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    SaleOutService.SaleCreateInput in = new SaleOutService.SaleCreateInput();
                    SaleOutService.SaleItemInput it = new SaleOutService.SaleItemInput();
                    it.wineId = 1L; it.qty = 50;
                    in.items = java.util.List.of(it);
                    saleService.create(in);
                    ok.incrementAndGet();
                } catch (Exception e) {
                    rejected.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));

        assertEquals(1, ok.get(), "仅一单能成交");
        assertEquals(1, rejected.get(), "另一单必须因库存不足被拒");
        // 库存只扣 50，批次可售合计仍为 80 体系（账面 = 批次合计）
        assertEquals(30, wineService.get(1L).stock);
        assertEquals(30, batchService.sumAvailable(1L));
    }
}
