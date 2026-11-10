package com.cptvsan.adapter;

/** Configuration validation and password redaction. */
public final class ConfigTest {

    public static void main(String[] args) {
        VhmConfig c = VhmConfig.of(" vc01.lab ", "", "u@vsphere.local", "s3cr3t!", true);
        Check.eq("vc01.lab", c.host, "host trimmed");
        Check.eq(443, c.port, "default port");
        Check.eq("https://vc01.lab:443", c.baseUrl(), "base url");
        Check.eq(8443, VhmConfig.of("vc01.lab:8443", "", "u", "p", true).port, "host:port accepted");
        Check.eq(8443, VhmConfig.of("vc01.lab", "8443", "u", "p", true).port, "explicit port");
        Check.eq("https://[fe80::1]:443", VhmConfig.of("fe80::1", "", "u", "p", true).baseUrl(),
                "IPv6 literal bracketed");
        Check.fails(IllegalArgumentException.class, () -> VhmConfig.of("", "", "u", "p", true), "host");
        Check.fails(IllegalArgumentException.class,
                () -> VhmConfig.of("https://vc", "", "u", "p", true), "scheme refused");
        Check.fails(IllegalArgumentException.class, () -> VhmConfig.of("vc", "x", "u", "p", true), "port");
        Check.fails(IllegalArgumentException.class, () -> VhmConfig.of("vc", "70000", "u", "p", true),
                "port range");
        Check.fails(IllegalArgumentException.class, () -> VhmConfig.of("vc", "", "", "p", true), "user");
        Check.fails(IllegalArgumentException.class, () -> VhmConfig.of("vc", "", "u", "", true), "password");
        Check.yes(!c.toString().contains("s3cr3t"), "toString omits the password");
        Check.eq("login failed ****", c.redact("login failed s3cr3t!"), "redacted");
        Check.eq(null, c.redact(null), "null safe");
        Check.done("ConfigTest");
    }
}
