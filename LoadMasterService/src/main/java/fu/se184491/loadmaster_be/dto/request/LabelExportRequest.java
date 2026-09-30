package fu.se184491.loadmaster_be.dto.request;

import java.util.List;

/**
 * Request body for POST /api/packages/export/labels.
 *
 * @param packageIds list of package IDs to include in the PDF (max 200)
 */
public record LabelExportRequest(List<Long> packageIds) {}
