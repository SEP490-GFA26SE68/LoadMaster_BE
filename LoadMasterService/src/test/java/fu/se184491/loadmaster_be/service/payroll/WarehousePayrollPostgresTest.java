package fu.se184491.loadmaster_be.service.payroll;

import fu.se184491.loadmaster_be.client.BrevoClient;
import fu.se184491.loadmaster_be.constant.payroll.PayrollPeriodStatus;
import fu.se184491.loadmaster_be.converter.InstantToStringConverter;
import fu.se184491.loadmaster_be.dto.response.payroll.WarehousePayrollPeriodResponse;
import fu.se184491.loadmaster_be.dto.response.payroll.WarehousePayrollStatementResponse;
import fu.se184491.loadmaster_be.dto.response.payroll.WarehousePayrollSummaryResponse;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.service.payroll.impl.WarehousePayrollServiceImpl;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.postgresql.PostgreSQLContainer;

import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Lương kho trên PostgreSQL thật: bảng lương kho dựng bằng đúng V54 (không phải ddl-auto) và Hibernate kiểm khớp entity;
 * kg lấy từ xác nhận xếp của nhân viên kho trong tháng, bỏ xác nhận tay còn chờ duyệt và việc của công ty khác.
 */
class WarehousePayrollPostgresTest {

    private static final String V54 = "db/migration/V54__create_warehouse_payroll.sql";

    static PostgreSQLContainer postgres;
    static AnnotationConfigApplicationContext context;
    static JdbcTemplate sql;
    static WarehousePayrollService service;

