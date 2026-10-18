package fu.se184491.loadmaster_be.service.billing;

import fu.se184491.loadmaster_be.constant.billing.InvoiceStatus;
import fu.se184491.loadmaster_be.dto.response.billing.InvoiceResponse;

import java.util.List;

public interface InvoiceService {

    List<InvoiceResponse> getCompanyInvoices(Long companyId, InvoiceStatus status);

    InvoiceResponse getInvoiceById(Long invoiceId, Long callerCompanyId, boolean isPlatformAdmin);
}
