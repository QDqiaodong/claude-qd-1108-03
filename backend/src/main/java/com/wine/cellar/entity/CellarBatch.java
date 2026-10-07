package com.wine.cellar.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

@Entity
@Table(name = "cellar_batch")
public class CellarBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "wine_id", nullable = false)
    public Long wineId;            // 关联酒款 id

    @Column(name = "batch_no", nullable = false, unique = true, length = 32)
    public String batchNo;         // 批次号

    @Column(name = "bottle_count", nullable = false)
    public Integer bottleCount;    // 总瓶数

    @Column(name = "sold_count", nullable = false)
    public Integer soldCount = 0;  // 已出窖扣减瓶数（联动维护）

    @Column(name = "cellar_date", nullable = false)
    public LocalDate cellarDate;   // 入窖日

    @Column(name = "status", nullable = false, length = 16)
    public String status;          // 窖藏中/可售/已售罄

    @Column(name = "created_at")
    public LocalDateTime createdAt = LocalDateTime.now();

    /** 可用瓶数 = 总瓶数 - 已扣减瓶数 */
    @Transient
    public int getAvailableBottles() {
        return bottleCount - soldCount;
    }
}
