package com.wine.cellar.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "sale_out_item")
public class SaleOutItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "sale_id", nullable = false)
    public Long saleId;           // 关联出窖单

    @Column(name = "wine_id", nullable = false)
    public Long wineId;           // 关联酒款

    @Column(name = "batch_id", nullable = false)
    public Long batchId;          // 实际扣减的窖藏批次（FIFO 分配后逐批留痕）

    @Column(name = "qty", nullable = false)
    public Integer qty;           // 本批次扣减数量
}
