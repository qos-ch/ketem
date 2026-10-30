package ch.qos.ketem.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;

/**
 * Command line entry point for de-obfuscating stack traces.
 */
public final class RetraceMain {

    private static final String USAGE = String.join("\n",
            "Usage: java -jar ketem-core-retrace.jar [options] [trace-file]",
            "",
            "Reads an obfuscated stack trace from trace-file (or stdin) and prints it",
            "with the original identifiers restored.",
            "",
            "Options:",
            "  --salt <salt>            the salt used during obfuscation",
            "                           (default: $KETEM_SALT environment variable)",
            "  --identifiers <file>     dictionary file written by the Maven plugin (repeatable)",
            "  --sources <dir>          original source tree to harvest identifiers from (repeatable)",
            "  --hash-length <n>        hash length used during obfuscation (default 12)",
            "  --serve <port>           run an HTTP service instead (POST /retrace)",
            "  --bind <host>            address for --serve (default 127.0.0.1)",
            "  -h, --help               show this help");

    private RetraceMain() {
    }

    public static void main(String[] args) throws IOException {
        String salt = System.getenv("KETEM_SALT");
        int hashLength = IdentifierHasher.DEFAULT_HASH_LENGTH;
        List<Path> dictionaries = new ArrayList<>();
        List<Path> sources = new ArrayList<>();
        Integer port = null;
        String bind = "127.0.0.1";
        Path traceFile = null;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
            case "--salt": salt = value(args, ++i, a); break;
            case "--identifiers": dictionaries.add(Paths.get(value(args, ++i, a))); break;
            case "--sources": sources.add(Paths.get(value(args, ++i, a))); break;
            case "--hash-length": hashLength = Integer.parseInt(value(args, ++i, a)); break;
            case "--serve": port = Integer.parseInt(value(args, ++i, a)); break;
            case "--bind": bind = value(args, ++i, a); break;
            case "-h": case "--help": System.out.println(USAGE); return;
            default:
                if (a.startsWith("-") || traceFile != null) {
                    fail("Unexpected argument: " + a);
                }
                traceFile = Paths.get(a);
            }
        }
        if (salt == null || salt.isEmpty()) {
            fail("No salt given; use --salt or set KETEM_SALT");
        }
        if (dictionaries.isEmpty() && sources.isEmpty()) {
            fail("Give at least one --identifiers file or --sources directory");
        }

        Set<String> ids = new TreeSet<>();
        for (Path d : dictionaries) {
            ids.addAll(IdentifierDictionary.read(d).getIdentifiers());
        }
        JavaParser parser = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE));
        for (Path s : sources) {
            ids.addAll(IdentifierDictionary.fromSources(s, parser).getIdentifiers());
        }
        StackTraceDeobfuscator deobfuscator = new StackTraceDeobfuscator(new IdentifierHasher(salt, hashLength), ids);

        if (port != null) {
            RetraceServer server = new RetraceServer(deobfuscator, bind, port);
            server.start();
            System.err.println("ketem retrace service listening on http://" + bind + ":" + server.getPort()
                    + "/ (" + deobfuscator.size() + " identifiers)");
            return;
        }
        String trace;
        if (traceFile != null) {
            trace = new String(Files.readAllBytes(traceFile), StandardCharsets.UTF_8);
        } else {
            InputStream in = System.in;
            trace = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        System.out.print(deobfuscator.deobfuscate(trace));
        System.out.flush();
    }

    private static String value(String[] args, int i, String option) {
        if (i >= args.length) {
            fail("Missing value for " + option);
        }
        return args[i];
    }

    private static void fail(String message) {
        System.err.println(message);
        System.err.println();
        System.err.println(USAGE);
        System.exit(2);
    }
}
