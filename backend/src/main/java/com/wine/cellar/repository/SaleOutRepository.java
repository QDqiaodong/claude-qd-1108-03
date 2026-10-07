package com.wine.cellar.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.wine.cellar.entity.SaleOut;

import jakarta.persistence.LockModeType;

public interface SaleOutRepository extends JpaRepository<SaleOut, Long> {

    List<SaleOut> findByStatus(String status);

    /** 行锁读取出窖单（退窖防重复提交） */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SaleOut s where s.id = :id")
    Optional<SaleOut> findLockingById(@Param("id") Long id);
}
