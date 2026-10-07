package com.wine.cellar.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

@Entity
@Table(name = "wine")
public class Wine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "code", nullable = false, unique = true, length = 32)
    public String code;            // 酒款编号

    @Column(name = "name", nullable = false, length = 128)
    public String name;            // 酒名

    @Column(name = "region", nullable = false, length = 16)
    public String region;          // 产区: 国产/法国/智利/澳洲/其他

    @Column(name = "type", nullable = false, length = 16)
    public String type;            // 类型: 红/白/起泡/甜

    @Column(name = "stock", nullable = false)
    public Integer stock;          // 库存

    @Column(name = "price", nullable = false, precision = 10, scale = 2)
    public BigDecimal price;       // 单价（用于销售计价）

    @Column(name = "status", nullable = false, length = 16)
    public String status;          // 在售/停售

    @Column(name = "created_at")
    public LocalDateTime createdAt = LocalDateTime.now();

    // ---------------- 可售资格（后端统一计算，随查询返回；酒款列表/批次页/销售出窖页共用同一套结果） ----------------
    @Transient
    public Boolean saleEligible;     // 是否可进入销售选择（在售 + 账实一致 + 有可售批次瓶数）

    @Transient
    public Integer sellableQty;      // 可售批次可用瓶数合计（前台展示可售量与批次合计一致）

    @Transient
    public Boolean stockConsistent;  // 库存镜像与可售批次合计是否一致

    @Transient
    public String saleBlockReason;   // 不可售原因（可售时为 null）
}
