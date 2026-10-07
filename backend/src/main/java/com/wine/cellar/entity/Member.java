package com.wine.cellar.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "member")
public class Member {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "phone", nullable = false, unique = true, length = 24)
    public String phone;          // 手机号

    @Column(name = "name", nullable = false, length = 32)
    public String name;           // 昵称

    @Column(name = "level", nullable = false, length = 16)
    public String level;          // 等级: 普通/银卡/黑卡（仅由累计积分按阈值推导，不可手工提交）

    @Column(name = "points", nullable = false)
    public Integer points;        // 积分（非负）

    /** 乐观锁版本号：资料编辑与出窖/退窖/加减积分并发时，过期提交一律拒绝，防止互相覆盖丢失积分 */
    @Version
    @Column(name = "version", nullable = false)
    public Long version = 0L;

    @Column(name = "created_at")
    public LocalDateTime createdAt = LocalDateTime.now();
}
