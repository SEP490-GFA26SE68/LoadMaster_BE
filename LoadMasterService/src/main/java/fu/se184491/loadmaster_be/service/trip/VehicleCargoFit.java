package fu.se184491.loadmaster_be.service.trip;

import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.cargo.PackageType;
import fu.se184491.loadmaster_be.entity.vehicle.VehicleType;
import fu.se184491.loadmaster_be.service.optimization.manual.ManualLayoutValidator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Xe có chở được hàng của chuyến không, thuần: chỉ kiểm điều kiện <b>cần</b> trên tổng hàng, không xếp thử. Xe qua được ở đây vẫn
 * có thể để lại kiện khi tối ưu; xe bị loại thì không cách xếp nào chở hết. Đơn vị cm, kg.
 *
 * <p>Mã và tham số như app web: {@code CARGO_TOO_LARGE} (kiện không có hướng nào được phép vừa lọt cửa vừa nằm trong lòng
 * thùng), {@code CARGO_VOLUME_EXCEEDED}, {@code CARGO_WEIGHT_EXCEEDED}, {@code AXLE_CAPACITY_EXCEEDED} (tổng hàng nặng hơn phần hai
 * nhóm trục còn nhận được: giới hạn − tải rỗng; xe thiếu dữ liệu trục thì không kiểm).</p>
 */
public final class VehicleCargoFit {

    private static final double EPSILON = 1e-6;

    private VehicleCargoFit() {
    }

    public record Issue(String code, String severity, Map<String, Object> params) {
        /** Câu tiếng Việt cho lỗi đầu tiên, khi nơi gọi chỉ trả được một thông điệp. */
        public String message() {
            return switch (code) {
                case "CARGO_TOO_LARGE" -> params.get("count") + " kiện không lọt cửa hoặc lòng thùng của xe";
                case "CARGO_VOLUME_EXCEEDED" -> "Tổng thể tích hàng lớn hơn lòng thùng của xe";
                case "CARGO_WEIGHT_EXCEEDED" -> "Hàng của chuyến nặng " + params.get("totalKg")
                        + " kg, vượt tải trọng " + params.get("maxPayloadKg") + " kg của xe";
                default -> "Hàng của chuyến vượt phần tải hai nhóm trục của xe còn nhận được";
            };
        }
    }

    public static List<Issue> issues(VehicleType type, List<CargoPackage> packages) {
        List<Issue> issues = new ArrayList<>();
        if (type == null) {
            return issues;
        }
        boolean interiorKnown = type.getInnerLength() != null && type.getInnerWidth() != null
                && type.getInnerHeight() != null;

        if (interiorKnown) {
            List<String> tooLarge = new ArrayList<>();
            for (CargoPackage pkg : packages) {
                if (dimensionsKnown(pkg) && !fitsSomeOrientation(pkg, type)) {
                    tooLarge.add(codeOf(pkg));
                }
            }
            if (!tooLarge.isEmpty()) {
                issues.add(error("CARGO_TOO_LARGE", "count", tooLarge.size(), "packageIds", tooLarge));
            }
            double totalCm3 = packages.stream().filter(VehicleCargoFit::dimensionsKnown)
                    .mapToDouble(pkg -> pkg.getActualLength().doubleValue() * pkg.getActualWidth().doubleValue()
                            * pkg.getActualHeight().doubleValue())
                    .sum();
            double cargoCm3 = (double) type.getInnerLength() * type.getInnerWidth() * type.getInnerHeight();
            if (totalCm3 > cargoCm3 + EPSILON) {
                issues.add(error("CARGO_VOLUME_EXCEEDED", "totalCm3", round(totalCm3), "cargoCm3", round(cargoCm3)));
            }
        }

        double totalKg = packages.stream().map(CargoPackage::getActualWeightKg)
                .filter(java.util.Objects::nonNull).mapToDouble(BigDecimal::doubleValue).sum();
        if (type.getMaxPayloadKg() != null && totalKg > type.getMaxPayloadKg().doubleValue() + EPSILON) {
            double payload = type.getMaxPayloadKg().doubleValue();
            issues.add(error("CARGO_WEIGHT_EXCEEDED", "totalKg", round(totalKg), "maxPayloadKg", round(payload),
                    "overKg", round(totalKg - payload)));
        }

        Double capacity = axleCapacityKg(type);
        if (capacity != null && totalKg > capacity + EPSILON) {
            issues.add(error("AXLE_CAPACITY_EXCEEDED", "totalKg", round(totalKg), "capacityKg", round(capacity),
                    "overKg", round(totalKg - capacity)));
        }
        return issues;
    }

    private static boolean fitsSomeOrientation(CargoPackage pkg, VehicleType type) {
        double[] base = {pkg.getActualLength().doubleValue(), pkg.getActualWidth().doubleValue(),
                pkg.getActualHeight().doubleValue()};
        for (int rotation : rotationsOf(pkg.getPackageType())) {
            double[] size = ManualLayoutValidator.orientedSize(base, rotation);
            boolean inside = size[0] <= type.getInnerLength() + EPSILON && size[1] <= type.getInnerWidth() + EPSILON
                    && size[2] <= type.getInnerHeight() + EPSILON;
            boolean throughDoor = (type.getDoorWidth() == null || size[1] <= type.getDoorWidth() + EPSILON)
                    && (type.getDoorHeight() == null || size[2] <= type.getDoorHeight() + EPSILON);
            if (inside && throughDoor) {
                return true;
            }
        }
        return false;
    }

    /** Như dịch vụ tối ưu: không có loại kiện thì chỉ hướng gốc. */
    private static Set<Integer> rotationsOf(PackageType type) {
        if (type == null) {
            return Set.of(0);
        }
        return ManualLayoutValidator.allowedRotations(Boolean.TRUE.equals(type.getRotationAllowed()),
                Boolean.TRUE.equals(type.getAllowRotateX()), Boolean.TRUE.equals(type.getAllowRotateY()),
                Boolean.TRUE.equals(type.getAllowRotateZ()));
    }

    private static Double axleCapacityKg(VehicleType type) {
        if (type.getFrontAxleX() == null || type.getRearAxleX() == null
                || type.getFrontAxleLimitKg() == null || type.getRearAxleLimitKg() == null
                || type.getEmptyFrontAxleLoadKg() == null || type.getEmptyRearAxleLoadKg() == null
                || type.getRearAxleX().compareTo(type.getFrontAxleX()) <= 0) {
            return null;
        }
        return type.getFrontAxleLimitKg().doubleValue() - type.getEmptyFrontAxleLoadKg().doubleValue()
                + type.getRearAxleLimitKg().doubleValue() - type.getEmptyRearAxleLoadKg().doubleValue();
    }

    private static boolean dimensionsKnown(CargoPackage pkg) {
        return pkg.getActualLength() != null && pkg.getActualWidth() != null && pkg.getActualHeight() != null;
    }

    private static String codeOf(CargoPackage pkg) {
        if (pkg.getPackageCode() != null) {
            return pkg.getPackageCode();
        }
        return pkg.getTrackingBarcode() != null ? pkg.getTrackingBarcode() : String.valueOf(pkg.getId());
    }

    private static Issue error(String code, Object... keyValues) {
        Map<String, Object> params = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            params.put((String) keyValues[i], keyValues[i + 1]);
        }
        return new Issue(code, "error", params);
    }

    private static double round(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
