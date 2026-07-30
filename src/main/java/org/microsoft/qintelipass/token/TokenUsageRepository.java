package org.microsoft.qintelipass.token;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface TokenUsageRepository extends JpaRepository<TokenUsage, Long> {

    /** 查询某用户某天某个模型的用量记录（用于累加） */
    java.util.Optional<TokenUsage> findByUserIdAndUsageDateAndModel(
            @Param("userId") Long userId, @Param("date") LocalDate date,
            @Param("model") String model);

    /** 查询某用户某天所有模型的用量 */
    List<TokenUsage> findByUserIdAndUsageDate(
            @Param("userId") Long userId, @Param("date") LocalDate date);

    /** 查询某用户某日期范围内的所有用量 */
    @Query("SELECT t FROM TokenUsage t " +
           "WHERE t.user.id = :userId AND t.usageDate BETWEEN :start AND :end " +
           "ORDER BY t.usageDate DESC, t.totalTokens DESC")
    List<TokenUsage> findByUserIdAndUsageDateBetween(
            @Param("userId") Long userId,
            @Param("start") LocalDate start,
            @Param("end") LocalDate end);

    /** 查询某天所有用户的用量 */
    List<TokenUsage> findByUsageDate(LocalDate date);

    /** 统计某个用户某天是否已有用量记录（活跃用户判定） */
    @Query("SELECT COUNT(t) FROM TokenUsage t " +
           "WHERE t.user.id = :userId AND t.usageDate = :date")
    long countByUserIdAndUsageDate(
            @Param("userId") Long userId, @Param("date") LocalDate date);

    /**
     * 统计从 start 日期起，按 日期 + 模型 汇总每日 token 消耗总量（用于一周柱状图）
     */
    @Query("SELECT new org.microsoft.qintelipass.token.TokenUsageRepository$ModelDailyTotal(" +
           "t.model, t.usageDate, SUM(t.totalTokens)) " +
           "FROM TokenUsage t WHERE t.usageDate >= :start " +
           "GROUP BY t.usageDate, t.model")
    List<ModelDailyTotal> findDailyTotalsSince(@Param("start") LocalDate start);

    /** 清理指定日期之前的旧记录（每日 0 点定时任务调用，保持统计表精简） */
    @Modifying
    @Query("DELETE FROM TokenUsage t WHERE t.usageDate < :cutoff")
    void deleteByUsageDateBefore(@Param("cutoff") LocalDate cutoff);

    /** 日期 + 模型 用量汇总投影接口 */
    class ModelDailyTotal {
        private final String model;
        private final LocalDate usageDate;
        private final Long total;

        public ModelDailyTotal(String model, LocalDate usageDate, Long total) {
            this.model = model;
            this.usageDate = usageDate;
            this.total = total;
        }

        public String getModel() { return model; }
        public LocalDate getUsageDate() { return usageDate; }
        public Long getTotal() { return total; }
    }
}
