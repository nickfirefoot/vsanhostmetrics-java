package com.cptvsan.adapter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A vSphere JSON API fault: an error status with a JSON body carrying
 * {@code _typeName} (the fault class), {@code faultstring} and, for vSAN
 * validation faults, {@code faultMessage[]} of {@code {key, message}}.
 *
 * <p>Faults are deterministic: the client never re-sends a call that
 * answered with one, except {@code NotAuthenticated} (log in again once).
 * The message carries the fault class, the first fault key and the server's
 * faultstring, never a request body or header.
 */
final class VsanFault extends IOException {

    private static final long serialVersionUID = 1L;

    static final String NOT_AUTHENTICATED = "NotAuthenticated";
    static final String INVALID_REQUEST = "InvalidRequest";
    static final String INVALID_ARGUMENT = "InvalidArgument";
    static final String NOT_SUPPORTED = "NotSupported";
    static final String MANAGED_OBJECT_NOT_FOUND = "ManagedObjectNotFound";
    static final String INVALID_LOGIN = "InvalidLogin";

    final String faultClass;
    final String faultString;
    @SuppressWarnings("serial")
    final List<String> keys;
    final int httpStatus;

    /**
     * A ContainerView moId, {@code session[<uuid>]<uuid>}: it embeds the vpxd
     * session key and must never reach a log, a property or a message.
     */
    private static final java.util.regex.Pattern VIEW_ID =
            java.util.regex.Pattern.compile("session\\[[^\\]]*\\][^\\s\"']*");

    /** {@code text} with any ContainerView moId replaced by {@code session[...]}. */
    static String scrub(String text) {
        return text == null ? null : VIEW_ID.matcher(text).replaceAll("session[...]");
    }

    VsanFault(String faultClass, String faultString, List<String> keys, int httpStatus) {
        super(describe(faultClass, scrub(faultString), keys, httpStatus));
        this.faultClass = faultClass;
        this.faultString = faultString == null ? "" : scrub(faultString);
        this.keys = Collections.unmodifiableList(new ArrayList<>(keys));
        this.httpStatus = httpStatus;
    }

    /**
     * The fault in an error body, or {@code null} when the body is not a
     * JSON object with a {@code _typeName} (then only the status is known).
     */
    static VsanFault parse(String body, int httpStatus) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        Object root;
        try {
            root = JsonReader.parse(body);
        } catch (IOException | RuntimeException e) {
            return null;
        }
        if (!(root instanceof Map)) {
            return null;
        }
        String cls = JsonReader.str(root, "_typeName");
        if (cls == null || cls.isEmpty()) {
            return null;
        }
        List<String> keys = new ArrayList<>();
        StringBuilder msgs = new StringBuilder();
        for (Object m : JsonReader.list(root, "faultMessage")) {
            String k = JsonReader.str(m, "key");
            if (k != null && !k.isEmpty()) {
                keys.add(k);
            }
            String t = JsonReader.str(m, "message");
            if (t != null && !t.isEmpty()) {
                msgs.append(' ').append(t);
            }
        }
        String fs = JsonReader.str(root, "faultstring");
        return new VsanFault(cls, ((fs == null ? "" : fs) + msgs).trim(), keys, httpStatus);
    }

    boolean is(String cls) {
        return cls.equals(faultClass);
    }

    /** The first fault key, or {@code null}. */
    String firstKey() {
        return keys.isEmpty() ? null : keys.get(0);
    }

    /** One of the query batching faults (wildcardlimit and its siblings): a defect here. */
    boolean isBatchingFault() {
        for (String k : keys) {
            if (k.endsWith(".wildcardlimit") || k.endsWith(".querySpecsSizeLimitOnlyRegularQuery")
                    || k.endsWith(".queryspectypelimit")) {
                return true;
            }
        }
        return false;
    }

    /**
     * The cluster does not offer this entity type: {@code NotSupported}, or
     * {@code InvalidArgument} that is not a batching fault. The Python pack
     * treats both as "not here" (perfsvc.collect), and so does this one.
     */
    boolean isTypeUnavailable() {
        return is(NOT_SUPPORTED) || (is(INVALID_ARGUMENT) && !isBatchingFault());
    }

    /**
     * The entity type does not exist on this cluster (feature absent), as the
     * Python pack's {@code PerfFault.unsupported}: NotSupported, or an
     * InvalidArgument that names the entityRefId; never a batching fault.
     */
    boolean isUnsupported() {
        if (isBatchingFault()) {
            return false;
        }
        if (is(NOT_SUPPORTED)) {
            return true;
        }
        if (!is(INVALID_ARGUMENT)) {
            return false;
        }
        String all = (faultString + " " + String.join(" ", keys)).toLowerCase(java.util.Locale.ROOT);
        return all.contains("entityrefid");
    }

    private static String describe(String cls, String fs, List<String> keys, int status) {
        StringBuilder b = new StringBuilder("vSphere fault ").append(cls);
        if (!keys.isEmpty()) {
            b.append(" [").append(keys.get(0)).append(']');
        }
        if (fs != null && !fs.trim().isEmpty()) {
            b.append(": ").append(shorten(fs.trim()));
        }
        return b.append(" (HTTP ").append(status).append(')').toString();
    }

    private static String shorten(String s) {
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }
}
