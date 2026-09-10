package com.billing.reconciliation.activity;

import com.billing.reconciliation.model.BatchRef;
import com.billing.reconciliation.model.FileNotification;
import com.billing.reconciliation.model.FileValidationResult;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.util.List;

@ActivityInterface
public interface FileActivities {

    /**
     * Locates the file in destination and SHA-256 validates it against the producer's sidecar.
     * Throws (fails the activity, shown red in the UI) on any problem: "not found yet" throws a
     * retryable failure so a large file that is still landing is retried; "present but wrong" throws
     * a non-retryable failure. Run identity is the billing file checksum.
     */
    @ActivityMethod
    FileValidationResult locateAndValidate(FileNotification notification);

    /** Splits the validated billing CSV (and matching GL) into child-workflow batches. */
    @ActivityMethod
    List<BatchRef> sliceBatches(String fileId, int batchSize);
}
