package fu.se184491.loadmaster_be.dto.response;

import java.util.List;

/**
 * Returned after successfully confirming a batch package import.
 *
 * @param totalImported number of packages persisted
 * @param packageIds    IDs of the newly created CargoPackage records
 */
public record ImportConfirmResult(
        int totalImported,
        List<Long> packageIds
) {}
