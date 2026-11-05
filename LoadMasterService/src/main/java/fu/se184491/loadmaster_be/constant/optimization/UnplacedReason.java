package fu.se184491.loadmaster_be.constant.optimization;

// WEIGHT_LIMIT_EXCEEDED, CONSTRAINT_VIOLATED do dịch vụ tối ưu (Python) ghi vào unplaced_packages.
public enum UnplacedReason { OVER_WEIGHT, OUT_OF_SPACE, STACKING_CONFLICT, LIFO_BLOCK, WEIGHT_LIMIT_EXCEEDED, CONSTRAINT_VIOLATED }
