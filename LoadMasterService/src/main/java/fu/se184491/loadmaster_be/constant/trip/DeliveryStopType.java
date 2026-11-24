package fu.se184491.loadmaster_be.constant.trip;

/**
 * Loại điểm của chuyến. Máy chủ chỉ còn tạo điểm giao; {@code PICKUP} giữ để đọc dòng cũ của tính năng nhận hàng dọc đường đã bỏ
 * (cột {@code delivery_stops.stop_type} vẫn còn dữ liệu đó).
 */
public enum DeliveryStopType { DELIVERY, PICKUP }
