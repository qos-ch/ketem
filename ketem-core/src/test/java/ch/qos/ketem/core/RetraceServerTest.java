package ch.qos.ketem.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;

import org.junit.jupiter.api.Test;

class RetraceServerTest {

    @Test
    void postRetrace() throws Exception {
        IdentifierHasher h = new IdentifierHasher("s3cret");
        RetraceServer server = new RetraceServer(new StackTraceDeobfuscator(h, Set.of("compute")), "127.0.0.1", 0);
        server.start();
        try {
            HttpClient client = HttpClient.newHttpClient();
            String body = "\tat a.B." + h.obfuscate("compute") + "(B.java:7)\n";
            HttpResponse<String> r = client.send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + server.getPort() + "/retrace"))
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, r.statusCode());
            assertEquals("\tat a.B.compute(B.java:7)\n", r.body());

            HttpResponse<String> get = client.send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + server.getPort() + "/retrace")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(405, get.statusCode());
        } finally {
            server.stop();
        }
    }
}
