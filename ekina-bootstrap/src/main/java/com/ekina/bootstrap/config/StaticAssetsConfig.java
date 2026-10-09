package com.ekina.bootstrap.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Prefer on-disk static assets (so avatar/portrait swaps don't require repackaging the JAR).
 */
@Configuration
public class StaticAssetsConfig implements WebMvcConfigurer {

    @Value("${ekina.workspace-dir:.}")
    private String workspaceDir;

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // Custom resource handlers do not auto-resolve directory → index.html.
        // Companion / tray open http://127.0.0.1:8081/shell/ which otherwise 404s.
        registry.addViewController("/shell").setViewName("forward:/shell/index.html");
        registry.addViewController("/shell/").setViewName("forward:/shell/index.html");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path dir = Path.of(workspaceDir, "ekina-bootstrap", "src", "main", "resources", "static")
                .toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) {
            return;
        }
        String location = dir.toUri().toString();
        if (!location.endsWith("/")) {
            location = location + "/";
        }
        registry.addResourceHandler("/**")
                .addResourceLocations(location)
                .resourceChain(false);
        System.out.println("[StaticAssets] serving from " + location);
    }
}
