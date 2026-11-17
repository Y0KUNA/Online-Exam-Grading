package vn.edu.examgrading.submission;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"vn.edu.examgrading.submission", "vn.edu.examgrading.common"})
public class SubmissionServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(SubmissionServiceApplication.class, args);
    }
}
