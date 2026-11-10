package com.cptvsan.adapter;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Successful calls must leave the connection in the JDK keep-alive pool. */
public final class NetTest {

    public static void main(String[] args) throws Exception {
        Set<Integer> clientPorts = ConcurrentHashMap.newKeySet();
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", x -> {
            clientPorts.add(x.getRemoteAddress().getPort());
            byte[] b = x.getRequestURI().getPath().equals("/err") ? "bad".getBytes() : "{\"a\":1}".getBytes();
            x.sendResponseHeaders(x.getRequestURI().getPath().equals("/err") ? 500 : 200, b.length);
            x.getResponseBody().write(b);
            x.close();
        });
        s.start();
        try {
            String base = "http://127.0.0.1:" + s.getAddress().getPort();
            for (int i = 0; i < 4; i++) {
                Net.Resp r = Net.call(Net.PLAIN, "GET", base + "/ok", null, Net.headers(), false, 5000);
                Check.eq("{\"a\":1}", r.text(), "body read in full");
            }
            Net.Resp e = Net.call(Net.PLAIN, "POST", base + "/err", "{}", Net.headers(), false, 5000);
            Check.eq(500, e.status, "error status");
            Check.eq("bad", e.text(), "error body read in full");
            Net.call(Net.PLAIN, "GET", base + "/ok", null, Net.headers(), false, 5000);
            Check.eq(1, clientPorts.size(), "six calls, one TCP connection (keep-alive pooling): " + clientPorts);
        } finally {
            s.stop(0);
        }
        Check.done("NetTest");
    }
}
