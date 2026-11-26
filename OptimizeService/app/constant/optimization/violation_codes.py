"""Mã vi phạm ràng buộc cứng theo spec "Ràng buộc vật lý và điểm phương án" mục 5.

Luật mới (`PHYSICS_RULES_V2`) ghi mã mới. Mã cũ vẫn còn trong phương án đã lưu và trong luật cũ, nên mọi chỗ ĐỌC mã
(đếm, so, hiển thị) đi qua `canonical()` để coi mã cũ là bí danh của mã mới.
"""

STACK_PRESSURE_EXCEEDED = "STACK_PRESSURE_EXCEEDED"  # H7: pⱼ = Fⱼ / A_c,j > σⱼ
FLOOR_LOAD_EXCEEDED = "FLOOR_LOAD_EXCEEDED"  # H10: (mᵢ + Fᵢ) / A_đáy,i > q_sàn
LIFO_BLOCKED = "LIFO_BLOCKED"  # H12: không có thứ tự dỡ thẳng ra cửa theo điểm giao
MUST_LOAD_UNPLACED = "MUST_LOAD_UNPLACED"  # H13: kiện must_load không được xếp

# mã cũ → mã mới. HC-08 (dễ vỡ) gộp vào H7: dễ vỡ = σ 0.
LEGACY_ALIASES = {
    "STACKING_WEIGHT_EXCEEDED": STACK_PRESSURE_EXCEEDED,
    "FRAGILITY_VIOLATION": STACK_PRESSURE_EXCEEDED,
    "MULTI_DROP_LIFO_VIOLATION": LIFO_BLOCKED,
}


def canonical(code):
    """Mã cũ → mã mới; mã khác giữ nguyên (cả None)."""
    return LEGACY_ALIASES.get(code, code)
