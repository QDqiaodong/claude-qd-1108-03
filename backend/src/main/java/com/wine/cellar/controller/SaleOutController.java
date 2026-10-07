package com.wine.cellar.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.wine.cellar.common.ApiResponse;
import com.wine.cellar.entity.SaleOut;
import com.wine.cellar.entity.SaleOutItem;
import com.wine.cellar.service.SaleOutService;

@RestController
@RequestMapping("/api/sale-outs")
public class SaleOutController {

    private final SaleOutService saleService;

    public SaleOutController(SaleOutService saleService) {
        this.saleService = saleService;
    }

    @GetMapping
    public ApiResponse<List<SaleOut>> list(@RequestParam(required = false) String status) {
        return ApiResponse.success(saleService.list(status));
    }

    /** 出窖单的逐批次扣减明细 */
    @GetMapping("/{id}/items")
    public ApiResponse<List<SaleOutItem>> items(@PathVariable Long id) {
        return ApiResponse.success(saleService.items(id));
    }

    @PostMapping
    public ApiResponse<SaleOut> create(@RequestBody SaleOutService.SaleCreateInput input) {
        return ApiResponse.success(saleService.create(input));
    }

    @PostMapping("/{id}/refund")
    public ApiResponse<SaleOut> refund(@PathVariable Long id) {
        return ApiResponse.success(saleService.refund(id));
    }
}
