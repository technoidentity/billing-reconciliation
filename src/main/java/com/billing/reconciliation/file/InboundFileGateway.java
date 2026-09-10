package com.billing.reconciliation.file;

import com.billing.reconciliation.config.BillingProperties;
import org.springframework.stereotype.Component;

@Component
public class InboundFileGateway {

    private final BillingProperties properties;
    private final LocalInboundFiles local;
    private final SftpInboundFiles sftp;

    public InboundFileGateway(BillingProperties properties, LocalInboundFiles local, SftpInboundFiles sftp) {
        this.properties = properties;
        this.local = local;
        this.sftp = sftp;
    }

    public InboundFileAccess active() {
        return properties.getFiles().isLocal() ? local : sftp;
    }
}
