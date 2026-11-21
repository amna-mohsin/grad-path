package ai.gradpath;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * GradPath AI - Graduate Career Placement Prediction System.
 *
 * <p>A Spring Boot web application (Spring MVC + Thymeleaf) that connects students with their Career Office.
 * The official readiness score comes from a deterministic, explainable 60-rule engine ({@code ai.gradpath.readiness}).
 * A secondary, data-driven placement estimate comes from the cross-validation-selected classifier trained in Java
 * ({@code ai.gradpath.ml}); {@code train-model} as the first argument runs the trainer instead of the web app.
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
@EnableScheduling
public class GradPathApplication {

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && ai.gradpath.ml.TrainModelCommand.COMMAND.equals(args[0])) {
            ai.gradpath.ml.TrainModelCommand.main(args);
            return;
        }
        SpringApplication.run(GradPathApplication.class, args);
    }
}
