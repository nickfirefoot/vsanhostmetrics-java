package com.cptvsan.adapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Adapter-instance settings (vCenter host, port, user, password, whether to
 * verify TLS) and the collection tuning, whose defaults are the Python pack's
 * {@code collector.Config}. The password is held for the Login call and
 * appears nowhere else: {@link #toString()} omits it and {@link #redact}
 * scrubs it from text bound for a log.
 */
public final class VhmConfig {

    public final String host;
    public final int port;
    public final String user;
    private final String password;
    public final boolean verifyTls;

    // Tuning: the Python defaults. Package-private and mutable so tests can shrink them.
    List<String> clusterInclude = new ArrayList<>();
    List<String> clusterExclude = new ArrayList<>();
    Set<String> skip = new java.util.HashSet<>();
    int clusterConcurrency = 4;
    int vcenterConcurrency = 4;
    int pushConcurrency = 4;
    int batchMaxObjects = 100;
    int batchMaxBytes = 1_000_000;
    double idCacheTtlMin = 30;
    double idNegativeTtlMin = 10;
    Set<String> heavy = Cadence.HEAVY;
    int buckets = Cadence.BUCKETS;
    int maxBackfillMin = 60;
    double diskCacheTtlMin = 60;
    double inventoryFullRefreshMin = 60;
    double unsupportedRecheckMin = 360;
    double emptyRecheckMin = 60;
    int verifySample = 5;
    double staggerS = 30.0;
    boolean gzip = true;
    boolean queryLabels = true;
    int httpTimeoutMs = 300_000;
    long retryPauseMs = 1000;

    private VhmConfig(String host, int port, String user, String password, boolean verifyTls) {
        this.host = host;
        this.port = port;
        this.user = user;
        this.password = password;
        this.verifyTls = verifyTls;
    }

    /**
     * @param host   vCenter FQDN or address; {@code host:port} is accepted
     * @param port   blank means 443
     * @throws IllegalArgumentException naming the first bad field, never the password
     */
    public static VhmConfig of(String host, String port, String user, String password,
            boolean verifyTls) {
        String h = host == null ? "" : host.trim();
        String ps = port == null ? "" : port.trim();
        if (ps.isEmpty() && h.indexOf(':') > 0 && h.indexOf(':') == h.lastIndexOf(':')) {
            ps = h.substring(h.indexOf(':') + 1);
            h = h.substring(0, h.indexOf(':'));
        }
        if (h.isEmpty()) {
            throw new IllegalArgumentException("vCenter Server is required");
        }
        for (int i = 0; i < h.length(); i++) {
            char c = h.charAt(i);
            if (Character.isWhitespace(c) || c == '/' || c == '@' || c == '?' || c == '#') {
                throw new IllegalArgumentException("vCenter Server must be a host name or"
                        + " address only (no scheme, path or credentials): " + h);
            }
        }
        int p = 443;
        if (!ps.isEmpty()) {
            try {
                p = Integer.parseInt(ps);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Port is not a number: " + ps);
            }
            if (p < 1 || p > 65535) {
                throw new IllegalArgumentException("Port is out of range: " + p);
            }
        }
        if (user == null || user.trim().isEmpty()) {
            throw new IllegalArgumentException("vCenter user name is required");
        }
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("vCenter password is required");
        }
        return new VhmConfig(h, p, user.trim(), password, verifyTls);
    }

    /** For the Login request body only. */
    String password() {
        return password;
    }

    /** {@code https://host:port}, IPv6 literals bracketed. */
    public String baseUrl() {
        String h = host.indexOf(':') >= 0 && !host.startsWith("[") ? "[" + host + "]" : host;
        return "https://" + h + ":" + port;
    }

    public String redact(String text) {
        return redact(text, password);
    }

    static String redact(String text, String secret) {
        if (text == null || secret == null || secret.isEmpty()) {
            return text;
        }
        return text.replace(secret, "****").replace(Json.quote(secret).replaceAll("^\"|\"$", ""),
                "****");
    }

    @Override
    public String toString() {
        return "VhmConfig{host=" + host + ", port=" + port + ", user=" + user
                + ", password=****, verifyTls=" + verifyTls + "}";
    }
}
