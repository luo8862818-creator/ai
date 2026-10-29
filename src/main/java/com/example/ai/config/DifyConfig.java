package com.example.ai.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter @Component @ConfigurationProperties(prefix = "dify")
public class DifyConfig {
    private String baseUrl = "http://192.168.3.5:18082/v1";
    private String apiKey = "";
    private String mode = "agent";
    private String inputVariable = "";
    private String outputVariable = "";
    private Duration connectTimeout = Duration.ofSeconds(10);
    private Duration readTimeout = Duration.ofSeconds(60);
    private Map<String, Object> inputs = new LinkedHashMap<>();
}
