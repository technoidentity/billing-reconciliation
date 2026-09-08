package com.billing.reconciliation.api;

import com.billing.reconciliation.db.BillingJdbc;
import com.billing.reconciliation.dto.CustomerDto;
import com.billing.reconciliation.dto.VendorDto;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class EnrichmentApiController {

    private final BillingJdbc jdbc;

    public EnrichmentApiController(BillingJdbc jdbc) {
        this.jdbc = jdbc;
    }

    @PostMapping("/vendors/bulk")
    public List<VendorDto> vendors(@RequestBody List<String> ids) {
        return jdbc.findVendors(ids);
    }

    @PostMapping("/customers/bulk")
    public List<CustomerDto> customers(@RequestBody List<String> ids) {
        return jdbc.findCustomers(ids);
    }
}
