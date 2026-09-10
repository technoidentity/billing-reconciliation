package com.billing.reconciliation.file;

import com.billing.reconciliation.config.BillingProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

@Component
public class LocalInboundFiles implements InboundFileAccess {

    private final BillingProperties properties;

    public LocalInboundFiles(BillingProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean exists(String directory, String fileName) {
        return Files.isRegularFile(resolve(directory, fileName));
    }

    @Override
    public void copy(String sourceDirectory, String destDirectory, String fileName) {
        Path source = resolve(sourceDirectory, fileName);
        Path dest = resolve(destDirectory, fileName);
        try {
            Files.createDirectories(dest.getParent());
            Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "Failed to copy " + fileName + " from " + sourceDirectory + " to " + destDirectory, ex);
        }
    }

    @Override
    public void download(String directory, String fileName, Path localTarget) {
        Path source = resolve(directory, fileName);
        try {
            Files.createDirectories(localTarget.getParent());
            Files.copy(source, localTarget, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to copy file " + directory + "/" + fileName, ex);
        }
    }

    @Override
    public Optional<String> readText(String directory, String fileName) {
        Path path = resolve(directory, fileName);
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(path));
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read file " + directory + "/" + fileName, ex);
        }
    }

    private Path resolve(String directory, String fileName) {
        String baseName = Path.of(fileName).getFileName().toString();
        Path root = Path.of(properties.getFiles().getRoot()).toAbsolutePath().normalize();
        String dir = directory == null ? "" : directory.replace('\\', '/').replaceAll("^/+", "");
        return dir.isBlank() ? root.resolve(baseName) : root.resolve(dir).resolve(baseName);
    }
}
