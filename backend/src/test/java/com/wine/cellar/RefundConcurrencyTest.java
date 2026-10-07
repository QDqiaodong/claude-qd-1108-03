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

import com.wine.cellar.entity.SaleOut;
import com.wine.cellar.service.SaleOutService;

/**
 * 并发重复提交退窖：仅一个请求成功，另一个被状态/行锁拦截，库存与批次只回补一次。
 * 真实线程跑（不套外层事务），用独立 context，结束后销毁重建以免污染其他用例。
 */
@SpringBootTest
class RefundConcurrencyTest {

    @Autowired SaleOutService saleService;
    @Autowired com.wine.cellar.service.WineService wineService;
    @Autowired com.wine.cellar.service.CellarBatchService batchService;

    @Test
    @org.springframework.test.annotation.DirtiesContext
    void duplicateRefundOnlyOneSucceeds() throws Exception {
        // 先出窖 50 瓶，把 B-T-01 扣到已售罄
        SaleOutService.SaleCreateInput in = new SaleOutService.SaleCreateInput();
        SaleOutService.SaleItemInput it = new SaleOutService.SaleItemInput();
        it.wineId = 1L; it.qty = 50;
        in.items = java.util.List.of(it);
        SaleOut sale = saleService.create(in);
        assertEquals(30, wineService.get(1L).stock);
        assertEquals("已售罄", batchService.get(1L).status);

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    saleService.refund(sale.id);
                    ok.incrementAndGet();
                } catch (Exception e) {
                    rejected.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));

        assertEquals(1, ok.get(), "只能有一次退窖成功");
        assertEquals(1, rejected.get(), "重复退窖必须被拒绝");
        // 只回补一次，没有翻倍
        assertEquals(80, wineService.get(1L).stock);
        assertEquals(0, batchService.get(1L).soldCount);
        assertEquals("可售", batchService.get(1L).status);
    }
}
