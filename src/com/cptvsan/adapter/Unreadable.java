package com.cptvsan.adapter;

import java.io.IOException;

/**
 * An answer that arrived but cannot be used (not JSON, wrong shape, no
 * body). Deterministic, so the client never re-sends the call for it: only a
 * call with no answer at all is retried.
 */
final class Unreadable extends IOException {

    private static final long serialVersionUID = 1L;

    Unreadable(String message) {
        super(message);
    }
}
