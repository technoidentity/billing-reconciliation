package com.billing.reconciliation.file;

import com.billing.reconciliation.config.BillingProperties;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

@Component
public class SftpInboundFiles implements InboundFileAccess {

    private final BillingProperties properties;

    public SftpInboundFiles(BillingProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean exists(String directory, String fileName) {
        try (SSHClient ssh = connect(); SFTPClient sftp = ssh.newSFTPClient()) {
            return sftp.statExistence(remotePath(directory, fileName)) != null;
        } catch (IOException ex) {
            throw new IllegalStateException("SFTP exists check failed for " + directory + "/" + fileName, ex);
        }
    }

    @Override
    public void copy(String sourceDirectory, String destDirectory, String fileName) {
        Path tmp = null;
        try {
            tmp = Files.createTempFile("sftp-copy-", ".bin");
            try (SSHClient ssh = connect(); SFTPClient sftp = ssh.newSFTPClient()) {
                String source = remotePath(sourceDirectory, fileName);
                String dest = remotePath(destDirectory, fileName);
                if (sftp.statExistence(source) == null) {
                    throw new IllegalStateException("SFTP source not found: " + source);
                }
                sftp.mkdirs("/" + normalizeDir(destDirectory));
                sftp.get(source, tmp.toString());
                sftp.put(tmp.toString(), dest);
            }
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "SFTP copy failed for " + fileName + " (" + sourceDirectory + " → " + destDirectory + ")", ex);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // best-effort temp delete
                }
            }
        }
    }

    @Override
    public void download(String directory, String fileName, Path localTarget) {
        try {
            Files.createDirectories(localTarget.getParent());
            try (SSHClient ssh = connect(); SFTPClient sftp = ssh.newSFTPClient()) {
                sftp.get(remotePath(directory, fileName), localTarget.toString());
            }
        } catch (IOException ex) {
            throw new IllegalStateException("SFTP download failed for " + directory + "/" + fileName, ex);
        }
    }

    @Override
    public Optional<String> readText(String directory, String fileName) {
        try (SSHClient ssh = connect(); SFTPClient sftp = ssh.newSFTPClient()) {
            if (sftp.statExistence(remotePath(directory, fileName)) == null) {
                return Optional.empty();
            }
            Path tmp = Files.createTempFile("sftp-", ".txt");
            try {
                sftp.get(remotePath(directory, fileName), tmp.toString());
                return Optional.of(Files.readString(tmp, StandardCharsets.UTF_8));
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("SFTP read failed for " + directory + "/" + fileName, ex);
        }
    }

    private SSHClient connect() throws IOException {
        BillingProperties.Sftp sftp = properties.getSftp();
        SSHClient ssh = new SSHClient();
        ssh.addHostKeyVerifier(new PromiscuousVerifier());
        ssh.connect(sftp.getHost(), sftp.getPort());
        ssh.authPassword(sftp.getUsername(), sftp.getPassword());
        return ssh;
    }

    private String remotePath(String directory, String fileName) {
        String dir = normalizeDir(directory);
        String name = Path.of(fileName).getFileName().toString();
        return dir.isEmpty() ? "/" + name : "/" + dir + "/" + name;
    }

    private String normalizeDir(String directory) {
        if (directory == null || directory.isBlank()) {
            return "";
        }
        return directory.replace('\\', '/').replaceAll("^/+", "").replaceAll("/+$", "");
    }
}
