package fu.se184491.loadmaster_be.exception;
import java.util.Map;
public class RequirementPackagesIncompleteException extends AppException {
    private final Map<String, Integer> counts;
    public RequirementPackagesIncompleteException(int expected, int received) {
        super(ErrorCode.REQUIREMENT_PACKAGES_INCOMPLETE);
        counts = Map.of("expectedCount", expected, "receivedCount", received, "missingCount", expected - received);
    }
    public Map<String, Integer> getCounts() { return counts; }
}
