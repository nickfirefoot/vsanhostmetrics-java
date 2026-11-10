package com.cptvsan.adapter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Session-object ids carry brackets, {@code session[<uuid>]<uuid>}, and
 * vCenter does not decode %5B / %5D: they must reach the wire RAW. A raw
 * socket server records the request line byte for byte (the JDK's own HTTP
 * server rejects brackets in a path, which is itself the hazard).
 */
public final class BracketIdTest {

    public static void main(String[] args) throws Exception {
        Check.eq("session[abc]pc1", VcClient.moSeg("session[abc]pc1"), "brackets stay raw");
        Check.eq("a%20b%2Fc", VcClient.moSeg("a b/c"), "other characters are percent-encoded");
        Check.eq("domain-c9", VcClient.moSeg("domain-c9"), "plain ids unchanged");
        Check.yes(!VcClient.moSeg("session[x]y").contains("%5B"), "never %5B");

        final List<String> requestLines = new ArrayList<>();
        try (ServerSocket ss = new ServerSocket(0, 5, java.net.InetAddress.getLoopbackAddress())) {
            Thread server = new Thread(() -> {
                try {
                    while (true) {
                        try (Socket s = ss.accept()) {
                            BufferedReader r = new BufferedReader(new InputStreamReader(
                                    s.getInputStream(), StandardCharsets.ISO_8859_1));
                            String line = r.readLine();
                            int len = 0;
                            for (String h = r.readLine(); h != null && !h.isEmpty();
                                    h = r.readLine()) {
                                if (h.toLowerCase().startsWith("content-length:")) {
                                    len = Integer.parseInt(h.substring(15).trim());
                                }
                            }
                            char[] body = new char[len];
                            int got = 0;
                            while (got < len) {
                                int n = r.read(body, got, len - got);
                                if (n < 0) {
                                    break;
                                }
                                got += n;
                            }
                            synchronized (requestLines) {
                                requestLines.add(line);
                            }
                            s.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: "
                                    + "application/json\r\nContent-Length: 2\r\nConnection: close"
                                    + "\r\n\r\n{}").getBytes(StandardCharsets.ISO_8859_1));
                        }
                    }
                } catch (IOException e) {
                    // closed
                }
            });
            server.setDaemon(true);
            server.start();
            VcClient vc = new VcClient("http://127.0.0.1:" + ss.getLocalPort(), "u", "p", Net.PLAIN,
                    true, 10_000);
            vc.pcWait("session[abc]pc1", "", 1000);
            vc.pcDestroy("session[abc]pc1");
            synchronized (requestLines) {
                Check.eq(2, requestLines.size(), "two requests seen");
                for (String l : requestLines) {
                    Check.yes(l.contains("session[abc]pc1"), "RAW brackets on the wire: " + l);
                    Check.yes(!l.contains("%5B") && !l.contains("%5D"), "no %5B/%5D on the wire: " + l);
                }
                Check.yes(requestLines.get(0).contains("/WaitForUpdatesEx"), "WaitForUpdatesEx path");
                Check.yes(requestLines.get(1).contains("/DestroyPropertyCollector"), "Destroy path");
            }
        }
        Check.done("BracketIdTest");
    }
}
