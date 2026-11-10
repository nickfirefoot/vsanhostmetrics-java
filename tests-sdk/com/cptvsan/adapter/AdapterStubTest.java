package com.cptvsan.adapter;

import com.integrien.alive.common.adapter3.DiscoveryResult;
import com.integrien.alive.common.adapter3.TestParam;
import com.integrien.alive.common.adapter3.config.AdapterConfig;
import com.integrien.alive.common.adapter3.config.CredentialConfig;
import com.integrien.alive.common.adapter3.config.CredentialFieldConfig;
import com.integrien.alive.common.adapter3.config.ResourceConfig;
import com.integrien.alive.common.adapter3.config.ResourceIdentifierConfig;

/**
 * Needs the SDK and framework jars (ci/run_sdk_tests.sh). Drives the
 * adapter's Test Connection and discoverer logic (SdkGlue) with the SDK's own
 * config classes. The adapter class itself cannot be constructed here:
 * AdapterBase starts log4j, which is not on this machine. Nothing here touches a network beyond a refused local connection.
 */
public final class AdapterStubTest {

    @SuppressWarnings("deprecation")
    private static ResourceConfig rc(String host, String port, String pass) {
        ResourceConfig r = new ResourceConfig();
        r.addResourceIdentifier(new ResourceIdentifierConfig("vcenter_host", host));
        r.addResourceIdentifier(new ResourceIdentifierConfig("vcenter_port", port));
        r.addResourceIdentifier(new ResourceIdentifierConfig("vcenter_user", "svc"));
        r.addResourceIdentifier(new ResourceIdentifierConfig("verify_certs", "false"));
        CredentialConfig cc = new CredentialConfig();
        cc.addCredentialField(new CredentialFieldConfig("vcenter_password", pass, false));
        r.setResourceCredential(cc);
        return r;
    }

    private static TestParam param(ResourceConfig r) {
        AdapterConfig ac = new AdapterConfig(VsanHostMetricsAdapter.ADAPTER_KIND, 1);
        if (r != null) {
            ac.setAdapterInstResource(r);
        }
        return new TestParam(ac);
    }

    public static void main(String[] args) throws Exception {
        java.util.function.Function<VhmConfig, Net.Opener> openers = c -> Net.PLAIN;

        // the request carries no configuration
        Exception e = Check.fails(Exception.class, () -> SdkGlue.test(param(null), openers), "no config");
        Check.yes(e.getMessage().contains("no adapter instance configuration"), e.getMessage());
        Check.fails(Exception.class, () -> SdkGlue.test(null, openers), "no request");

        // identifiers and credential are read from the SDK objects
        VhmConfig c = SdkGlue.config(rc("vc.example.org", "8443", "s3cr3t"));
        Check.eq("vc.example.org", c.host, "host");
        Check.eq("svc", c.user, "user");
        Check.eq("s3cr3t", c.password(), "password from the credential");
        Check.yes(!c.verifyTls, "verify_certs=false");
        Check.fails(IllegalArgumentException.class,
                () -> SdkGlue.test(param(rc("vc.example.org", "notaport", "s3cr3t")), openers), "bad port");

        // a dead vCenter: readable failure, no password anywhere in the chain
        Exception dead = Check.fails(Exception.class,
                () -> SdkGlue.test(param(rc("127.0.0.1", "1", "s3cr3t")),
                        cfg -> url -> Net.PLAIN.open(url.replace("https://", "http://"))), "dead vCenter");
        for (Throwable t = dead; t != null; t = t.getCause()) {
            Check.yes(t.getMessage() == null || !t.getMessage().contains("s3cr3t"),
                    "no password in " + t.getClass().getSimpleName());
        }

        // discoverer: returns normally, leaves the real DiscoveryResult empty with no error text
        DiscoveryResult res = new DiscoveryResult(VsanHostMetricsAdapter.ADAPTER_KIND, 1);
        SdkGlue.noDiscovery().discover(null, null, null, res);
        Check.yes(res.isEmpty(), "no resource discovered");
        Check.yes(res.getErrorMsg() == null || res.getErrorMsg().isEmpty(), "no error text");
        Check.done("AdapterStubTest");
    }
}
