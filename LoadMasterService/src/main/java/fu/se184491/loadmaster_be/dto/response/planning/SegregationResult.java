package fu.se184491.loadmaster_be.dto.response.planning;

import fu.se184491.loadmaster_be.constant.cargo.HandlingClass;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
@AllArgsConstructor
public class SegregationResult {

    private List<Group> groups;
    private List<Conflict> conflicts;
    private String handlingClassLock;
    private String overrideReason;

    @Getter
    @Builder
    @AllArgsConstructor
    public static class Group {
        private HandlingClass handlingClass;
        private int packageCount;
        private List<Long> packageIds;
    }

    @Getter
    @Builder
    @AllArgsConstructor
    public static class Conflict {
        private String ruleCode;
        private String message;
        private List<Long> affectedPackageIds;
    }
}
