package vn.edu.examgrading.identity.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import vn.edu.examgrading.identity.service.IdentityAuthService;

@Configuration
public class BootstrapAdmin {
    @Bean CommandLineRunner bootstrapSingletonAccounts(IdentityAuthService auth,
        @Value("${app.auth.bootstrap-username}") String officeUsername,
        @Value("${app.auth.bootstrap-password}") String officePassword,
        @Value("${app.auth.bootstrap-office-name:Phòng khảo thí}") String officeName,
        @Value("${app.auth.bootstrap-council-username:}") String councilUsername,
        @Value("${app.auth.bootstrap-council-password:}") String councilPassword,
        @Value("${app.auth.bootstrap-council-name:Hội đồng chấm thi}") String councilName) {
        return ignored -> {
            auth.bootstrapSingleton(vn.edu.examgrading.identity.domain.UserRole.EXAM_OFFICE, officeUsername, officePassword, officeName);
            auth.bootstrapSingleton(vn.edu.examgrading.identity.domain.UserRole.COUNCIL, councilUsername, councilPassword, councilName);
        };
    }
}
