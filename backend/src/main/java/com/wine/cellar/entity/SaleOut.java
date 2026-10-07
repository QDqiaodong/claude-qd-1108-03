package com.wine.cellar.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "sale_out")
public class SaleOut {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "member_id")
    public Long memberId;         // 关联会员（散客可空）

    @Column(name = "total", nullable = false, precision = 12, scale = 2)
    public BigDecimal total;      // 出窖总额（自动计算）

    @Column(name = "points_earned", nullable = false)
    public Integer pointsEarned = 0;  // 本单实际产生积分（退窖按此精确回滚，散客为 0）

    @Column(name = "status", nullable = false, length = 16)
    public String status;         // 已出窖/已退窖

    @Column(name = "created_at")
    public LocalDateTime createdAt = LocalDateTime.now();
}
