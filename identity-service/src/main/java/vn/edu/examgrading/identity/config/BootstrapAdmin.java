package vn.edu.examgrading.identity.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import vn.edu.examgrading.identity.service.IdentityAuthService;

@Configuration
public class BootstrapAdmin {
    @Bean CommandLineRunner bootstrapExamOffice(IdentityAuthService auth, @Value("${app.auth.bootstrap-username}") String username,
        @Value("${app.auth.bootstrap-password}") String password) { return ignored -> auth.bootstrap(username, password); }
}
