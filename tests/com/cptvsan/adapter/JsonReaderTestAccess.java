package com.cptvsan.adapter;

import java.io.IOException;

/** Test helper: parse JSON, wrapping the checked exception. */
final class JsonReaderTestAccess {
    private JsonReaderTestAccess() {
    }

    static Object parse(String s) throws IOException {
        return JsonReader.parse(s);
    }
}
