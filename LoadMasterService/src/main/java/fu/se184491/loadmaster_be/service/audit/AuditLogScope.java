package fu.se184491.loadmaster_be.service.audit;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;

/**
 * Phạm vi đọc nhật ký của người xem.
 *
 * <ul>
 *   <li>{@code SYSTEM_ADMIN}: mọi bản ghi; lọc {@code companyId=<mã>} theo một công ty, hoặc
 *       {@code companyId=platform} cho bản ghi không thuộc công ty nào.</li>
 *   <li>{@code ADMIN} (quản trị công ty): chỉ công ty mình; tham số {@code companyId} bị bỏ qua,
 *       như danh sách người dùng.</li>
 *   <li>Vai trò khác: 403.</li>
 * </ul>
 *
 * @param everything   không lọc theo công ty
 * @param platformOnly chỉ bản ghi không thuộc công ty nào
 * @param companyId    chỉ bản ghi của công ty này
 */
public record AuditLogScope(boolean everything, boolean platformOnly, Long companyId) {

    public static final String PLATFORM = "platform";

    public static AuditLogScope of(UserRole viewerRole, Long viewerCompanyId, String companyParam) {
        if (viewerRole == UserRole.SYSTEM_ADMIN) {
            if (companyParam == null || companyParam.isBlank()) {
                return new AuditLogScope(true, false, null);
            }
            String value = companyParam.trim();
            if (PLATFORM.equalsIgnoreCase(value)) {
                return new AuditLogScope(false, true, null);
            }
            try {
                return new AuditLogScope(false, false, Long.valueOf(value));
            } catch (NumberFormatException e) {
                throw new AppException(ErrorCode.INVALID_INPUT, "companyId phải là mã công ty hoặc platform");
            }
        }
        if (viewerRole == UserRole.ADMIN) {
            if (viewerCompanyId == null) {
                throw new AppException(ErrorCode.USER_COMPANY_NOT_FOUND);
            }
            return new AuditLogScope(false, false, viewerCompanyId);
        }
        throw new AppException(ErrorCode.UNAUTHORIZED);
    }
}
