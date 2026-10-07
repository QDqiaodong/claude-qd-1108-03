package com.wine.cellar.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.wine.cellar.entity.SaleOutItem;

public interface SaleOutItemRepository extends JpaRepository<SaleOutItem, Long> {

    List<SaleOutItem> findBySaleId(Long saleId);

    /**
     * 某酒款在指定出窖单状态下的全部扣减明细（在途业务核查：
     * 传入「已出窖」即得到该酒款未完成出窖单的待处理退窖明细，停售/复售保护用）。
     */
    @Query("select i from SaleOutItem i, SaleOut s "
            + "where i.saleId = s.id and i.wineId = :wineId and s.status = :saleStatus "
            + "order by i.saleId asc, i.id asc")
    List<SaleOutItem> findByWineIdAndSaleStatus(@Param("wineId") Long wineId,
                                                @Param("saleStatus") String saleStatus);
}
