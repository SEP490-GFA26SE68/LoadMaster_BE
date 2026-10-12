package fu.se184491.loadmaster_be.service.cargo;

import fu.se184491.loadmaster_be.dto.request.cargo.StackingRuleRequest;
import fu.se184491.loadmaster_be.dto.response.cargo.StackingRuleResponse;
import fu.se184491.loadmaster_be.entity.cargo.PackageType;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.optimization.StackingRule;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.cargo.PackageTypeRepository;
import fu.se184491.loadmaster_be.repository.cargo.StackingRuleRepository;
import fu.se184491.loadmaster_be.repository.company.CompanyRepository;
import fu.se184491.loadmaster_be.service.cargo.Impl.StackingRuleServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class StackingRuleServiceTest {

    @Mock
    private StackingRuleRepository stackingRuleRepository;

    @Mock
    private PackageTypeRepository packageTypeRepository;

    @Mock
    private CompanyRepository companyRepository;

    @InjectMocks
    private StackingRuleServiceImpl stackingRuleService;

    private Company company;
    private PackageType bottomType;
    private PackageType topType;
    private StackingRule rule;
    private StackingRuleRequest request;

    @BeforeEach
    void setUp() {
        company = Company.builder().id(1L).companyCode("COMP01").build();

        bottomType = PackageType.builder()
                .id(10L)
                .company(company)
                .name("Standard Pallet")
                .build();

        topType = PackageType.builder()
                .id(20L)
                .company(company)
                .name("Fragile Carton")
                .build();

        rule = StackingRule.builder()
                .id(100L)
                .company(company)
                .bottomType(bottomType)
                .topType(topType)
                .isAllowed(false)
                .minContactRatio(new BigDecimal("0.85"))
                .build();

        request = StackingRuleRequest.builder()
                .bottomPackageTypeId(10L)
                .topPackageTypeId(20L)
                .isAllowed(false)
                .minContactRatio(new BigDecimal("0.85"))
                .build();
    }

    @Test
    void createStackingRule_Success() {
        when(companyRepository.findById(1L)).thenReturn(Optional.of(company));
        when(packageTypeRepository.findById(10L)).thenReturn(Optional.of(bottomType));
        when(packageTypeRepository.findById(20L)).thenReturn(Optional.of(topType));
        when(stackingRuleRepository.existsByCompanyIdAndBottomTypeIdAndTopTypeId(1L, 10L, 20L)).thenReturn(false);

        when(stackingRuleRepository.save(any(StackingRule.class))).thenAnswer(inv -> {
            StackingRule saved = inv.getArgument(0);
            saved.setId(100L);
            return saved;
        });

        StackingRuleResponse response = stackingRuleService.createStackingRule(1L, request);

        assertNotNull(response);
        assertEquals(100L, response.getId());
        assertEquals(1L, response.getCompanyId());
        assertEquals(10L, response.getBottomPackageTypeId());
        assertEquals("Standard Pallet", response.getBottomPackageTypeName());
        assertEquals(20L, response.getTopPackageTypeId());
        assertEquals("Fragile Carton", response.getTopPackageTypeName());
        assertFalse(response.getIsAllowed());
        assertEquals(new BigDecimal("0.85"), response.getMinContactRatio());
        verify(stackingRuleRepository).save(any(StackingRule.class));
    }

    @Test
    void createStackingRule_Throws_DuplicateRule() {
        when(companyRepository.findById(1L)).thenReturn(Optional.of(company));
        when(packageTypeRepository.findById(10L)).thenReturn(Optional.of(bottomType));
        when(packageTypeRepository.findById(20L)).thenReturn(Optional.of(topType));
        when(stackingRuleRepository.existsByCompanyIdAndBottomTypeIdAndTopTypeId(1L, 10L, 20L)).thenReturn(true);

        AppException ex = assertThrows(AppException.class, () -> stackingRuleService.createStackingRule(1L, request));
        assertEquals(ErrorCode.STACKING_RULE_ALREADY_EXISTS, ex.getErrorCode());
        verify(stackingRuleRepository, never()).save(any());
    }

    @Test
    void createStackingRule_Throws_BottomTypeNotFound() {
        when(companyRepository.findById(1L)).thenReturn(Optional.of(company));
        when(packageTypeRepository.findById(10L)).thenReturn(Optional.empty());

        AppException ex = assertThrows(AppException.class, () -> stackingRuleService.createStackingRule(1L, request));
        assertEquals(ErrorCode.PACKAGE_TYPE_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void createStackingRule_Throws_TopTypeNotFound() {
        when(companyRepository.findById(1L)).thenReturn(Optional.of(company));
        when(packageTypeRepository.findById(10L)).thenReturn(Optional.of(bottomType));
        when(packageTypeRepository.findById(20L)).thenReturn(Optional.empty());

        AppException ex = assertThrows(AppException.class, () -> stackingRuleService.createStackingRule(1L, request));
        assertEquals(ErrorCode.PACKAGE_TYPE_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void createStackingRule_Throws_BottomTypeCrossCompany() {
        Company otherCompany = Company.builder().id(999L).build();
        bottomType.setCompany(otherCompany);

        when(companyRepository.findById(1L)).thenReturn(Optional.of(company));
        when(packageTypeRepository.findById(10L)).thenReturn(Optional.of(bottomType));

        AppException ex = assertThrows(AppException.class, () -> stackingRuleService.createStackingRule(1L, request));
        assertEquals(ErrorCode.UNAUTHORIZED, ex.getErrorCode());
    }

    @Test
    void createStackingRule_Throws_TopTypeCrossCompany() {
        Company otherCompany = Company.builder().id(999L).build();
        topType.setCompany(otherCompany);

        when(companyRepository.findById(1L)).thenReturn(Optional.of(company));
        when(packageTypeRepository.findById(10L)).thenReturn(Optional.of(bottomType));
        when(packageTypeRepository.findById(20L)).thenReturn(Optional.of(topType));

        AppException ex = assertThrows(AppException.class, () -> stackingRuleService.createStackingRule(1L, request));
        assertEquals(ErrorCode.UNAUTHORIZED, ex.getErrorCode());
    }

    @Test
    void updateStackingRule_Success() {
        when(stackingRuleRepository.findById(100L)).thenReturn(Optional.of(rule));
        when(packageTypeRepository.findById(10L)).thenReturn(Optional.of(bottomType));
        when(packageTypeRepository.findById(20L)).thenReturn(Optional.of(topType));
        when(stackingRuleRepository.existsByCompanyIdAndBottomTypeIdAndTopTypeIdAndIdNot(1L, 10L, 20L, 100L)).thenReturn(false);
        when(stackingRuleRepository.save(any(StackingRule.class))).thenAnswer(inv -> inv.getArgument(0));

        request.setIsAllowed(true);
        StackingRuleResponse response = stackingRuleService.updateStackingRule(1L, 100L, request);

        assertNotNull(response);
        assertTrue(response.getIsAllowed());
        verify(stackingRuleRepository).save(rule);
    }

    @Test
    void updateStackingRule_Throws_NotFound() {
        when(stackingRuleRepository.findById(100L)).thenReturn(Optional.empty());

        AppException ex = assertThrows(AppException.class, () -> stackingRuleService.updateStackingRule(1L, 100L, request));
        assertEquals(ErrorCode.STACKING_RULE_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void updateStackingRule_Throws_Unauthorized() {
        Company otherCompany = Company.builder().id(999L).build();
        rule.setCompany(otherCompany);
        when(stackingRuleRepository.findById(100L)).thenReturn(Optional.of(rule));

        AppException ex = assertThrows(AppException.class, () -> stackingRuleService.updateStackingRule(1L, 100L, request));
        assertEquals(ErrorCode.UNAUTHORIZED, ex.getErrorCode());
    }

    @Test
    void updateStackingRule_Throws_DuplicatePair() {
        when(stackingRuleRepository.findById(100L)).thenReturn(Optional.of(rule));
        when(packageTypeRepository.findById(10L)).thenReturn(Optional.of(bottomType));
        when(packageTypeRepository.findById(20L)).thenReturn(Optional.of(topType));
        when(stackingRuleRepository.existsByCompanyIdAndBottomTypeIdAndTopTypeIdAndIdNot(1L, 10L, 20L, 100L)).thenReturn(true);

        AppException ex = assertThrows(AppException.class, () -> stackingRuleService.updateStackingRule(1L, 100L, request));
        assertEquals(ErrorCode.STACKING_RULE_ALREADY_EXISTS, ex.getErrorCode());
    }

    @Test
    void deleteStackingRule_Success() {
        when(stackingRuleRepository.findById(100L)).thenReturn(Optional.of(rule));

        stackingRuleService.deleteStackingRule(1L, 100L);

        verify(stackingRuleRepository).delete(rule);
    }

    @Test
    void deleteStackingRule_Throws_NotFound() {
        when(stackingRuleRepository.findById(100L)).thenReturn(Optional.empty());

        AppException ex = assertThrows(AppException.class, () -> stackingRuleService.deleteStackingRule(1L, 100L));
        assertEquals(ErrorCode.STACKING_RULE_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void getStackingRuleById_Success() {
        when(stackingRuleRepository.findById(100L)).thenReturn(Optional.of(rule));

        StackingRuleResponse response = stackingRuleService.getStackingRuleById(1L, 100L);

        assertNotNull(response);
        assertEquals(100L, response.getId());
    }

    @Test
    void getAllStackingRules_Success() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<StackingRule> page = new PageImpl<>(Collections.singletonList(rule), pageable, 1);
        when(stackingRuleRepository.findByCompanyId(eq(1L), eq(pageable))).thenReturn(page);

        Page<StackingRuleResponse> result = stackingRuleService.getAllStackingRules(1L, pageable);

        assertNotNull(result);
        assertEquals(1, result.getTotalElements());
    }

    @Test
    void isStackingAllowed_WhenRuleExists_ReturnsRuleValue() {
        when(stackingRuleRepository.findByCompanyIdAndBottomTypeIdAndTopTypeId(1L, 10L, 20L)).thenReturn(Optional.of(rule));

        Boolean allowed = stackingRuleService.isStackingAllowed(1L, 10L, 20L);

        assertFalse(allowed);
    }

    @Test
    void isStackingAllowed_WhenNoRuleExists_DefaultsToTrue() {
        when(stackingRuleRepository.findByCompanyIdAndBottomTypeIdAndTopTypeId(1L, 10L, 20L)).thenReturn(Optional.empty());

        Boolean allowed = stackingRuleService.isStackingAllowed(1L, 10L, 20L);

        assertTrue(allowed);
    }
}
