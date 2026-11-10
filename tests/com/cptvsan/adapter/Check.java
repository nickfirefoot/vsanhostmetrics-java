package com.cptvsan.adapter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Minimal assertions for the plain-main tests. Not itself a test. */
final class Check {

    interface Body {
        void run() throws Exception;
    }

    private static int passed;

    private Check() {
    }

    static void eq(Object expected, Object actual, String what) {
        boolean same = expected == null ? actual == null : expected.equals(actual);
        if (!same) {
            throw new AssertionError(what + ": expected <" + expected + "> but was <" + actual + ">");
        }
        passed++;
    }

    static void yes(boolean cond, String what) {
        if (!cond) {
            throw new AssertionError(what);
        }
        passed++;
    }

    /** {@code body} must throw an exception of {@code type}; returns it. */
    static <T extends Throwable> T fails(Class<T> type, Body body, String what) {
        try {
            body.run();
        } catch (Throwable t) {
            if (type.isInstance(t)) {
                passed++;
                return type.cast(t);
            }
            throw new AssertionError(what + ": expected " + type.getSimpleName()
                    + " but got " + t, t);
        }
        throw new AssertionError(what + ": expected " + type.getSimpleName() + ", nothing thrown");
    }

    static byte[] fixture(String name) throws IOException {
        return Files.readAllBytes(Paths.get("tests", "fixtures", name));
    }

    static void done(String test) {
        System.out.println(test + ": " + passed + " checks passed");
    }
}
