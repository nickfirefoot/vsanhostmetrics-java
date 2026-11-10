package com.cptvsan.adapter;

import java.util.Random;

/** Wires the SDK-free collector to a {@link FakeOps}. Not a test. */
final class Harness {

    final FakeOps fake;
    final VcClient vc;
    final SuiteApi suite;
    final VhmConfig cfg;
    final Collector.State state = new Collector.State();
    final Random rng = new Random(1);
    final java.util.List<String> logs = new java.util.concurrent.CopyOnWriteArrayList<>();
    long now = (System.currentTimeMillis() / 300_000) * 300_000 + 100_000;

    final Log log = new Log() {
        @Override
        public void info(String m) {
            logs.add("INFO " + m);
        }

        @Override
        public void warn(String m) {
            logs.add("WARN " + m);
        }

        @Override
        public void error(String m) {
            logs.add("ERROR " + m);
        }
    };

    Harness(FakeOps fake) throws Exception {
        this.fake = fake;
        cfg = VhmConfig.of("127.0.0.1", String.valueOf(fake.port), "u", "p", true);
        cfg.staggerS = 0;
        cfg.retryPauseMs = 0;
        vc = new VcClient(fake.vcBase(), "u", "p", Net.PLAIN, true, 20_000);
        suite = new SuiteApi(fake.suiteBase(), "ops", "pw", Net.PLAIN, 20_000);
        vc.open();
    }

    Collector.Report cycle() throws Exception {
        Collector.Report r = new Collector(vc, suite, cfg, state, log).run(now, null, rng);
        now += 300_000;
        return r;
    }
}
