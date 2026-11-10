package com.cptvsan.adapter;

import java.io.IOException;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;

import com.integrien.alive.common.adapter3.AdapterBase;
import com.integrien.alive.common.adapter3.MetricData;
import com.integrien.alive.common.adapter3.Relationships;
import com.integrien.alive.common.adapter3.ResourceStatus;
import com.integrien.alive.common.adapter3.config.ResourceConfig;
import com.integrien.alive.common.util.CommonConstants.ResourceStatusEnum;
import com.vcfcf.adapter.VcfCfAdapter;
import com.vcfcf.adapter.http.CertificateNames;
import com.vcfcf.adapter.http.ManagedHttpClient;
import com.vcfcf.adapter.spi.VcfCfCollector;
import com.vcfcf.adapter.spi.VcfCfDiscoverer;
import com.vcfcf.adapter.spi.VcfCfTester;
import com.vcfcf.adapter.stitch.AmbientCredential;

/**
 * vSAN Host Metrics 3.0.0: the Python 2.0.0 pack as a Java GENERAL adapter.
 * vSAN Performance Service metrics are read over the vSphere JSON API
 * (vCenter 8.0 Update 3 or later, or 9.0 or later) and pushed DIRECTLY onto
 * the VMWARE ClusterComputeResource, HostSystem or VirtualMachine each one
 * describes, under {@code cptvsan|<group>[:<instance>]|<metric>}.
 *
 * <h3>What this adapter never does</h3>
 * It defines no object kind of its own besides the adapter instance and
 * reports no object of any other kind: the only resource it ever hands to the
 * platform is the adapter instance, carrying {@code Collector|*} self-metrics.
 * It never adds a relationship, to any object. Relationship writes REPLACE a
 * parent's whole child list; the stats and properties endpoints carry none.
 * It does not use the framework's {@code SuiteApiStitcher}: values are written
 * by {@link Pusher} to the batch stats/properties endpoints only, with the
 * credential the platform injects for this adapter instance.
 *
 * <h3>Where the cycle runs</h3>
 * The platform does not list the adapter instance among the resources it
 * collects, and this pack has no other object, so the cycle runs in the
 * collector's {@code rediscover} hook, which the framework calls every cycle
 * with the adapter instance (the 9.x note that {@code onDiscover} is never
 * called does not matter: nothing is discovered). A failed cycle throws from
 * there and the framework shows the reason on the adapter instance.
 */
public final class VsanHostMetricsAdapter extends VcfCfAdapter<VhmConfig> {

    /** MUST match describe.xml and adapter.yaml. */
    static final String ADAPTER_KIND = "vsanhostmetrics";

    static final String ID_HOST = "vcenter_host";
    static final String ID_PORT = "vcenter_port";
    static final String ID_USER = "vcenter_user";
    static final String ID_VERIFY = "verify_certs";
    static final String CRED_PASSWORD = "vcenter_password";

    /** One configuration's state, published with one volatile write. */
    private static final class Runtime {
        final VhmConfig cfg;
        final Net.Opener vcOpener;
        final Collector.State state = new Collector.State();
        VcClient vc;
        SuiteApi suite;
        final Random rng = new Random();

        Runtime(VhmConfig cfg, Net.Opener vcOpener) {
            this.cfg = cfg;
            this.vcOpener = vcOpener;
        }
    }

    private volatile Runtime runtime;
    private volatile String configError;

    public VsanHostMetricsAdapter() {
        super(ADAPTER_KIND);
    }

    public VsanHostMetricsAdapter(String adapterDir, Integer adapterInstanceId) {
        super(ADAPTER_KIND, adapterDir, adapterInstanceId);
    }

    /**
     * This pack asserts no relationship, to any object. The framework
     * would attach one to the collect result; refuse it here, loudly.
     */
    @Override
    public void addRelationshipsToCurrentCycle(Relationships rels) {
        throw new IllegalStateException("vSAN Host Metrics never writes a relationship"
                + " (relationship writes replace a whole child list)");
    }

    // ------------------------------------------------------------------
    // Configure
    // ------------------------------------------------------------------

