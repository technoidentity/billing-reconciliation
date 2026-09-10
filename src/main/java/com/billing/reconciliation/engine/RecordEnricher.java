package com.billing.reconciliation.engine;

import com.billing.reconciliation.dto.BillingTransactionDto;
import com.billing.reconciliation.dto.CustomerDto;
import com.billing.reconciliation.dto.ProcessedTransactionDto;
import com.billing.reconciliation.dto.VendorDto;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Merges vendor and customer names onto valid billing rows. Rows without a matching
 * vendor or customer are skipped. Callers write the result to CSV.
 */
@Component
public class RecordEnricher {

    public List<ProcessedTransactionDto> enrich(
            List<BillingTransactionDto> rows,
            Map<String, VendorDto> vendors,
            Map<String, CustomerDto> customers,
            int batchNo) {
        List<ProcessedTransactionDto> processed = new ArrayList<>();
        if (rows == null || rows.isEmpty()) {
            return processed;
        }
        Map<String, VendorDto> vendorMap = vendors == null ? Map.of() : vendors;
        Map<String, CustomerDto> customerMap = customers == null ? Map.of() : customers;
        for (BillingTransactionDto row : rows) {
            VendorDto vendor = vendorMap.get(row.getVendorId());
            CustomerDto customer = customerMap.get(row.getCustomerId());
            if (vendor == null || customer == null) {
                continue;
            }
            BigDecimal amount = row.getAmount() == null ? BigDecimal.ZERO : row.getAmount();
            processed.add(new ProcessedTransactionDto(
                    row.getTxnId(),
                    vendor.getVendorName(),
                    customer.getCustomerName(),
                    row.getCurrency(),
                    amount,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    amount,
                    batchNo,
                    row.getDueDate()));
        }
        return processed;
    }
}
