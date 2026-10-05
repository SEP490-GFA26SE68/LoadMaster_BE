package fu.se184491.loadmaster_be.dto.response.account;

public record UserStatsResponse(
        long totalUsers,
        long activeUsers,
        long suspendedUsers
) {
}