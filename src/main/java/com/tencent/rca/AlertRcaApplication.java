package com.tencent.rca;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * AI 告警自动归因分析系统启动类.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AlertRcaApplication {

    public static void main(String[] args) {
        SpringApplication.run(AlertRcaApplication.class, args);
    }
}
