package com.cptvsan.adapter;

import java.util.Random;

/**
 * Drives the Java collector for N cycles against a server that speaks the
 * vSphere JSON API and the Suite API (tools/e2e_compare.py starts the Python
 * pack's tools/fake_vsphere.py). Not a unit test.
 *
 * <pre>java com.cptvsan.adapter.E2eMain &lt;port&gt; &lt;cycles&gt; &lt;nowMs&gt;</pre>
 */
public final class E2eMain {

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        int cycles = Integer.parseInt(args[1]);
        long now = Long.parseLong(args[2]);
        VhmConfig cfg = VhmConfig.of("127.0.0.1", String.valueOf(port), "u@vsphere.local", "p", true);
        cfg.staggerS = 0;
        VcClient vc = new VcClient("http://127.0.0.1:" + port, "u@vsphere.local", "p", Net.PLAIN,
                true, 60_000);
        vc.open();
        SuiteApi suite = new SuiteApi("http://127.0.0.1:" + port + "/suite-api", "admin", "p",
                Net.PLAIN, 60_000);
        Collector.State state = new Collector.State();
        Log log = new Log() {
            @Override
            public void info(String m) {
                System.err.println("INFO " + m);
            }

            @Override
            public void warn(String m) {
                System.err.println("WARN " + m);
            }

            @Override
            public void error(String m) {
                System.err.println("ERROR " + m);
            }
        };
        for (int c = 0; c < cycles; c++) {
            Collector.Report r = new Collector(vc, suite, cfg, state, log).run(now + c * 300_000L,
                    c, new Random(c));
            System.out.println(r.summary());
        }
        System.exit(0);
    }
}
