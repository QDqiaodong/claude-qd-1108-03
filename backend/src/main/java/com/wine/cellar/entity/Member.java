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

    /** 乐观锁版本号：资料编辑/积分变动共用，过期提交（含与出窖、退窖并发）一律拒绝，绝不互相覆盖 */
    @Version
    @Column(name = "version", nullable = false)
    public Long version;

    @Column(name = "phone", nullable = false, unique = true, length = 24)
    public String phone;          // 手机号

    @Column(name = "name", nullable = false, length = 32)
    public String name;           // 昵称

    @Column(name = "level", nullable = false, length = 16)
    public String level;          // 等级（只读派生字段）：始终由累计积分按阈值唯一决定，普通/银卡/黑卡

    @Column(name = "points", nullable = false)
    public Integer points;        // 积分

    @Column(name = "created_at")
    public LocalDateTime createdAt = LocalDateTime.now();
}
