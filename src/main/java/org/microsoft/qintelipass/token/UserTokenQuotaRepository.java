package org.microsoft.qintelipass.token;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserTokenQuotaRepository extends JpaRepository<UserTokenQuota, Long> {

    /** 查询某员工是否有个性化配额 */
    Optional<UserTokenQuota> findByUserId(Long userId);

    /** 删除某员工的个性化配额（恢复为系统默认值） */
    void deleteByUserId(Long userId);
}
