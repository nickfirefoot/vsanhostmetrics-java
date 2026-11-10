package com.cptvsan.adapter;

/**
 * The logging the SDK-free classes need. The adapter adapts its platform
 * logger; tests pass a recorder. Never pass a credential, session id or
 * token to any method.
 */
interface Log {
    void info(String m);

    void warn(String m);

    void error(String m);

    /** Drops everything. */
    Log NONE = new Log() {
        @Override
        public void info(String m) {
        }

        @Override
        public void warn(String m) {
        }

        @Override
        public void error(String m) {
        }
    };
}
