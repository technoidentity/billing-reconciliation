package com.billing.reconciliation.dto;

public class VendorDto {

    private String vendorId;
    private String vendorName;
    private String status;

    public VendorDto() {
    }

    public VendorDto(String vendorId, String vendorName, String status) {
        this.vendorId = vendorId;
        this.vendorName = vendorName;
        this.status = status;
    }

    public String getVendorId() {
        return vendorId;
    }

    public void setVendorId(String vendorId) {
        this.vendorId = vendorId;
    }

    public String getVendorName() {
        return vendorName;
    }

    public void setVendorName(String vendorName) {
        this.vendorName = vendorName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
