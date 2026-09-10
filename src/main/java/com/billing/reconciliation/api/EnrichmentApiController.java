package com.billing.reconciliation.api;

import com.billing.reconciliation.dto.CustomerDto;
import com.billing.reconciliation.dto.VendorDto;
import com.billing.reconciliation.file.ReconciliationFileStore;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class EnrichmentApiController {

    private final ReconciliationFileStore files;

    public EnrichmentApiController(ReconciliationFileStore files) {
        this.files = files;
    }

    @PostMapping("/vendors/bulk")
    public List<VendorDto> vendors(@RequestBody List<String> ids) {
        return files.findReferenceVendors(ids);
    }

    @PostMapping("/customers/bulk")
    public List<CustomerDto> customers(@RequestBody List<String> ids) {
        return files.findReferenceCustomers(ids);
    }
}
