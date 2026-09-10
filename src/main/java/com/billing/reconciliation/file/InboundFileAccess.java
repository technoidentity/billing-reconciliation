package com.billing.reconciliation.file;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Reads and copies billing/GL files on the Docker SFTP mount (home → destination)
 * or a local pair of directories used by tests.
 */
public interface InboundFileAccess {

    boolean exists(String directory, String fileName);

    void copy(String sourceDirectory, String destDirectory, String fileName);

    void download(String directory, String fileName, Path localTarget);

    Optional<String> readText(String directory, String fileName);
}
