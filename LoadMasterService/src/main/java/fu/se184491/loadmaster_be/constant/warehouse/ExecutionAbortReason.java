package fu.se184491.loadmaster_be.constant.warehouse;

/**
 * Vì sao phiên xếp dừng và chuyến quay về lập kế hoạch: {@code STAGING_SHORTAGE} — điều phối viên bỏ kiện kho báo thiếu;
 * {@code DAMAGED_PACKAGE} — kiện hỏng có kiện tựa lên. Phiên dừng vì huỷ chuyến không mang lý do.
 */
public enum ExecutionAbortReason { STAGING_SHORTAGE, DAMAGED_PACKAGE }