    /** The vCenter connection: platform trust and name check, or none when verification is off. */
    private Net.Opener vcOpener(VhmConfig cfg) {
        final SSLContext ctx;
        final HostnameVerifier hv;
        if (cfg.verifyTls) {
            ctx = getPlatformSslContext(cfg.host);
            hv = CertificateNames.strictHostnameVerifier();
        } else {
            logWarn("Verify vCenter certificate is off for " + cfg.host + ": the certificate and"
                    + " host name are NOT checked. Turn it on once the certificate is trusted.");
            ctx = insecureSslContext();
            hv = (h, s) -> true;
        }
        return url -> {
            HttpsURLConnection c = (HttpsURLConnection) new java.net.URL(url).openConnection();
            c.setSSLSocketFactory(ctx.getSocketFactory());
            c.setHostnameVerifier(hv);
            return c;
        };
    }

    @Override
    protected void configureAdapter(ResourceStatus status, ResourceConfig rc) {
        Runtime old = runtime;
        runtime = null;
        configError = null;
        try {
            VhmConfig cfg = SdkGlue.config(rc);
            config = cfg;
            runtime = new Runtime(cfg, vcOpener(cfg));
            logInfo("VsanHostMetricsAdapter configured: " + cfg);
        } catch (IllegalArgumentException e) {
            configError = e.getMessage();
            logError("configuration is invalid: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            configError = "the vCenter connection could not be built (" + e.getClass().getSimpleName()
                    + ")";
            logError(configError, e);
        } finally {
            if (old != null) {
                release(old);
            }
        }
    }

    private static void release(Runtime r) {
        VcClient vc = r.vc;
        if (vc != null) {
            vc.logout();
        }
    }

    @Override
    protected List<String> certificateCheckUrls(ResourceConfig rc) {
        String host = getIdentifier(rc, ID_HOST);
        String verify = getIdentifier(rc, ID_VERIFY);
        if (host == null || host.trim().isEmpty() || "false".equalsIgnoreCase(
                verify == null ? "" : verify.trim())) {
            return Collections.emptyList();
        }
        String port = getIdentifier(rc, ID_PORT);
        return Collections.singletonList("https://" + host.trim() + ":"
                + (port == null || port.trim().isEmpty() ? "443" : port.trim()));
    }

    @Override
    public void onDiscard() {
        Runtime r = runtime;
        runtime = null;
        if (r != null) {
            release(r);
        }
        super.onDiscard();
    }

    // ------------------------------------------------------------------
    // Test Connection (self-contained: configure has not run)
    // ------------------------------------------------------------------

    @Override
    protected VcfCfTester<VhmConfig> getTester() {
        // Built from THIS request's config, not from instance fields: Validate
        // Connection (and certificate accept) run before configureAdapter has
        // applied these values, and the TLS context must be for THIS host.
        // No Suite API call: on a Cloud Proxy the per-instance credential is
        // null in this context. Collection still fails loudly if it cannot
        // authenticate.
        return (cfg, http, param) -> logInfo("Test OK: " + SdkGlue.test(param, this::vcOpener));
    }

    // ------------------------------------------------------------------
    // Collection
    // ------------------------------------------------------------------

    /**
     * The platform may call onDiscover (8.18 does). This pack discovers nothing, so
     * the answer is an empty, successful result with no error text: the framework's
     * default discoverer would report that discovery is not supported.
     */
    @Override
    protected VcfCfDiscoverer<VhmConfig> getDiscoverer() {
        return SdkGlue.noDiscovery();
    }

    @Override
    protected VcfCfCollector<VhmConfig> getCollector() {
        return new VcfCfCollector<VhmConfig>() {
            @Override
            public boolean needsRediscovery(VhmConfig cfg) {
                return true; // the hook the cycle runs in; see the class comment
            }

            @Override
            public void rediscover(VhmConfig cfg, ManagedHttpClient http, ResourceConfig inst,
                    AdapterBase adapter) throws Exception {
                runCycle(inst);
            }

            @Override
            public void collect(VhmConfig cfg, ManagedHttpClient http, ResourceConfig rc,
                    List<MetricData> out, AdapterBase adapter) {
                // Nothing to collect: no object of this pack's own besides the instance.
            }

            @Override
            public ResourceStatusEnum mapCollectException(Exception e) {
                for (Throwable t = e; t != null; t = t.getCause()) {
                    if (t instanceof ConnectException || t instanceof UnknownHostException
                            || t instanceof NoRouteToHostException
                            || t instanceof SocketTimeoutException) {
                        return ResourceStatusEnum.RESOURCE_STATUS_DOWN;
                    }
                }
                return null;
            }
        };
    }

    private Log pakLog() {
        return new Log() {
            @Override
            public void info(String m) {
                logInfo(m);
            }

            @Override
            public void warn(String m) {
                logWarn(m);
            }

            @Override
            public void error(String m) {
                logError(m, null);
            }
        };
    }

    private Net.Opener suiteOpener() {
        return url -> (HttpURLConnection) openPlatformConnection(url);
    }

    private ResourceConfig instanceResource(ResourceConfig given) {
        if (given != null) {
            return given;
        }
        try {
            return getAdapterInstResource();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void runCycle(ResourceConfig givenInstance) throws Exception {
        if (isAbortRequested()) {
            return;
        }
        Runtime r = runtime;
        if (r == null) {
            throw new IllegalStateException(configError == null
                    ? "the adapter instance is not configured"
                    : "the adapter instance configuration is invalid: " + configError);
        }
        try {
            runCycle(r, instanceResource(givenInstance));
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            throw redacted(e, r.cfg);
        }
    }

    private void runCycle(Runtime r, ResourceConfig inst) throws Exception {
        Log log = pakLog();
        if (r.suite == null) {
            AmbientCredential cred = AmbientCredential.load(getAdapterConfig());
            r.suite = new SuiteApi("https://localhost/suite-api", cred.getUsername(),
                    cred.getPassword(), suiteOpener(), 120_000);
        }
        if (r.vc == null) {
            r.vc = new VcClient(r.cfg.baseUrl(), r.cfg.user, r.cfg.password(), r.vcOpener,
                    r.cfg.gzip, r.cfg.httpTimeoutMs);
        }
        if (r.vc.instanceUuid().isEmpty()) {
            try {
                r.vc.open();
            } catch (VsanFault f) {
                throw new IOException("vCenter login failed for " + r.cfg.user + "@" + r.cfg.host
                        + ": " + f.getMessage(), f);
            }
        }
        long now = System.currentTimeMillis();
        Collector.Report rep = new Collector(r.vc, r.suite, r.cfg, r.state, log).run(now, null,
                r.rng);
        log.info(rep.summary());
        for (int i = 0; i < Math.min(5, rep.problems.size()); i++) {
            log.warn("collection problem: " + rep.problems.get(i));
        }
        // Self-metrics on OUR adapter instance: the only object this pack reports.
        if (inst != null) {
            for (Map.Entry<String, Double> m : rep.metrics().entrySet()) {
                pushMetric(inst, m.getKey(), m.getValue(), now);
            }
        }
        String failure = rep.failure();
        if (failure != null) {
            throw new IOException(failure);
        }
        if (rep.push.targets > 0 && rep.push.resolved == 0) {
            throw new IOException("Collected " + rep.plan.entities + " vSAN entities but none of"
                    + " the " + rep.push.targets + " VMware objects they belong to was found in"
                    + " Operations. Check that the VMware vSphere adapter collects the same"
                    + " vCenter.");
        }
        if (rep.push.targets > 0 && rep.push.ok == 0) {
            throw new IOException("Pushed to none of " + rep.push.resolved + " VMware objects ("
                    + rep.push.failed + " rejected, " + rep.push.skipped + " not sent).");
        }
    }

    /** {@code e}, or a copy without its cause when its message contained the password. */
    static Exception redacted(Exception e, VhmConfig cfg) {
        boolean leaks = false;
        for (Throwable t = e; t != null && !leaks; t = t.getCause()) {
            String m = t.getMessage();
            leaks = m != null && !m.equals(cfg.redact(m));
        }
        return leaks ? ConnectionCheck.scrub(e, cfg) : e;
    }
}
