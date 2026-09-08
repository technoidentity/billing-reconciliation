package com.billing.reconciliation.dto;

public class CustomerDto {

    private String customerId;
    private String customerName;
    private String status;

    public CustomerDto() {
    }

    public CustomerDto(String customerId, String customerName, String status) {
        this.customerId = customerId;
        this.customerName = customerName;
        this.status = status;
    }

    public String getCustomerId() {
        return customerId;
    }

    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    public String getCustomerName() {
        return customerName;
    }

    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
