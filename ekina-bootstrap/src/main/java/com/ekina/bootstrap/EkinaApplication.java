package com.ekina.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/**
 * Ekina Application Bootstrap Entrypoint
 */
@SpringBootApplication(scanBasePackages = "com.ekina")
@EnableJpaRepositories(basePackages = "com.ekina")
@EntityScan(basePackages = "com.ekina")
@org.springframework.scheduling.annotation.EnableAsync
@org.springframework.scheduling.annotation.EnableScheduling
public class EkinaApplication {

    public static void main(String[] args) {
        loadEnv();
        SpringApplication.run(EkinaApplication.class, args);
    }

    private static void loadEnv() {
        try {
            if (Files.exists(Paths.get(".env"))) {
                List<String> lines = Files.readAllLines(Paths.get(".env"));
                for (String line : lines) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    int eqIdx = line.indexOf('=');
                    if (eqIdx > 0) {
                        String key = line.substring(0, eqIdx).trim();
                        String value = line.substring(eqIdx + 1).trim();
                        // Strip quotes if present
                        if (value.startsWith("\"") && value.endsWith("\"")) {
                            value = value.substring(1, value.length() - 1);
                        } else if (value.startsWith("'") && value.endsWith("'")) {
                            value = value.substring(1, value.length() - 1);
                        }
                        System.setProperty(key, value);
                    }
                }
                System.out.println("Loaded environment variables from local .env file.");
            }
        } catch (IOException e) {
            System.err.println("Warning: Failed to load local .env file: " + e.getMessage());
        }
    }
}

