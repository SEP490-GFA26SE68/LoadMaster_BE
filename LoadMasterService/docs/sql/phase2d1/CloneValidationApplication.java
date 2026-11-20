package rehearsal;

import fu.se184491.loadmaster_be.repository.billing.*;
import fu.se184491.loadmaster_be.repository.optimization.OptimizationJobRepository;
import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.boot.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.client.RestClient;

/** Test-only Boot context: no component scan/auto-configuration, web, jobs or remote clients. */
@SpringBootConfiguration
@EnableTransactionManagement
@EnableJpaRepositories(basePackages="fu.se184491.loadmaster_be.repository")
public class CloneValidationApplication {
    @Bean DataSource dataSource(Environment env) {
        String url=env.getRequiredProperty("clone.jdbc-url");
        if (!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/loadmaster_clone_2d1_[a-z0-9_]+"))
            throw new IllegalStateException("Refusing a non-clone datasource");
        var ds=new DriverManagerDataSource(url,env.getRequiredProperty("clone.username"),env.getRequiredProperty("clone.password"));
        var sql=new JdbcTemplate(ds);
        String database=sql.queryForObject("select current_database()",String.class);
        if (!url.endsWith("/"+database) || !"on".equals(sql.queryForObject("show default_transaction_read_only",String.class)))
            throw new IllegalStateException("Clone identity/read-only role guard failed");
        return ds;
    }
    @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds) {
        var factory=new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(ds);
        factory.setPackagesToScan("fu.se184491.loadmaster_be.entity");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto","validate","hibernate.jdbc.time_zone","UTC"));
        return factory;
    }
    @Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
        return new JpaTransactionManager(factory);
    }
    public static void main(String[] args) {
        if (args.length!=1) throw new IllegalArgumentException("One private configuration file is required");
        try (ConfigurableApplicationContext app=new SpringApplicationBuilder(CloneValidationApplication.class)
                .web(WebApplicationType.NONE).registerShutdownHook(false)
                .run("--spring.config.location="+Path.of(args[0]).toUri(),
                     "--spring.profiles.active=phase2d-clone","--spring.flyway.enabled=false",
                     "--spring.jpa.hibernate.ddl-auto=validate","--spring.main.banner-mode=off")) {
            if (!app.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class).isEmpty()
                || !app.getBeansOfType(ApplicationRunner.class).isEmpty()
                || !app.getBeansOfType(CommandLineRunner.class).isEmpty()
                || !app.getBeansOfType(RestClient.class).isEmpty()
                || app.containsBean("flyway")) throw new IllegalStateException("Unexpected unsafe infrastructure loaded");
            var jobs=app.getBean(OptimizationJobRepository.class);
            var plans=app.getBean(SubscriptionPlanRepository.class);
            var pro=plans.findByActiveTrue().stream().filter(p->"PRO_MONTHLY".equals(p.getPlanCode())).findFirst().orElseThrow();
            System.out.println("CLONE_VALIDATION_PASS entities="+app.getBean(EntityManagerFactory.class).getMetamodel().getEntities().size()
                +" repositories="+app.getBeanNamesForType(org.springframework.data.repository.Repository.class).length
                +" jobs="+jobs.count()+" accounts="+app.getBean(CreditAccountRepository.class).count()
                +" transactions="+app.getBean(CreditTransactionRepository.class).count()
                +" subscriptions="+app.getBean(CompanySubscriptionRepository.class).count()
                +" proMonthlyCredits="+pro.getMonthlyCredits()
                +" schedulers=0 runners=0 remoteClients=0 flyway=false");
        }
    }
}
