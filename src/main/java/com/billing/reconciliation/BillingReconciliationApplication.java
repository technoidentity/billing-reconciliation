package com.billing.reconciliation;

import com.billing.reconciliation.config.BillingProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(BillingProperties.class)
public class BillingReconciliationApplication {

    public static void main(String[] args) {
        SpringApplication.run(BillingReconciliationApplication.class, args);
    }
}
