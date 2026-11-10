package com.cptvsan.adapter;

import java.util.function.Function;

import com.integrien.alive.common.adapter3.TestParam;
import com.integrien.alive.common.adapter3.config.AdapterConfig;
import com.integrien.alive.common.adapter3.config.CredentialConfig;
import com.integrien.alive.common.adapter3.config.CredentialFieldConfig;
import com.integrien.alive.common.adapter3.config.ResourceConfig;
import com.integrien.alive.common.adapter3.config.ResourceIdentifierConfig;
import com.vcfcf.adapter.spi.VcfCfDiscoverer;

/**
 * The parts of the adapter that touch SDK configuration objects but need no
 * running adapter (an AdapterBase cannot be constructed outside the platform:
 * it starts log4j). Static, so tests/ SDK stubs can drive them. Never reads a
 * Suite API, never discovers an object.
 */
final class SdkGlue {

    private SdkGlue() {
    }

    /** The value of the first identifier named {@code key}, or null. */
    static String identifier(ResourceConfig rc, String key) {
        for (ResourceIdentifierConfig i : rc.getResourceIdentifiers()) {
            if (key.equals(i.getKey())) {
                return i.getValue();
            }
        }
        return null;
    }

    /** The value of the first credential field named {@code key}, or null. */
    static String credentialField(ResourceConfig rc, String key) {
        CredentialConfig cc = rc.getResourceCredential();
        if (cc == null) {
            return null;
        }
        for (CredentialFieldConfig f : cc.getCredenialFields()) {
            if (key.equals(f.getKey())) {
                return f.getValue();
            }
        }
        return null;
    }

    static VhmConfig config(ResourceConfig rc) {
        String verify = identifier(rc, VsanHostMetricsAdapter.ID_VERIFY);
        return VhmConfig.of(identifier(rc, VsanHostMetricsAdapter.ID_HOST),
                identifier(rc, VsanHostMetricsAdapter.ID_PORT),
                identifier(rc, VsanHostMetricsAdapter.ID_USER),
                credentialField(rc, VsanHostMetricsAdapter.CRED_PASSWORD), // REDACT-SECRET
                verify == null || !"false".equalsIgnoreCase(verify.trim()));
    }

    /**
     * Test Connection from the request alone (configure has not run): the config is
     * parsed from THIS request and the opener is built for THIS host.
     * @return the one-line success description
     */
    static String test(TestParam param, Function<VhmConfig, Net.Opener> openers) throws Exception {
        AdapterConfig ac = param == null ? null : param.getAdapterConfig();
        ResourceConfig rc = ac == null ? null : ac.getAdapterInstResource();
        if (rc == null) {
            throw new Exception("Test Connection: no adapter instance configuration on the request");
        }
        VhmConfig c = config(rc);
        return ConnectionCheck.run(c, openers.apply(c));
    }

    /** Nothing to discover: the adapter instance is the only object. Empty, no error text. */
    static VcfCfDiscoverer<VhmConfig> noDiscovery() {
        return (cfg, http, param, result) -> {
            // intentionally empty
        };
    }
}
