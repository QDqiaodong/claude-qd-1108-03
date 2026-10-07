package com.wine.cellar.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.wine.cellar.common.ApiResponse;
import com.wine.cellar.entity.Wine;
import com.wine.cellar.service.WineSaleStatusService;
import com.wine.cellar.service.WineService;

@RestController
@RequestMapping("/api/wines")
public class WineController {

    private final WineService wineService;
    private final WineSaleStatusService saleStatusService;

    public WineController(WineService wineService, WineSaleStatusService saleStatusService) {
        this.wineService = wineService;
        this.saleStatusService = saleStatusService;
    }

    @GetMapping
    public ApiResponse<List<Wine>> list(
            @RequestParam(required = false) String region,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status) {
        List<Wine> wines = wineService.list(region, type, status);
        // 随列表返回同一套可售资格结果：酒款列表 / 批次页 / 销售出窖页看到的一致
        saleStatusService.fillEligibility(wines);
        return ApiResponse.success(wines);
    }

    @PostMapping
    public ApiResponse<Wine> create(@RequestBody WineService.WineInput input) {
        return ApiResponse.success(wineService.create(input));
    }

    @PutMapping("/{id}")
    public ApiResponse<Wine> update(@PathVariable Long id, @RequestBody WineService.WineInput input) {
        return ApiResponse.success(wineService.update(id, input));
    }

    /** 库存校准：按各可售批次可用瓶数重算酒款库存（批次/酒款不一致时的恢复入口） */
    @PostMapping("/{id}/reconcile-stock")
    public ApiResponse<Wine> reconcileStock(@PathVariable Long id) {
        return ApiResponse.success(wineService.reconcileStock(id));
    }

    /** 停售：存在在途业务（未完成出窖单/待处理退窖明细）时拒绝并保持原状态 */
    @PostMapping("/{id}/delist")
    public ApiResponse<Wine> delist(@PathVariable Long id) {
        Wine w = saleStatusService.delist(id);
        return ApiResponse.success("酒款已停售：前台销售入口、可售标记与批次可售展示已同步关闭", w);
    }

    /** 复售：重新核验可售批次/库存镜像/在途冲正，全部通过才恢复销售选择 */
    @PostMapping("/{id}/relist")
    public ApiResponse<Wine> relist(@PathVariable Long id) {
        Wine w = saleStatusService.relist(id);
        return ApiResponse.success("酒款已复售：销售选择已恢复，可售量与可售批次合计一致", w);
    }

    /** 单个酒款的可售资格结果（与列表/批次页/销售页同一口径） */
    @GetMapping("/{id}/sale-eligibility")
    public ApiResponse<WineSaleStatusService.Eligibility> saleEligibility(@PathVariable Long id) {
        return ApiResponse.success(saleStatusService.eligibilityOf(id));
    }
}
