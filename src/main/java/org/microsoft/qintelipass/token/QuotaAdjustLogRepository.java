package org.microsoft.qintelipass.token;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
public interface QuotaAdjustLogRepository extends JpaRepository<QuotaAdjustLog, Long> {

    /**
     * 多条件分页查询操作日志，按时间倒序。
     * 可选的筛选条件：操作人ID、目标员工ID、时间范围。
     */
    @Query("SELECT l FROM QuotaAdjustLog l " +
           "WHERE (:operatorId IS NULL OR l.operator.id = :operatorId) " +
           "AND (:targetUserId IS NULL OR l.targetUser.id = :targetUserId) " +
           "AND (:startTime IS NULL OR l.operatedAt >= :startTime) " +
           "AND (:endTime IS NULL OR l.operatedAt <= :endTime) " +
           "ORDER BY l.operatedAt DESC")
    Page<QuotaAdjustLog> findLogs(@Param("operatorId") Long operatorId,
                                  @Param("targetUserId") Long targetUserId,
                                  @Param("startTime") Instant startTime,
                                  @Param("endTime") Instant endTime,
                                  Pageable pageable);

    /** 清理 90 天前的旧日志 */
    @org.springframework.data.jpa.repository.Modifying
    @Query("DELETE FROM QuotaAdjustLog l WHERE l.createdAt < :cutoff")
    int deleteByCreatedAtBefore(@Param("cutoff") Instant cutoff);
}
