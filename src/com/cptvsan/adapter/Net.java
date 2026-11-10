package com.cptvsan.adapter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Plain HTTP over {@link HttpURLConnection}. The URL is used verbatim: vCenter
 * does not percent-decode {@code [} and {@code ]} in a path, and the ids of
 * session objects contain them, so a URI-based client (which rejects or
 * encodes them) cannot be used. TLS is the opener's business: the adapter hands
 * in connections already configured with the platform trust, tests hand in
 * plain ones.
 */
final class Net {

    private Net() {
    }

    /** Opens a connection to {@code url}; configures TLS. Never sends anything. */
    interface Opener {
        HttpURLConnection open(String url) throws IOException;
    }

    /** The default opener: no TLS configuration (plain http in tests). */
    static final Opener PLAIN = url -> (HttpURLConnection) new URL(url).openConnection();

    /** A complete response. */
    static final class Resp {
        final int status;
        final byte[] body;
        final Map<String, List<String>> headers;
        /** Bytes on the wire (before decompression). */
        final int wire;

        Resp(int status, byte[] body, Map<String, List<String>> headers, int wire) {
            this.status = status;
            this.body = body;
            this.headers = headers;
            this.wire = wire;
        }

        String header(String name) {
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)
                        && !e.getValue().isEmpty()) {
                    return e.getValue().get(0);
                }
            }
            return null;
        }

        String text() {
            return new String(body, java.nio.charset.StandardCharsets.UTF_8);
        }

        boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    /** Most bytes read from one response. */
    static final int MAX_BODY = 1_500_000_000;

    static Resp call(Opener opener, String method, String url, String body,
            List<String[]> headers, boolean gzip, int timeoutMs) throws IOException {
        HttpURLConnection c = opener.open(url);
        boolean done = false;
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(Math.min(timeoutMs, 30_000));
            c.setReadTimeout(timeoutMs);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("Accept-Encoding", gzip ? "gzip" : "identity");
            for (String[] h : headers) {
                c.setRequestProperty(h[0], h[1]);
            }
            if (body != null) {
                byte[] b = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(b.length);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream o = c.getOutputStream()) {
                    o.write(b);
                }
            }
            int status = c.getResponseCode();
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            byte[] raw = in == null ? new byte[0] : readAll(in);
            String enc = c.getContentEncoding();
            byte[] data = raw;
            if (raw.length > 0 && enc != null) {
                if (enc.equalsIgnoreCase("gzip")) {
                    data = readAll(new GZIPInputStream(new java.io.ByteArrayInputStream(raw)));
                } else if (enc.equalsIgnoreCase("deflate")) {
                    data = readAll(new InflaterInputStream(new java.io.ByteArrayInputStream(raw)));
                }
            }
            Resp resp = new Resp(status, data, new java.util.LinkedHashMap<>(c.getHeaderFields()),
                    raw.length);
            done = true;
            return resp;
        } finally {
            // After a response read to its end and closed, the connection goes back to the
            // JDK's keep-alive pool; disconnect() would close the socket instead. Only a
            // failed exchange (a half-read stream, a timeout) is torn down.
            if (!done) {
                c.disconnect();
            }
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream i = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
            byte[] buf = new byte[16384];
            int n;
            while ((n = i.read(buf)) != -1) {
                out.write(buf, 0, n);
                if (out.size() > MAX_BODY) {
                    throw new IOException("response larger than " + MAX_BODY + " bytes");
                }
            }
            return out.toByteArray();
        }
    }

    static List<String[]> headers(String... kv) {
        List<String[]> l = new ArrayList<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            l.add(new String[] {kv[i], kv[i + 1]});
        }
        return l;
    }
}
