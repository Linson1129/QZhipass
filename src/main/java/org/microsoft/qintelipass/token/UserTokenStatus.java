package org.microsoft.qintelipass.token;

/**
 * 某用户当日的 token 限额与使用状态
 */
public record UserTokenStatus(
        Long userId,
        long quota,
        long used,
        long remaining,
        boolean overQuota,
        String department,
        String userName
) {
    /** 兼容旧构造器（无 department/name） */
    public UserTokenStatus(Long userId, long quota, long used, long remaining, boolean overQuota) {
        this(userId, quota, used, remaining, overQuota, null, null);
    }
}
