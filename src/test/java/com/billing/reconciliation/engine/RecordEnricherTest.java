package com.billing.reconciliation.engine;

import com.billing.reconciliation.dto.BillingTransactionDto;
import com.billing.reconciliation.dto.CustomerDto;
import com.billing.reconciliation.dto.ProcessedTransactionDto;
import com.billing.reconciliation.dto.VendorDto;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordEnricherTest {

    private final RecordEnricher enricher = new RecordEnricher();

    @Test
    void mergesVendorAndCustomerNames() {
        BillingTransactionDto row = txn(1L, "TXN1", "V1", "C1", "10.00");
        List<ProcessedTransactionDto> processed = enricher.enrich(
                List.of(row),
                Map.of("V1", new VendorDto("V1", "Acme", "ACTIVE")),
                Map.of("C1", new CustomerDto("C1", "Acme Corp", "ACTIVE")),
                3);
        assertEquals(1, processed.size());
        ProcessedTransactionDto out = processed.get(0);
        assertEquals("TXN1", out.getTxnId());
        assertEquals("Acme", out.getVendorName());
        assertEquals("Acme Corp", out.getCustomerName());
        assertEquals(new BigDecimal("10.00"), out.getOriginalAmount());
        assertEquals(new BigDecimal("10.00"), out.getFinalAmount());
        assertEquals(BigDecimal.ZERO, out.getAdjustment());
        assertEquals(3, out.getBatchNo());
    }

    @Test
    void skipsRowsMissingVendorOrCustomerCache() {
        BillingTransactionDto missingVendor = txn(1L, "TXN1", "V-MISS", "C1", "10.00");
        BillingTransactionDto missingCustomer = txn(2L, "TXN2", "V1", "C-MISS", "10.00");
        List<ProcessedTransactionDto> processed = enricher.enrich(
                List.of(missingVendor, missingCustomer),
                Map.of("V1", new VendorDto("V1", "Acme", "ACTIVE")),
                Map.of("C1", new CustomerDto("C1", "Cust", "ACTIVE")),
                1);
        assertTrue(processed.isEmpty());
    }

    private static BillingTransactionDto txn(long id, String txnId, String vendorId, String customerId, String amount) {
        return new BillingTransactionDto(id, txnId, vendorId, customerId, new BigDecimal(amount), "USD");
    }
}
