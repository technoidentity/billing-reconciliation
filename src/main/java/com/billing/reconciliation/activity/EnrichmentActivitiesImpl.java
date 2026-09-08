package com.billing.reconciliation.activity;

import com.billing.reconciliation.config.BillingProperties;
import com.billing.reconciliation.config.TaskQueues;
import com.billing.reconciliation.db.BillingJdbc;
import com.billing.reconciliation.model.BatchRef;
import com.billing.reconciliation.model.CustomerDto;
import com.billing.reconciliation.model.StepResult;
import com.billing.reconciliation.model.VendorDto;
import io.temporal.activity.Activity;
import io.temporal.spring.boot.ActivityImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Component
@ActivityImpl(taskQueues = TaskQueues.BILLING)
public class EnrichmentActivitiesImpl implements EnrichmentActivities {

    private static final Logger log = LoggerFactory.getLogger(EnrichmentActivitiesImpl.class);

    private final BillingJdbc jdbc;
    private final BillingProperties properties;
    private final RestClient restClient;

    public EnrichmentActivitiesImpl(BillingJdbc jdbc, BillingProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
        int timeoutMs = (int) properties.getVendorApi().getTimeout().toMillis();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public StepResult fetchVendorData(String runId, BatchRef batch) {
        Heartbeats.beat("vendors-batch-" + batch.getBatchNo());
        List<String> ids = jdbc.distinctVendorIds(batch.getFromId(), batch.getToId());
        BillingProperties.ApiEndpoint api = properties.getVendorApi();
        List<VendorDto> vendors;
        try {
            vendors = callInParallel(
                    api.getBaseUrl() + "/api/vendors/bulk", ids,
                    api.getMaxConcurrentCalls(), api.getBulkChunkSize(),
                    new ParameterizedTypeReference<List<VendorDto>>() {
                    },
                    "vendor-chunk-");
        } catch (Exception ex) {
            vendors = fallbackVendors(ids, ex);
        }
        Heartbeats.beat("vendors-save-" + batch.getBatchNo());
        jdbc.saveVendorCache(runId, vendors);
        return new StepResult("FETCH_VENDOR_DATA", vendors.size(),
                "Fetched vendors via parallel bulk calls (max " + api.getMaxConcurrentCalls() + " concurrent)");
    }

    @Override
    public StepResult fetchCustomerData(String runId, BatchRef batch) {
        Heartbeats.beat("customers-batch-" + batch.getBatchNo());
        List<String> ids = jdbc.distinctCustomerIds(batch.getFromId(), batch.getToId());
        BillingProperties.ApiEndpoint api = properties.getCustomerApi();
        List<CustomerDto> customers;
        try {
            customers = callInParallel(
                    api.getBaseUrl() + "/api/customers/bulk", ids,
                    api.getMaxConcurrentCalls(), api.getBulkChunkSize(),
                    new ParameterizedTypeReference<List<CustomerDto>>() {
                    },
                    "customer-chunk-");
        } catch (Exception ex) {
            customers = fallbackCustomers(ids, ex);
        }
        Heartbeats.beat("customers-save-" + batch.getBatchNo());
        jdbc.saveCustomerCache(runId, customers);
        return new StepResult("FETCH_CUSTOMER_DATA", customers.size(),
                "Fetched customers via parallel bulk calls (max " + api.getMaxConcurrentCalls() + " concurrent)");
    }

    @Override
    public StepResult enrichRecords(String runId, BatchRef batch) {
        Heartbeats.beat("enrich-start-" + batch.getBatchNo());
        long count = jdbc.enrichBatch(runId, batch);
        Heartbeats.beat("enrich-done-" + batch.getBatchNo());
        return new StepResult("ENRICH_RECORDS", count, "Merged vendor and customer data for batch " + batch.getBatchNo());
    }

    /**
     * Splits {@code ids} into chunks of {@code chunkSize} and calls the bulk endpoint for each chunk
     * concurrently, capped at {@code maxConcurrent} in-flight requests. Throws if any chunk fails so
     * the caller can fall back to the local table (and Temporal can retry the activity).
     */
    private <T> List<T> callInParallel(String url, List<String> ids, int maxConcurrent, int chunkSize,
                                       ParameterizedTypeReference<List<T>> responseType, String beatPrefix) {
        if (ids.isEmpty()) {
            return List.of();
        }
        int safeChunk = Math.max(1, chunkSize);
        List<List<String>> chunks = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += safeChunk) {
            chunks.add(ids.subList(i, Math.min(i + safeChunk, ids.size())));
        }
        int poolSize = Math.max(1, Math.min(maxConcurrent, chunks.size()));
        ExecutorService pool = Executors.newFixedThreadPool(poolSize);
        try {
            List<Future<List<T>>> futures = new ArrayList<>(chunks.size());
            for (List<String> chunk : chunks) {
                futures.add(pool.submit(() -> restClient.post()
                        .uri(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(chunk)
                        .retrieve()
                        .body(responseType)));
            }
            List<T> all = new ArrayList<>();
            int done = 0;
            for (Future<List<T>> future : futures) {
                List<T> part = future.get();
                if (part != null) {
                    all.addAll(part);
                }
                Heartbeats.beat(beatPrefix + (++done) + "/" + chunks.size());
            }
            return all;
        } catch (Exception ex) {
            throw new RuntimeException("Bulk enrichment call failed", ex);
        } finally {
            pool.shutdownNow();
        }
    }

    private List<VendorDto> fallbackVendors(List<String> ids, Exception ex) {
        int attempt = Activity.getExecutionContext().getInfo().getAttempt();
        int maxAttempts = properties.getActivities().getEnrichment().getRetry().getMaximumAttempts();
        if (attempt < maxAttempts) {
            throw new RuntimeException("Vendor API failed on attempt " + attempt, ex);
        }
        log.warn("Vendor API failed after {} attempts, using local vendor table", attempt, ex);
        return jdbc.findVendors(ids);
    }

    private List<CustomerDto> fallbackCustomers(List<String> ids, Exception ex) {
        int attempt = Activity.getExecutionContext().getInfo().getAttempt();
        int maxAttempts = properties.getActivities().getEnrichment().getRetry().getMaximumAttempts();
        if (attempt < maxAttempts) {
            throw new RuntimeException("Customer API failed on attempt " + attempt, ex);
        }
        log.warn("Customer API failed after {} attempts, using local customer table", attempt, ex);
        return jdbc.findCustomers(ids);
    }
}
