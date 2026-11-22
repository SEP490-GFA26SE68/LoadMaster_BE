package fu.se184491.loadmaster_be.service.cargo;

import fu.se184491.loadmaster_be.entity.cargo.*;
import fu.se184491.loadmaster_be.constant.cargo.*;
import jakarta.persistence.criteria.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import java.util.*;

/** Read-only warehouse list contracts. Exact packageCode lookup remains separate. */
public final class WarehouseListQuery {
    private WarehouseListQuery() {}
    public static final Map<String,String> PACKAGES = Map.ofEntries(
        Map.entry("id","id"), Map.entry("packageCode","packageCode"), Map.entry("code","packageCode"),
        Map.entry("packageName","packageName"), Map.entry("trackingBarcode","trackingBarcode"),
        Map.entry("actualWeightKg","actualWeightKg"), Map.entry("weight","actualWeightKg"),
        Map.entry("actualLength","actualLength"), Map.entry("actualWidth","actualWidth"),
        Map.entry("actualHeight","actualHeight"), Map.entry("destination","destination"),
        Map.entry("handlingClass","handlingClass"), Map.entry("status","status"));
    public static final Map<String,String> TYPES = Map.ofEntries(
        Map.entry("id","id"), Map.entry("name","name"), Map.entry("typeCode","typeCode"),
        Map.entry("length","length"), Map.entry("lengthCm","length"),
        Map.entry("width","width"), Map.entry("height","height"), Map.entry("weightKg","weightKg"),
        Map.entry("maxStackingWeightKg","maxStackingWeightKg"), Map.entry("isFragile","isFragile"));

    public static Pageable pageable(Pageable input, String sortBy, String sortDir, Map<String,String> allowed) {
        Sort sort = input.getSort();
        if (sortBy != null) {
            sort = Sort.by(direction(sortDir), sortBy);
        } else if (sortDir != null) {
            throw new IllegalArgumentException("sortDir requires sortBy");
        }
        if (sort.isUnsorted()) sort = Sort.by("id");
        List<Sort.Order> orders = new ArrayList<>();
        for (Sort.Order order : sort) {
            String path = allowed.get(order.getProperty());
            if (path == null) throw new IllegalArgumentException("Unsupported sort field");
            orders.add(new Sort.Order(order.getDirection(), path));
        }
        if (orders.stream().noneMatch(o -> o.getProperty().equals("id"))) orders.add(Sort.Order.asc("id"));
        return PageRequest.of(input.getPageNumber(), input.getPageSize(), Sort.by(orders));
    }
    private static Sort.Direction direction(String value) {
        if (value == null) return Sort.Direction.ASC;
        if (!value.equalsIgnoreCase("ASC") && !value.equalsIgnoreCase("DESC"))
            throw new IllegalArgumentException("sortDir must be ASC or DESC");
        return Sort.Direction.fromString(value);
    }
    public static Pageable window(Pageable page) {
        return PageRequest.of(page.getPageNumber(), page.getPageSize());
    }
    private static void order(Root<?> root, CriteriaQuery<?> query, CriteriaBuilder cb, Sort sort) {
        if (query.getResultType() == Long.class || query.getResultType() == long.class) return;
        List<jakarta.persistence.criteria.Order> orders = new ArrayList<>();
        for (Sort.Order item : sort) {
            Path<?> path = root.get(item.getProperty());
            // Explicit NULLS LAST in both directions, including PostgreSQL DESC.
            orders.add(cb.asc(cb.<Integer>selectCase().when(cb.isNull(path),1).otherwise(0)));
            orders.add(item.isAscending() ? cb.asc(path) : cb.desc(path));
        }
        query.orderBy(orders);
    }
    private static final String[] FOLD = foldingCharacters();
    private static String[] foldingCharacters() {
        StringBuilder from = new StringBuilder(), to = new StringBuilder();
        for (char c = 0x00c0; c <= 0x1eff; c++) {
            if (!Character.isLowerCase(c)) continue;
            String plain = java.text.Normalizer.normalize(String.valueOf(c), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
            if (plain.length() == 1 && plain.charAt(0) >= 'a' && plain.charAt(0) <= 'z') {
                from.append(c); to.append(plain);
            }
        }
        from.append('\u0111'); to.append('d');
        return new String[]{from.toString(), to.toString()};
    }
    private static String[] terms(String value) {
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "").replace('\u0111', 'd').replace('\u0110', 'd')
            .toLowerCase(Locale.ROOT).trim().split("\\s+");
    }
    private static Predicate contains(CriteriaBuilder cb, Expression<String> value, String term) {
        Expression<String> folded = cb.function("translate", String.class, cb.lower(value), cb.literal(FOLD[0]), cb.literal(FOLD[1]));
        folded = cb.function("regexp_replace", String.class, folded, cb.literal("[\u0300-\u036f]"), cb.literal(""), cb.literal("g"));
        String pattern = "%" + term.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        return cb.like(folded, pattern, '!');
    }
    public static Specification<CargoPackage> packages(String search, PackageStatus status, HandlingClass handling, Sort sort) {
        return (root,query,cb) -> {
            order(root,query,cb,sort);
            List<Predicate> filters = new ArrayList<>();
            if (status != null) filters.add(cb.equal(root.get("status"),status));
            if (handling != null) filters.add(cb.equal(root.get("handlingClass"),handling));
            if (search != null && !search.isBlank()) {
                var type = root.join("packageType",JoinType.LEFT);
                for (String term : terms(search)) filters.add(cb.or(
                    contains(cb,root.get("packageCode"),term),contains(cb,root.get("packageName"),term),
                    contains(cb,root.get("trackingBarcode"),term),
                    contains(cb,root.get("destination"),term),contains(cb,type.get("name"),term),
                    contains(cb,type.get("typeCode"),term),contains(cb,((org.hibernate.query.criteria.JpaExpression<?>) root.get("id")).cast(String.class),term)));
            }
            return cb.and(filters.toArray(Predicate[]::new));
        };
    }
    public static Specification<PackageType> types(Long companyId, String search, Sort sort) {
        return (root,query,cb) -> {
            order(root,query,cb,sort);
            Predicate tenant = cb.equal(root.get("company").get("id"),companyId);
            if (search == null || search.isBlank()) return tenant;
            List<Predicate> matches = new ArrayList<>();
            matches.add(tenant);
            for (String term : terms(search)) matches.add(cb.or(contains(cb,root.get("name"),term),
                contains(cb,root.get("typeCode"),term),contains(cb,((org.hibernate.query.criteria.JpaExpression<?>) root.get("id")).cast(String.class),term)));
            return cb.and(matches.toArray(Predicate[]::new));
        };
    }
}
