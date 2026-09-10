package com.billing.reconciliation.config;

import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.spring.boot.TemporalOptionsCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
public class TemporalClientConfig {

    /**
     * Worker identity shown in Temporal UI. Not a starter property, so applied here.
     * Blank keeps the SDK default ({@code host@pid}).
     */
    @Bean
    public TemporalOptionsCustomizer<WorkflowClientOptions.Builder> temporalIdentityCustomizer(
            @Value("${spring.temporal.identity:}") String identity) {
        return builder -> {
            if (StringUtils.hasText(identity)) {
                builder.setIdentity(identity.trim());
            }
            return builder;
        };
    }

    /**
     * Temporal Cloud requires TLS. The starter auto-enables HTTPS only when
     * {@code enable-https} is unset; local yaml defaults it to false, so turn it
     * on whenever an API key is present.
     */
    @Bean
    public TemporalOptionsCustomizer<WorkflowServiceStubsOptions.Builder> temporalApiKeyTlsCustomizer(
            @Value("${spring.temporal.connection.api-key:}") String apiKey) {
        return builder -> {
            if (StringUtils.hasText(apiKey)) {
                builder.setEnableHttps(true);
            }
            return builder;
        };
    }
}
