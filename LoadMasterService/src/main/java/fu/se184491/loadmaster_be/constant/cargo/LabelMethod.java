package fu.se184491.loadmaster_be.constant.cargo;

/**
 * Cách đối chiếu một kiện bằng nhãn khi xếp, dỡ hoặc nhận dọc đường: quét mã QR hoặc gõ mã in trên nhãn. Xác nhận tay đi
 * lối riêng; bản ghi cũ không có cách là {@code QR} (app khi đó chỉ quét).
 */
public enum LabelMethod {
    QR,
    CODE;

    /** Cách đã ghi, vắng là {@code QR}. */
    public static LabelMethod orQr(LabelMethod method) {
        return method == null ? QR : method;
    }
}
