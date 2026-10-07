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
import com.wine.cellar.entity.CellarBatch;
import com.wine.cellar.service.CellarBatchService;

@RestController
@RequestMapping("/api/cellar-batches")
public class CellarBatchController {

    private final CellarBatchService batchService;

    public CellarBatchController(CellarBatchService batchService) {
        this.batchService = batchService;
    }

    @GetMapping
    public ApiResponse<List<CellarBatch>> list(@RequestParam(required = false) String status) {
        return ApiResponse.success(batchService.list(status));
    }

    @PostMapping
    public ApiResponse<CellarBatch> create(@RequestBody CellarBatchService.BatchInput input) {
        return ApiResponse.success(batchService.create(input));
    }

    @PutMapping("/{id}")
    public ApiResponse<CellarBatch> update(@PathVariable Long id, @RequestBody CellarBatchService.BatchInput input) {
        return ApiResponse.success(batchService.update(id, input));
    }

    @PostMapping("/{id}/release")
    public ApiResponse<CellarBatch> release(@PathVariable Long id) {
        return ApiResponse.success(batchService.release(id));
    }
}
