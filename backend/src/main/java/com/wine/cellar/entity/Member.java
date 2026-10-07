package com.wine.cellar.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

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
    public String level;          // 等级: 普通/银卡/黑卡

    @Column(name = "points", nullable = false)
    public Integer points;        // 积分

    @Column(name = "created_at")
    public LocalDateTime createdAt = LocalDateTime.now();
}
