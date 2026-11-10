package com.cptvsan.adapter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test Connection uses only what it is handed: the TLS opener is built for
 * the config under test (so a certificate accepted at Validate Connection
 * applies to that host), the password never leaks, the version floor holds,
 * and no Suite API call is made.
 */
public final class ConnectionCheckTest {

    public static void main(String[] args) throws Exception {
        try (FakeOps fake = new FakeOps(2, 0)) {
            VhmConfig c = VhmConfig.of("127.0.0.1", String.valueOf(fake.port), "u", "s3cr3t", true);
            final AtomicReference<String> openedFor = new AtomicReference<>();
            // the opener stands for "TLS context for THIS config's host"
            Net.Opener opener = url -> {
                openedFor.set(url);
                return Net.PLAIN.open(url.replace("https://", "http://"));
            };
            String ok = ConnectionCheck.run(c, opener);
            Check.yes(ok.contains("1 cluster"), "describes the cluster: " + ok);
            Check.yes(openedFor.get().startsWith(c.baseUrl()), "connection opened for the tested config's host");
            Check.eq(0, fake.suitePaths.size(), "no Suite API call");
            Check.yes(fake.perfCalls.containsKey("cluster-domclient"), "Performance Service probed");

            fake.version = "8.0.2";
            IOException e = Check.fails(IOException.class, () -> ConnectionCheck.run(c, opener),
                    "version floor");
            Check.yes(e.getMessage().contains("8.0 Update 3"), "floor text: " + e.getMessage());
            fake.version = "9.1.1";
            fake.unsupported.add("cluster-domclient"); // an unsupported type still proves the service
            ConnectionCheck.run(c, opener);
            fake.unsupported.clear();
            fake.broken.add("cluster-domclient");
            e = Check.fails(IOException.class, () -> ConnectionCheck.run(c, opener), "perf failure");
            Check.yes(!e.getMessage().contains("s3cr3t"), "no password in the message");
            Check.yes(e.getMessage().contains("Performance Service did not answer"), e.getMessage());
        }
        // a dead host: readable failure
        VhmConfig dead = VhmConfig.of("127.0.0.1", "1", "u", "s3cr3t", true);
        Check.fails(Exception.class, () -> ConnectionCheck.run(dead, url ->
                Net.PLAIN.open(url.replace("https://", "http://"))), "unreachable vCenter");
        // redaction keeps the cause chain (the framework words certificate failures from it)
        // and removes the secret from every message
        VhmConfig sc = VhmConfig.of("vc.example.org", "443", "u", "s3cr3t", true);
        Exception root = new javax.net.ssl.SSLHandshakeException("PKIX path building failed");
        IOException mid = new IOException("tls to u:s3cr3t@vc failed", root);
        IOException scrubbed = ConnectionCheck.scrub(mid, sc);
        Check.yes(!scrubbed.getMessage().contains("s3cr3t"), "secret removed from the message");
        Check.yes(scrubbed.getCause() == root, "the clean cause is kept as is: " + scrubbed.getCause());
        IOException leakyRoot = new IOException("password s3cr3t rejected",
                new java.security.cert.CertificateException("untrusted"));
        IOException top = ConnectionCheck.scrub(new IOException("wrap", leakyRoot), sc);
        int depth = 0;
        for (Throwable t = top; t != null; t = t.getCause()) {
            depth++;
            Check.yes(t.getMessage() == null || !t.getMessage().contains("s3cr3t"),
                    "no secret at depth " + depth);
        }
        Check.eq(3, depth, "a leaking chain keeps its depth");
        Check.yes(top.getCause().getCause() instanceof java.security.cert.CertificateException,
                "the certificate cause survives");
        Check.done("ConnectionCheckTest");
    }
}
