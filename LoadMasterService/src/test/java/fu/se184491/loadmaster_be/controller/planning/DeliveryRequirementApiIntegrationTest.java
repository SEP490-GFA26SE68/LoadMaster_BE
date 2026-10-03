package fu.se184491.loadmaster_be.controller.planning;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.account.UserStatus;
import fu.se184491.loadmaster_be.constant.cargo.PackageStatus;
import fu.se184491.loadmaster_be.constant.cargo.HandlingClass;
import fu.se184491.loadmaster_be.constant.company.CompanyStatus;
import fu.se184491.loadmaster_be.constant.planning.DeliveryRequirementPriority;
import fu.se184491.loadmaster_be.constant.planning.DeliveryRequirementStatus;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.cargo.PackageType;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.order.TransportOrder;
import fu.se184491.loadmaster_be.entity.planning.DeliveryRequirement;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import fu.se184491.loadmaster_be.service.planning.DeliveryRequirementService;
import fu.se184491.loadmaster_be.exception.AppException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:delivery-requirement;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "spring.flyway.enabled=false",
        "spring.security.oauth2.client.registration.optimization-client.client-secret=test",
        "spring.security.oauth2.client.registration.keycloak-admin-client.client-secret=test",
        "app.brevo.api-key=test",
        "app.brevo.sender-email=test@example.com",
        "app.brevo.sender-name=Test",
        "app.brevo.security.otp-hmac-secret=test-secret"
})
@AutoConfigureMockMvc
@Transactional
class DeliveryRequirementApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private DeliveryRequirementService deliveryRequirementService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private Long packageId;
    private TransportOrder order;
    private PackageType packageType;
    private Company company;
    private User manager;

    @BeforeEach
    void setUpTenantAndPackage() {
        company = Company.builder()
                .companyCode("ACME")
                .companyName("Acme Logistics")
                .taxCode("ACME-TAX")
                .billingEmail("billing@acme.test")
                .status(CompanyStatus.ACTIVE)
                .createdAt(Instant.now())
                .build();
        entityManager.persist(company);

        manager = User.builder()
                .keycloakId("manager-subject")
                .company(company)
                .email("manager@acme.test")
                .fullName("Acme Manager")
                .userRoleType(UserRole.COMPANY_MANAGER)
                .status(UserStatus.ACTIVE)
                .build();
        entityManager.persist(manager);

        packageType = PackageType.builder()
                .company(company)
                .typeCode("BOX")
                .name("Box")
                .length(100)
                .width(100)
                .height(100)
                .build();
        entityManager.persist(packageType);

        order = TransportOrder.builder()
                .company(company)
                .orderCode("ORDER-001")
                .status(fu.se184491.loadmaster_be.constant.order.OrderStatus.PENDING)
                .build();
        entityManager.persist(order);

        CargoPackage cargoPackage = CargoPackage.builder()
                .order(order)
                .packageType(packageType)
                .trackingBarcode("PKG-001")
                .actualWeightKg(BigDecimal.ONE)
                .status(PackageStatus.PENDING)
                .build();
        entityManager.persist(cargoPackage);
        entityManager.flush();
        packageId = cargoPackage.getId();
    }

    @Test
    void managerCanCreateAndRetrieveDeliveryRequirement() throws Exception {
        String deadline = LocalDateTime.now().plusDays(2).withNano(0).toString();
        String request = """
                {
                  "destination": "Da Nang Distribution Center",
                  "destinationLat": 16.054407,
                  "destinationLng": 108.202167,
                  "deadline": "%s",
                  "priority": "HIGH",
                  "packageIds": [%d]
                }
                """.formatted(deadline, packageId);

        String location = mockMvc.perform(post("/api/delivery-requirements")
                        .with(jwt().jwt(token -> token.subject("manager-subject"))
                                .authorities(() -> "DELIVERY_DEMAND_MANAGE"))
                        .contentType("application/json")
                        .content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.destination").value("Da Nang Distribution Center"))
                .andExpect(jsonPath("$.priority").value("HIGH"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.packageIds[0]").value(packageId))
                .andReturn().getResponse().getHeader("Location");

        mockMvc.perform(get(location)
                        .with(jwt().jwt(token -> token.subject("manager-subject"))
                                .authorities(() -> "DELIVERY_DEMAND_MANAGE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.destination").value("Da Nang Distribution Center"))
                .andExpect(jsonPath("$.packageIds[0]").value(packageId));
    }

    @Test
    void managerCannotCreateRequirementWithAnotherCompanyPackage() throws Exception {
        Company otherCompany = Company.builder()
                .companyCode("OTHER")
                .companyName("Other Logistics")
                .taxCode("OTHER-TAX")
                .billingEmail("billing@other.test")
                .status(CompanyStatus.ACTIVE)
                .createdAt(Instant.now())
                .build();
        entityManager.persist(otherCompany);

        PackageType otherType = PackageType.builder()
                .company(otherCompany)
                .typeCode("OTHER-BOX")
                .name("Other Box")
                .length(100)
                .width(100)
                .height(100)
                .build();
        entityManager.persist(otherType);

        TransportOrder otherOrder = TransportOrder.builder()
                .company(otherCompany)
                .orderCode("OTHER-ORDER")
                .status(fu.se184491.loadmaster_be.constant.order.OrderStatus.PENDING)
                .build();
        entityManager.persist(otherOrder);

        CargoPackage otherPackage = CargoPackage.builder()
                .order(otherOrder)
                .packageType(otherType)
                .trackingBarcode("OTHER-PKG")
                .actualWeightKg(BigDecimal.ONE)
                .status(PackageStatus.PENDING)
                .build();
        entityManager.persist(otherPackage);
        entityManager.flush();

        String request = """
                {
                  "destination": "Da Nang Distribution Center",
                  "deadline": "%s",
                  "priority": "NORMAL",
                  "packageIds": [%d]
                }
                """.formatted(LocalDateTime.now().plusDays(2).withNano(0), otherPackage.getId());

        mockMvc.perform(post("/api/delivery-requirements")
                        .with(jwt().jwt(token -> token.subject("manager-subject"))
                                .authorities(() -> "DELIVERY_DEMAND_MANAGE"))
                        .contentType("application/json")
                        .content(request))
                .andExpect(status().isNotFound());
    }

    @Test
    void managerCannotMixPackageHandlingClassesInOneRequirement() throws Exception {
        CargoPackage fragilePackage = CargoPackage.builder()
                .order(order)
                .packageType(packageType)
                .trackingBarcode("PKG-FRAGILE")
                .actualWeightKg(BigDecimal.ONE)
                .status(PackageStatus.PENDING)
                .handlingClass(HandlingClass.FRAGILE)
                .build();
        entityManager.persist(fragilePackage);
        entityManager.flush();

        String request = """
                {
                  "destination": "Da Nang Distribution Center",
                  "deadline": "%s",
                  "priority": "NORMAL",
                  "packageIds": [%d, %d]
                }
                """.formatted(
                LocalDateTime.now().plusDays(2).withNano(0),
                packageId,
                fragilePackage.getId());

        mockMvc.perform(post("/api/delivery-requirements")
                        .with(jwt().jwt(token -> token.subject("manager-subject"))
                                .authorities(() -> "DELIVERY_DEMAND_MANAGE"))
                        .contentType("application/json")
                        .content(request))
                .andExpect(status().isBadRequest());
    }

    @Test
    void managerCannotCreateRequirementWithPastDeadline() throws Exception {
        String request = """
                {
                  "destination": "Da Nang Distribution Center",
                  "deadline": "%s",
                  "priority": "NORMAL",
                  "packageIds": [%d]
                }
                """.formatted(LocalDateTime.now().minusMinutes(1).withNano(0), packageId);

        mockMvc.perform(post("/api/delivery-requirements")
                        .with(jwt().jwt(token -> token.subject("manager-subject"))
                                .authorities(() -> "DELIVERY_DEMAND_MANAGE"))
                        .contentType("application/json")
                        .content(request))
                .andExpect(status().isBadRequest());
    }

    @Test
    void managerCanListRequirementsByStatusAndDeadlineRangeWithinOwnCompany() throws Exception {
        LocalDateTime from = LocalDateTime.now().plusDays(1).withNano(0);
        LocalDateTime to = from.plusDays(2);
        DeliveryRequirement matching = persistRequirement(
                company, manager, "Matching", from.plusHours(1), DeliveryRequirementStatus.PENDING);
        persistRequirement(company, manager, "Too late", to.plusDays(1), DeliveryRequirementStatus.PENDING);
        persistRequirement(company, manager, "Already assigned", from.plusHours(2), DeliveryRequirementStatus.ASSIGNED);

        Company otherCompany = Company.builder()
                .companyCode("LIST-OTHER")
                .companyName("List Other Logistics")
                .taxCode("LIST-OTHER-TAX")
                .billingEmail("list@other.test")
                .status(CompanyStatus.ACTIVE)
                .createdAt(Instant.now())
                .build();
        entityManager.persist(otherCompany);
        persistRequirement(otherCompany, null, "Other tenant", from.plusHours(1), DeliveryRequirementStatus.PENDING);
        entityManager.flush();

        mockMvc.perform(get("/api/delivery-requirements")
                        .param("status", "PENDING")
                        .param("deadlineFrom", from.toString())
                        .param("deadlineTo", to.toString())
                        .param("page", "0")
                        .param("size", "20")
                        .with(jwt().jwt(token -> token.subject("manager-subject"))
                                .authorities(() -> "DELIVERY_DEMAND_MANAGE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(matching.getId()))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void managerCanUpdateDeadlineAndPriority() throws Exception {
        DeliveryRequirement requirement = persistRequirement(
                company,
                manager,
                "Original destination",
                LocalDateTime.now().plusDays(2).withNano(0),
                DeliveryRequirementStatus.PENDING);
        entityManager.flush();
        String newDeadline = LocalDateTime.now().plusDays(5).withNano(0).toString();

        String request = """
                {
                  "deadline": "%s",
                  "priority": "URGENT"
                }
                """.formatted(newDeadline);

        mockMvc.perform(patch("/api/delivery-requirements/{id}", requirement.getId())
                        .with(jwt().jwt(token -> token.subject("manager-subject"))
                                .authorities(() -> "DELIVERY_DEMAND_MANAGE"))
                        .contentType("application/json")
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deadline").value(newDeadline))
                .andExpect(jsonPath("$.priority").value("URGENT"))
                .andExpect(jsonPath("$.destination").value("Original destination"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void managerCanDeletePendingRequirement() throws Exception {
        DeliveryRequirement requirement = persistRequirement(
                company,
                manager,
                "Pending requirement",
                LocalDateTime.now().plusDays(2),
                DeliveryRequirementStatus.PENDING);
        entityManager.flush();

        mockMvc.perform(delete("/api/delivery-requirements/{id}", requirement.getId())
                        .with(jwt().jwt(token -> token.subject("manager-subject"))
                                .authorities(() -> "DELIVERY_DEMAND_MANAGE")))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/delivery-requirements/{id}", requirement.getId())
                        .with(jwt().jwt(token -> token.subject("manager-subject"))
                                .authorities(() -> "DELIVERY_DEMAND_MANAGE")))
                .andExpect(status().isNotFound());
    }

    @Test
    void managerCannotDeleteAssignedRequirement() throws Exception {
        DeliveryRequirement requirement = persistRequirement(
                company,
                manager,
                "Assigned requirement",
                LocalDateTime.now().plusDays(2),
                DeliveryRequirementStatus.ASSIGNED);
        entityManager.flush();

        mockMvc.perform(delete("/api/delivery-requirements/{id}", requirement.getId())
                        .with(jwt().jwt(token -> token.subject("manager-subject"))
                                .authorities(() -> "DELIVERY_DEMAND_MANAGE")))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/delivery-requirements/{id}", requirement.getId())
                        .with(jwt().jwt(token -> token.subject("manager-subject"))
                                .authorities(() -> "DELIVERY_DEMAND_MANAGE")))
                .andExpect(status().isOk());
    }

    @Test
    void requirementMovesFromPendingToAssignedToInTrip() {
        DeliveryRequirement requirement = persistRequirement(
                company,
                manager,
                "Status flow requirement",
                LocalDateTime.now().plusDays(2),
                DeliveryRequirementStatus.PENDING);
        entityManager.flush();

        org.junit.jupiter.api.Assertions.assertEquals(
                DeliveryRequirementStatus.ASSIGNED,
                deliveryRequirementService.markAssigned(company.getId(), requirement.getId()).getStatus());
        org.junit.jupiter.api.Assertions.assertEquals(
                DeliveryRequirementStatus.IN_TRIP,
                deliveryRequirementService.markInTrip(company.getId(), requirement.getId()).getStatus());
    }

    @Test
    void requirementCannotSkipAssignedState() {
        DeliveryRequirement requirement = persistRequirement(
                company,
                manager,
                "Invalid status flow requirement",
                LocalDateTime.now().plusDays(2),
                DeliveryRequirementStatus.PENDING);
        entityManager.flush();

        org.junit.jupiter.api.Assertions.assertThrows(
                AppException.class,
                () -> deliveryRequirementService.markInTrip(company.getId(), requirement.getId()));
    }

    @Test
    void authenticatedUserWithoutDeliveryDemandManageAuthorityIsForbidden() throws Exception {
        DeliveryRequirement requirement = persistRequirement(
                company,
                manager,
                "Protected requirement",
                LocalDateTime.now().plusDays(2),
                DeliveryRequirementStatus.PENDING);
        entityManager.flush();

        mockMvc.perform(get("/api/delivery-requirements/{id}", requirement.getId())
                        .with(jwt().jwt(token -> token.subject("manager-subject"))))
                .andExpect(status().isForbidden());
    }

    private DeliveryRequirement persistRequirement(
            Company owner,
            User creator,
            String destination,
            LocalDateTime deadline,
            DeliveryRequirementStatus status
    ) {
        DeliveryRequirement requirement = DeliveryRequirement.builder()
                .company(owner)
                .createdBy(creator)
                .destination(destination)
                .deadline(deadline)
                .priority(DeliveryRequirementPriority.NORMAL)
                .status(status)
                .build();
        entityManager.persist(requirement);
        return requirement;
    }
}
