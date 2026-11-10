package com.cptvsan.adapter;

import java.io.IOException;
import java.util.List;

/**
 * Test Connection, SDK-free: log in, enforce the vCenter 8.0 U3+ / 9.0+ floor,
 * see clusters, and prove the vSAN Performance Service answers. Takes
 * everything it needs as arguments (the config of the request being tested
 * and a connection opener built for that config's host), never instance
 * state, because the test runs before the config is applied. Makes no Suite
 * API call.
 */
final class ConnectionCheck {

    private ConnectionCheck() {
    }

    /** @return a one-line success description; throws with a readable, redacted reason */
    static String run(VhmConfig c, Net.Opener opener) throws Exception {
        VcClient vc = new VcClient(c.baseUrl(), c.user, c.password(), opener, true, 60_000);
        try {
            try {
                vc.open();
                vc.checkVersion();
                List<Inventory.ClusterRef> clusters = Inventory.filterClusters(
                        vc.listClusters().clusterRefs(), c.clusterInclude, c.clusterExclude);
                if (clusters.isEmpty()) {
                    throw new IOException("Connected to vCenter but no clusters are visible. The"
                            + " account likely lacks Read-only propagated from the root.");
                }
                String problem = vc.probe(clusters.get(0).moid, "cluster-domclient",
                        System.currentTimeMillis());
                if (problem != null) {
                    throw new IOException("Connected to " + c.host + " and found "
                            + clusters.size() + " cluster(s), but the vSAN Performance Service did"
                            + " not answer: " + problem + ". Check that it is enabled on the"
                            + " cluster.");
                }
                return c.host + " (" + vc.about("version") + " build " + vc.about("build") + "), "
                        + clusters.size() + " cluster(s)";
            } catch (IOException e) {
                throw scrub(e, c);
            }
        } finally {
            vc.logout();
        }
    }

    /** Whether any message in {@code t}'s cause chain contains the password. */
    private static boolean leaks(Throwable t, VhmConfig c) {
        for (; t != null; t = t.getCause()) {
            String m = t.getMessage();
            if (m != null && !m.equals(c.redact(m))) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code e} with no secret in any message, KEEPING the cause chain: the framework
     * words a certificate-trust failure by walking the causes, so dropping them turns
     * "untrusted certificate" into a bare message. A throwable whose whole chain is
     * clean is reused as is; one that leaks becomes an IOException with the redacted
     * message and the (recursively scrubbed) cause.
     */
    static IOException scrub(Throwable e, VhmConfig c) {
        Throwable s = scrubChain(e, c);
        return s instanceof IOException ? (IOException) s : new IOException(String.valueOf(s.getMessage()), s);
    }

    private static Throwable scrubChain(Throwable t, VhmConfig c) {
        if (t == null || !leaks(t, c)) {
            return t;
        }
        return new IOException(c.redact(String.valueOf(t.getMessage())), scrubChain(t.getCause(), c));
    }
}
