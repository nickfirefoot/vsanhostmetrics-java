package com.cptvsan.adapter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The VCF Operations Suite API with the credential the platform injects for
 * this adapter instance (the only one that works from a Cloud Proxy). A token
 * is acquired lazily, reused until its validity (minus a margin) and renewed
 * once on HTTP 401. The Python pack's {@code push.SuiteSession}. Never logs a
 * credential or token.
 */
final class SuiteApi {

    static final long TOKEN_MARGIN_MS = 10 * 60_000L;

    private final String base;
    private final String user;
    private final String password;
    private final Net.Opener opener;
    private final int timeoutMs;
    private volatile String token = "";
    private volatile long validity;
    private final Object lock = new Object();
    final AtomicInteger calls = new AtomicInteger();

    SuiteApi(String base, String user, String password, Net.Opener opener, int timeoutMs) {
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.user = user;
        this.password = password;
        this.opener = opener;
        this.timeoutMs = timeoutMs;
    }

    private String url(String path) {
        String p = path.startsWith("/") ? path.substring(1) : path;
        if (p.startsWith("suite-api/")) {
            p = p.substring("suite-api/".length());
        }
        return base + "/" + p;
    }

    private String acquire(String stale) throws IOException {
        synchronized (lock) {
            if (!token.isEmpty() && !token.equals(stale)) {
                return token; // another thread renewed it
            }
            calls.incrementAndGet();
            Net.Resp r = Net.call(opener, "POST", url("api/auth/token/acquire"),
                    "{\"username\":" + Json.quote(user) + ",\"password\":" + Json.quote(password)
                    + ",\"authSource\":\"LOCAL\"}", Collections.<String[]>emptyList(), true,
                    timeoutMs);
            if (!r.ok()) {
                throw new IOException("Suite API token acquire: HTTP " + r.status);
            }
            Object body = JsonReader.parse(r.text());
            String t = JsonReader.str(body, "token");
            if (t == null || t.isEmpty()) {
                throw new IOException("Suite API token acquire: no token in the response");
            }
            String v = JsonReader.str(body, "validity");
            long vv = 0;
            try {
                vv = v == null ? 0 : Long.parseLong(v);
            } catch (NumberFormatException e) {
                // the default applies
            }
            token = t;
            validity = vv != 0 ? vv : System.currentTimeMillis() + 3_600_000L;
            return t;
        }
    }

    /** Whether the cached token has run out (or never existed). */
    private boolean stale() {
        return token.isEmpty() || validity - TOKEN_MARGIN_MS <= System.currentTimeMillis();
    }

    private Net.Resp request(String method, String path, String body) throws IOException {
        String tok = stale() ? acquire(token) : token;
        List<String[]> h;
        Net.Resp r = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            h = Net.headers("Authorization", "vRealizeOpsToken " + tok);
            calls.incrementAndGet();
            r = Net.call(opener, method, url(path), body, h, false, timeoutMs);
            if (r.status != 401 || attempt == 1) {
                return r;
            }
            tok = acquire(tok);
        }
        return r;
    }

    private static final java.util.regex.Pattern READ = java.util.regex.Pattern.compile(
            "^api/resources(\\?[^/]*|/[^/?]+)$");
    private static final java.util.regex.Pattern WRITE = java.util.regex.Pattern.compile(
            "^api/resources/(stats|properties|[^/?]+/(stats|properties))$");

    static String bare(String path) {
        String p = path.startsWith("/") ? path.substring(1) : path;
        return p.startsWith("suite-api/") ? p.substring("suite-api/".length()) : p;
    }

    /** GET of a resource listing or one resource. Nothing else can be read through this class. */
    Net.Resp get(String path) throws IOException {
        if (!READ.matcher(bare(path)).matches()) {
            throw new IllegalStateException("refusing GET " + path);
        }
        return request("GET", path, null);
    }

    /**
     * POST of stats or properties, batch or per resource. Nothing else can be
     * written through this class: no relationship endpoint, no adapter-kind
     * variant, no resource creation (relationship writes replace an object's whole child list).
     */
    Net.Resp post(String path, String body) throws IOException {
        if (!WRITE.matcher(bare(path)).matches()) {
            throw new IllegalStateException("refusing POST " + path + ": only stats and"
                    + " properties may be written");
        }
        return request("POST", path, body);
    }
}