    @Configuration
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackages = "fu.se184491.loadmaster_be.repository",
            excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                    pattern = "^(?!.*(?:CompanyRepository|UserRepository|CompanyWarehousePayrollRateRepository"
                            + "|WarehouseBaseSalaryOverrideRepository|WarehousePayrollPeriodRepository|WarehousePayrollRepository"
                            + "|PlacementConfirmationRepository)$).*$"))
    @Import(WarehousePayrollServiceImpl.class)
    static class Config {
        @Bean DataSource dataSource() {
            var target = java.net.URI.create(postgres.getJdbcUrl().substring(5));
            if (!"/warehouse_payroll_test".equals(target.getPath())
                    || target.getPort() != postgres.getMappedPort(5432) || target.getPort() == 5432)
                throw new IllegalStateException("Fresh test container required");
            return new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        }

        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds,
                org.springframework.beans.factory.config.ConfigurableListableBeanFactory beans) {
            return factory(ds, "create", beans);
        }

        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory f) {
            return new JpaTransactionManager(f);
        }

        @Bean fu.se184491.loadmaster_be.service.qr.AesQrTokenService qr() {
            var qr = mock(fu.se184491.loadmaster_be.service.qr.AesQrTokenService.class);
            when(qr.encrypt(any())).thenAnswer(a -> a.getArgument(0));
            when(qr.decrypt(any())).thenAnswer(a -> a.getArgument(0));
            return qr;
        }

        @Bean BrevoClient brevoClient() {
            return mock(BrevoClient.class);
        }
    }

    static LocalContainerEntityManagerFactoryBean factory(DataSource ds, String ddl,
            org.springframework.beans.factory.config.ConfigurableListableBeanFactory beans) {
        var factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(ds);
        factory.setPackagesToScan("fu.se184491.loadmaster_be.entity");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", ddl,
                "jakarta.persistence.validation.mode", "none", "hibernate.show_sql", "false",
                "hibernate.resource.beans.container", new org.springframework.orm.jpa.hibernate.SpringBeanContainer(beans)));
        return factory;
    }

    @BeforeAll
    static void start() throws Exception {
        postgres = new PostgreSQLContainer("postgres:17-alpine").withDatabaseName("warehouse_payroll_test");
        postgres.start();
        context = new AnnotationConfigApplicationContext(Config.class);
        sql = new JdbcTemplate(context.getBean(DataSource.class));
        // Bảng lương kho do ddl-auto tạo được thay bằng đúng V54, để test chạy trên schema thật của production.
        sql.execute("DROP TABLE warehouse_payroll_trip_lines, warehouse_payrolls, warehouse_payroll_periods,"
                + " warehouse_base_salary_overrides, company_warehouse_payroll_rates CASCADE");
        try (InputStream in = WarehousePayrollPostgresTest.class.getClassLoader().getResourceAsStream(V54)) {
            assertNotNull(in, V54);
            sql.execute(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        service = context.getBean(WarehousePayrollService.class);
    }

    @AfterAll
    static void stop() {
        if (context != null) context.close();
        if (postgres != null) postgres.stop();
    }

    @BeforeEach
    void fixture() {
        sql.execute("TRUNCATE companies RESTART IDENTITY CASCADE");
        sql.update("INSERT INTO companies(id,company_code,company_name,tax_code,billing_email,status) VALUES"
                + "(1,'W1','Long Bình','W1','one@example.test','ACTIVE'),(2,'W2','Phương Nam','W2','two@example.test','ACTIVE')");
        String stamp = new InstantToStringConverter().convertToDatabaseColumn(Instant.parse("2026-09-01T00:00:00Z"));
        Object[][] users = {
                {8L, 1L, "DRIVER", "ACTIVE"}, {9L, 1L, "WAREHOUSE_WORKER", "ACTIVE"}, {10L, 1L, "WAREHOUSE_WORKER", "ACTIVE"},
                {11L, 1L, "DISPATCHER", "ACTIVE"}, {12L, 1L, "WAREHOUSE_WORKER", "INACTIVE"},
                {20L, 2L, "WAREHOUSE_WORKER", "ACTIVE"}, {21L, 2L, "DRIVER", "ACTIVE"}};
        for (Object[] u : users) {
            sql.update("INSERT INTO users(id,company_id,keycloak_id,email,full_name,user_role_type,status,created_at,updated_at)"
                    + " VALUES(?,?,?,?,?,?,?,?,?)", u[0], u[1], "kc-" + u[0], u[0] + "@example.test", "Người " + u[0],
                    u[2], u[3], stamp, stamp);
        }
        sql.update("INSERT INTO vehicle_types(id,company_id,name,inner_length,inner_width,inner_height,max_payload_kg,"
                + "max_cog_offset_ratio,hazardous_capable) VALUES(1,1,'Xe 1',400,200,200,2000,0.15,false),"
                + "(2,2,'Xe 2',400,200,200,2000,0.15,false)");
        sql.update("INSERT INTO vehicles(id,company_id,vehicle_type_id,name,license_plate,status) VALUES"
                + "(1,1,1,'Xe 1','TEST-1','AVAILABLE'),(2,2,2,'Xe 2','TEST-2','AVAILABLE')");
        sql.update("INSERT INTO trips(id,company_id,trip_code,driver_user_id,created_by_dispatcher_id,vehicle_id,status) VALUES"
                + "(10,1,'TRIP-10',8,11,1,'DELIVERED'),(12,2,'TRIP-12',21,21,2,'DELIVERED')");
        for (long trip : new long[]{10, 12}) {
            sql.update("INSERT INTO optimization_jobs(id,trip_id,job_uuid,status) VALUES(?,?,?,'COMPLETED')", trip + 30, trip, "job-" + trip);
            sql.update("INSERT INTO load_plans(id,job_id,is_approved,is_stale,version) VALUES(?,?,true,false,1)", trip + 40, trip + 30);
        }
        sql.update("INSERT INTO delivery_stops(id,trip_id,stop_sequence,stop_name,status) VALUES(30,10,1,'Biên Hoà','COMPLETED')");
        sql.update("INSERT INTO package_types(id,company_id,name,weight_kg) VALUES(1,1,'Thùng 7,25 kg',7.25)");
        // id, công ty, khối lượng thật (null: lấy khối lượng loại kiện), loại kiện
        Object[][] packages = {
                {1, 1, "10.00", null}, {2, 1, "20.00", null}, {3, 1, "5.50", null}, {4, 1, "8.00", null},
                {5, 1, "9.00", null}, {6, 1, null, 1}, {7, 2, "3.00", null}, {8, 1, "4.00", null}, {9, 1, "6.00", null}};
        for (Object[] p : packages) {
            int id = (Integer) p[0];
            sql.update("INSERT INTO packages(id,company_id,package_code,qr_token,handling_class,is_pinned,status,actual_weight_kg,"
                    + "package_type_id) VALUES(?,?,?,?,'STANDARD',false,'DELIVERED',?,?)", id, p[1], "PK-" + id, "QR-" + id,
                    p[2] == null ? null : new BigDecimal((String) p[2]), p[3]);
            sql.update("INSERT INTO package_placements(id,load_plan_id,package_id,stop_zone_id,pos_x,pos_y,pos_z,packed_length,"
                    + "packed_width,packed_height,rotation_type,step_sequence,pinned) VALUES(?,?,?,?,?,0,0,10,10,10,0,?,false)",
                    id + 70, id == 7 ? 52 : 50, id, 30, id * 20, id);
        }
        // placement, người xếp, giờ xác nhận (giờ Việt Nam)
        Object[][] confirmations = {
                {71, 9, "2026-10-05 08:00:00"},  // đối chiếu nhãn: tính
                {72, 9, "2026-10-06 09:00:00"},  // xác nhận tay còn chờ: chưa tính
                {73, 9, "2026-10-06 10:00:00"},  // xác nhận tay đã duyệt: tính
                {74, 9, "2026-09-30 23:59:59"},  // tháng trước
                {75, 9, "2026-11-01 00:00:00"},  // tháng sau (mốc đầu tháng sau không thuộc tháng 10)
                {76, 9, "2026-10-31 23:59:59"},  // giây cuối tháng, kiện chỉ có khối lượng loại kiện: tính
                {77, 20, "2026-10-10 08:00:00"}, // công ty khác
                {78, 9, "2026-10-07 09:00:00"},  // xếp lại bằng nhãn sau khi xác nhận tay bị từ chối: tính
                {79, 12, "2026-10-08 08:00:00"}  // người đã nghỉ: giữ kg, không lương cứng
        };
        for (Object[] c : confirmations) {
            sql.update("INSERT INTO placement_confirmations(placement_id,confirmed_by,confirmed_at) VALUES(?,?,CAST(? AS timestamp))",
                    c[0], c[1], c[2]);
        }
        manual(2, 72, "PENDING", "2026-10-06 09:00:00");
        manual(3, 73, "APPROVED", "2026-10-06 10:00:00");
        manual(8, 78, "REJECTED", "2026-10-07 08:00:00");
        rate(1, "25.000", "300000");
        rate(2, null, "0");
    }

    private static void manual(int pkg, int placement, String status, String at) {
        sql.update("INSERT INTO manual_unload_confirmations(trip_id,delivery_stop_id,package_id,reason,status,requested_by,"
                + "requested_at,step,placement_id) VALUES(10,30,?,'LABEL_DAMAGED',?,9,CAST(? AS timestamp),'LOADING',?)",
                pkg, status, at, placement);
    }

    private static void rate(long company, String kpiTarget, String kpiBonus) {
        sql.update("INSERT INTO company_warehouse_payroll_rates(company_id,effective_from,base_salary_vnd,rate_vnd_per_kg,"
                + "kpi_target_kg,kpi_bonus_vnd) VALUES(?,DATE '2026-10-01',5000000,150.50,?,?)",
                company, kpiTarget == null ? null : new BigDecimal(kpiTarget), new BigDecimal(kpiBonus));
    }

    private static WarehousePayrollSummaryResponse line(WarehousePayrollPeriodResponse period, long workerId) {
        return period.workers().stream().filter(w -> w.workerId() == workerId).findFirst().orElseThrow();
    }

    @Test
    void v54MatchesTheEntitiesUnderHibernateValidate() {
        var validating = factory(context.getBean(DataSource.class), "validate", context.getBeanFactory());
        validating.afterPropertiesSet();
        assertNotNull(validating.getObject());
        validating.destroy();
    }

    @Test
    void calculateLockAndPayslipCountOnlyValidLoadingsOfTheCompany() {
        WarehousePayrollPeriodResponse draft = service.calculatePeriod(1L, "2026-10");

        assertEquals(PayrollPeriodStatus.DRAFT, draft.status());
        // Hai nhân viên kho đang làm + người đã nghỉ có kg trong tháng; không có tài xế, điều phối hay người công ty khác.
        assertEquals(List.of(9L, 10L, 12L), draft.workers().stream().map(WarehousePayrollSummaryResponse::workerId).toList());
        var worker = line(draft, 9L);
        // 10 + 5,5 (tay đã duyệt) + 7,25 (loại kiện) + 4 (xếp lại sau khi bị từ chối) = 26,75 kg; bỏ 20 kg tay còn chờ.
        assertEquals(4, worker.loadedPackages());
        assertEquals(0, new BigDecimal("26.750").compareTo(worker.loadedKg()));
        // 26,75 × 150,50 = 4.025,875 → 4.026 đ; đạt KPI 25 kg.
        assertEquals(0, new BigDecimal("4026").compareTo(worker.loadedAmountVnd()));
        assertTrue(worker.kpiAchieved());
        assertEquals(0, new BigDecimal("5304026").compareTo(worker.totalSalaryVnd()));
        var idle = line(draft, 10L);
        assertEquals(0, idle.loadedPackages());
        assertEquals(0, new BigDecimal("5000000").compareTo(idle.totalSalaryVnd()));
        var left = line(draft, 12L);
        assertEquals(0, left.baseSalaryVnd().signum());
        assertEquals(0, new BigDecimal("903").compareTo(left.totalSalaryVnd()));

        // Nhân viên kho chưa thấy kg, tiền theo kg hay KPI khi kỳ còn nháp.
        WarehousePayrollStatementResponse beforeLock = service.getWorkerStatement(1L, 9L, "2026-10");
        assertFalse(beforeLock.calculated());
        assertEquals(PayrollPeriodStatus.DRAFT, beforeLock.status());
        assertEquals(0, new BigDecimal("5000000").compareTo(beforeLock.baseSalaryVnd()));
        assertNull(beforeLock.loadedKg());
        assertNull(beforeLock.totalSalaryVnd());
        assertTrue(beforeLock.trips().isEmpty());

        // Điều phối viên duyệt xác nhận tay còn chờ: tính lại kỳ nháp thì kiện đó được tính.
        sql.update("UPDATE manual_unload_confirmations SET status='APPROVED' WHERE placement_id=72");
        var recalculated = line(service.calculatePeriod(1L, "2026-10"), 9L);
        assertEquals(5, recalculated.loadedPackages());
        assertEquals(0, new BigDecimal("46.750").compareTo(recalculated.loadedKg()));
        // 46,75 × 150,50 = 7.035,875 → 7.036 đ.
        assertEquals(0, new BigDecimal("5307036").compareTo(recalculated.totalSalaryVnd()));
        assertEquals(3, sql.queryForObject("SELECT count(*) FROM warehouse_payrolls", Integer.class));

        assertEquals(PayrollPeriodStatus.LOCKED, service.lockPeriod(1L, "2026-10").status());
        assertEquals(ErrorCode.PAYROLL_PERIOD_LOCKED, assertThrows(AppException.class,
                () -> service.calculatePeriod(1L, "2026-10")).getErrorCode());
        assertEquals(ErrorCode.PAYROLL_PERIOD_LOCKED, assertThrows(AppException.class,
                () -> service.deleteDraftPeriod(1L, "2026-10")).getErrorCode());

        WarehousePayrollStatementResponse payslip = service.getWorkerStatement(1L, 9L, "2026-10");
        assertTrue(payslip.calculated());
        assertEquals(PayrollPeriodStatus.LOCKED, payslip.status());
        assertEquals(0, new BigDecimal("5307036").compareTo(payslip.totalSalaryVnd()));
        assertEquals(0, new BigDecimal("150.50").compareTo(payslip.rateVndPerKg()));
        assertEquals(1, payslip.trips().size());
        assertEquals("TRIP-10", payslip.trips().get(0).tripCode());
        assertEquals(5, payslip.trips().get(0).loadedPackages());
        assertEquals("2026-10-31", payslip.trips().get(0).loadedDate().toString());

        // Mở chốt thì kỳ lại là nháp, tính lại được.
        assertEquals(PayrollPeriodStatus.DRAFT, service.unlockPeriod(1L, "2026-10").status());
        assertEquals(PayrollPeriodStatus.DRAFT, service.calculatePeriod(1L, "2026-10").status());
    }

    @Test
    void otherCompanyOnlyCountsItsOwnLoadings() {
        WarehousePayrollPeriodResponse period = service.calculatePeriod(2L, "2026-10");

        assertEquals(List.of(20L), period.workers().stream().map(WarehousePayrollSummaryResponse::workerId).toList());
        var worker = line(period, 20L);
        assertEquals(1, worker.loadedPackages());
        assertEquals(0, new BigDecimal("3.000").compareTo(worker.loadedKg()));
        assertFalse(worker.kpiAchieved());
        // Không nhìn được phiếu của nhân viên công ty khác.
        assertEquals(ErrorCode.USER_NOT_FOUND, assertThrows(AppException.class,
                () -> service.getCompanyWorkerStatement(2L, 9L, "2026-10")).getErrorCode());
    }

    @Test
    void workerWithoutAnyPeriodSeesBaseOnly() {
        WarehousePayrollStatementResponse statement = service.getWorkerStatement(1L, 10L, "2026-10");

        assertFalse(statement.calculated());
        assertNull(statement.status());
        assertEquals(0, new BigDecimal("5000000").compareTo(statement.baseSalaryVnd()));
    }
}
