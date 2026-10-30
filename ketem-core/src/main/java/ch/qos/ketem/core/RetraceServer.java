package ch.qos.ketem.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Minimal HTTP service: {@code POST /retrace} with an obfuscated stack trace as
 * the request body returns the de-obfuscated text. {@code GET /} serves a
 * small form for pasting traces in a browser.
 */
public final class RetraceServer {

    static final int MAX_BODY_BYTES = 4 * 1024 * 1024;

    private static final String FORM = "<!doctype html><html><head><meta charset=\"utf-8\"><title>Ketem retrace</title>"
            + "<style>body{font-family:sans-serif;margin:1rem}textarea{width:100%;height:40vh;font-family:monospace}"
            + "pre{background:#f4f4f4;padding:.5rem;white-space:pre-wrap}</style></head><body>"
            + "<h1>Ketem retrace</h1><textarea id=\"in\" placeholder=\"Paste an obfuscated stack trace\"></textarea>"
            + "<p><button onclick=\"go()\">Retrace</button></p><pre id=\"out\"></pre><script>"
            + "async function go(){const r=await fetch('retrace',{method:'POST',body:document.getElementById('in').value});"
            + "document.getElementById('out').textContent=await r.text();}</script></body></html>";

    private final HttpServer server;

    public RetraceServer(StackTraceDeobfuscator deobfuscator, String host, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/retrace", ex -> handleRetrace(ex, deobfuscator));
        server.createContext("/", ex -> {
            if (!"/".equals(ex.getRequestURI().getPath())) {
                respond(ex, 404, "text/plain", "Not found\n");
            } else {
                respond(ex, 200, "text/html", FORM);
            }
        });
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
    }

    public int getPort() {
        return server.getAddress().getPort();
    }

    private static void handleRetrace(HttpExchange ex, StackTraceDeobfuscator deobfuscator) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            ex.getResponseHeaders().set("Allow", "POST");
            respond(ex, 405, "text/plain", "Use POST\n");
            return;
        }
        byte[] body = readLimited(ex.getRequestBody());
        if (body == null) {
            respond(ex, 413, "text/plain", "Request body too large\n");
            return;
        }
        respond(ex, 200, "text/plain", deobfuscator.deobfuscate(new String(body, StandardCharsets.UTF_8)));
    }

    private static byte[] readLimited(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            if (out.size() + n > MAX_BODY_BYTES) {
                return null;
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static void respond(HttpExchange ex, int status, String type, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
