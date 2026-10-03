package fu.se184491.loadmaster_be.service.company;

import fu.se184491.loadmaster_be.constant.company.CompanyStatus;
import fu.se184491.loadmaster_be.dto.request.company.CompanyRequest;
import fu.se184491.loadmaster_be.dto.response.company.CompanyResponse;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.company.CompanyRepository;
import fu.se184491.loadmaster_be.service.company.Impl.CompanyServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class CompanyServiceTest {

    @Mock
    private CompanyRepository companyRepository;

    @InjectMocks
    private CompanyServiceImpl companyService;

    private CompanyRequest request;
    private Company company;

    @BeforeEach
    void setUp() {
        request = CompanyRequest.builder()
                .companyCode("LM-LOGISTICS")
                .companyName("LoadMaster Logistics")
                .taxCode("TAX-001")
                .billingEmail("billing@loadmaster.vn")
                .build();

        company = Company.builder()
                .id(1L)
                .companyCode("LM-LOGISTICS")
                .companyName("LoadMaster Logistics")
                .taxCode("TAX-001")
                .billingEmail("billing@loadmaster.vn")
                .status(CompanyStatus.ACTIVE)
                .createdAt(Instant.now())
                .build();
    }

    @Test
    @DisplayName("Create Company should succeed when codes are unique")
    void createCompany_success() {
        when(companyRepository.existsByCompanyCode("LM-LOGISTICS")).thenReturn(false);
        when(companyRepository.existsByTaxCode("TAX-001")).thenReturn(false);
        when(companyRepository.save(any(Company.class))).thenReturn(company);

        CompanyResponse response = companyService.createCompany(request);

        assertNotNull(response);
        assertEquals(1L, response.getId());
        assertEquals("LM-LOGISTICS", response.getCompanyCode());
        assertEquals("LoadMaster Logistics", response.getCompanyName());
        assertEquals(CompanyStatus.ACTIVE, response.getStatus());

        verify(companyRepository).save(any(Company.class));
    }

    @Test
    @DisplayName("Create Company should throw COMPANY_ALREADY_EXISTS when company code exists")
    void createCompany_duplicateCode() {
        when(companyRepository.existsByCompanyCode("LM-LOGISTICS")).thenReturn(true);

        AppException ex = assertThrows(AppException.class, () -> companyService.createCompany(request));
        assertEquals(ErrorCode.COMPANY_ALREADY_EXISTS, ex.getErrorCode());
        verify(companyRepository, never()).save(any());
    }

    @Test
    @DisplayName("Create Company should throw TAX_CODE_ALREADY_EXISTS when tax code exists")
    void createCompany_duplicateTaxCode() {
        when(companyRepository.existsByCompanyCode("LM-LOGISTICS")).thenReturn(false);
        when(companyRepository.existsByTaxCode("TAX-001")).thenReturn(true);

        AppException ex = assertThrows(AppException.class, () -> companyService.createCompany(request));
        assertEquals(ErrorCode.TAX_CODE_ALREADY_EXISTS, ex.getErrorCode());
        verify(companyRepository, never()).save(any());
    }

    @Test
    @DisplayName("Update Company should succeed when entity exists")
    void updateCompany_success() {
        when(companyRepository.findById(1L)).thenReturn(Optional.of(company));
        when(companyRepository.existsByTaxCodeAndIdNot("TAX-NEW", 1L)).thenReturn(false);
        when(companyRepository.save(any(Company.class))).thenReturn(company);

        CompanyRequest updateRequest = CompanyRequest.builder()
                .companyCode("LM-LOGISTICS")
                .companyName("LoadMaster Global")
                .taxCode("TAX-NEW")
                .billingEmail("new@loadmaster.vn")
                .status(CompanyStatus.ACTIVE)
                .build();

        CompanyResponse response = companyService.updateCompany(1L, updateRequest);

        assertNotNull(response);
        verify(companyRepository).save(company);
    }

    @Test
    @DisplayName("Update Company should throw COMPANY_NOT_FOUND when company does not exist")
    void updateCompany_notFound() {
        when(companyRepository.findById(999L)).thenReturn(Optional.empty());

        AppException ex = assertThrows(AppException.class, () -> companyService.updateCompany(999L, request));
        assertEquals(ErrorCode.COMPANY_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    @DisplayName("Get Company by ID should succeed when company exists")
    void getCompanyById_success() {
        when(companyRepository.findById(1L)).thenReturn(Optional.of(company));

        CompanyResponse response = companyService.getCompanyById(1L);

        assertNotNull(response);
        assertEquals(1L, response.getId());
        assertEquals("LM-LOGISTICS", response.getCompanyCode());
    }

    @Test
    @DisplayName("Get Company by ID should throw COMPANY_NOT_FOUND when company does not exist")
    void getCompanyById_notFound() {
        when(companyRepository.findById(999L)).thenReturn(Optional.empty());

        AppException ex = assertThrows(AppException.class, () -> companyService.getCompanyById(999L));
        assertEquals(ErrorCode.COMPANY_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    @DisplayName("Get all Companies should return Page of response")
    void getAllCompanies_success() {
        Pageable pageable = mock(Pageable.class);
        Page<Company> page = new PageImpl<>(List.of(company));
        when(companyRepository.findAll(pageable)).thenReturn(page);

        Page<CompanyResponse> result = companyService.getAllCompanies(pageable);

        assertNotNull(result);
        assertEquals(1, result.getTotalElements());
        assertEquals("LM-LOGISTICS", result.getContent().get(0).getCompanyCode());
    }

    @Test
    @DisplayName("Change Company status should update status and save")
    void changeCompanyStatus_success() {
        when(companyRepository.findById(1L)).thenReturn(Optional.of(company));
        when(companyRepository.save(any(Company.class))).thenReturn(company);

        CompanyResponse response = companyService.changeCompanyStatus(1L, CompanyStatus.SUSPENDED);

        assertNotNull(response);
        assertEquals(CompanyStatus.SUSPENDED, company.getStatus());
        verify(companyRepository).save(company);
    }
}
