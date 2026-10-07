package com.wine.cellar.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.wine.cellar.entity.CellarBatch;

import jakarta.persistence.LockModeType;

public interface CellarBatchRepository extends JpaRepository<CellarBatch, Long> {

    Optional<CellarBatch> findByBatchNo(String batchNo);

    boolean existsByBatchNo(String batchNo);

    boolean existsByBatchNoAndIdNot(String batchNo, Long id);

    /** 某酒款的全部批次，按入窖日升序、同日按 id 升序（FIFO 依据） */
    List<CellarBatch> findByWineIdOrderByCellarDateAscIdAsc(Long wineId);

    /**
     * 行锁读取某酒款指定状态的批次（出窖/退窖/放行联动时使用，避免并发扣减丢失更新）。
     * 同样按入窖日、id 升序加锁，配合酒款 id 升序的全局加锁顺序防止死锁。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from CellarBatch b where b.wineId = :wineId and b.status = :status order by b.cellarDate asc, b.id asc")
    List<CellarBatch> findForUpdateByWineIdAndStatus(@Param("wineId") Long wineId,
                                                     @Param("status") String status);

    /** 行锁读取单个批次 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from CellarBatch b where b.id = :id")
    Optional<CellarBatch> findLockingById(@Param("id") Long id);

    List<CellarBatch> findByStatus(String status);
}
