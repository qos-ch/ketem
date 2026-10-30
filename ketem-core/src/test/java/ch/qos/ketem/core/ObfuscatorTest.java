package ch.qos.ketem.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ObfuscatorTest {

    static final String SALT = "test-salt";
    static String original;
    static String obfuscated;
    static Set<String> renamed;
    static IdentifierHasher hasher = new IdentifierHasher(SALT);

    @BeforeAll
    static void obfuscateSample() throws IOException {
        try (InputStream in = ObfuscatorTest.class.getResourceAsStream("/sample/demo/Inventory.java")) {
            original = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Obfuscator obfuscator = new Obfuscator(hasher);
        obfuscated = obfuscator.obfuscate(original, "Inventory.java");
        renamed = obfuscator.getRenamedIdentifiers();
    }

    @Test
    void hashFormat() {
        String o = hasher.obfuscate("total");
        assertTrue(o.matches("_[0-9a-f]{12}"), o);
        assertEquals(o, new IdentifierHasher(SALT).obfuscate("total"));
        assertNotEquals(o, new IdentifierHasher("other").obfuscate("total"));
    }

    @Test
    void renamesPrivateAndLocalIdentifiers() {
        for (String id : new String[] {"total", "items", "normalize", "decorate", "nextId", "recurse", "failWith",
                "normalized", "result", "badIndex", "text", "tmp", "calls", "acc", "base", "remaining", "counter"}) {
            assertTrue(renamed.contains(id), id + " should be renamed");
            assertTrue(obfuscated.contains(hasher.obfuscate(id)), id);
        }
    }

    @Test
    void keepsApiAndUnsafeNames() {
        for (String id : new String[] {"serialVersionUID", "visible", "shared", "size", "Point", "x", "y",
                "weight", "SMALL", "add", "describe", "run", "Inventory"}) {
            assertFalse(renamed.contains(id), id + " must not be renamed");
        }
        assertTrue(obfuscated.contains("public record Point(int x, int y)"));
        assertTrue(obfuscated.contains("other.shared"));
    }

    @Test
    void preservesLineStructure() {
        assertEquals(original.lines().count(), obfuscated.lines().count());
    }

    @Test
    void obfuscatedCodeBehavesIdentically() throws Exception {
        assertEquals(invokeRun(original), invokeRun(obfuscated));
    }

    @Test
    void stackTraceRoundTrip() throws Exception {
        String originalTrace = explode(original);
        String obfuscatedTrace = explode(obfuscated);
        assertNotEquals(originalTrace, obfuscatedTrace);
        assertTrue(obfuscatedTrace.contains(hasher.obfuscate("recurse")), obfuscatedTrace);

        StackTraceDeobfuscator d = new StackTraceDeobfuscator(hasher, renamed);
        assertEquals(originalTrace, d.deobfuscate(obfuscatedTrace));
    }

    @Test
    void deobfuscationFromSourceIdentifiers() throws Exception {
        // A superset of identifiers (everything in the source) also works.
        Set<String> all = new java.util.TreeSet<>();
        new com.github.javaparser.JavaParser().parse(original).getResult().get()
                .findAll(com.github.javaparser.ast.expr.SimpleName.class).forEach(n -> all.add(n.getIdentifier()));
        StackTraceDeobfuscator d = new StackTraceDeobfuscator(hasher, all);
        assertEquals(explode(original), d.deobfuscate(explode(obfuscated)));
    }

    @Test
    void unknownTokensAreLeftAlone() {
        StackTraceDeobfuscator d = new StackTraceDeobfuscator(hasher, Set.of("foo"));
        String foo = hasher.obfuscate("foo");
        assertEquals("at a.B.foo(B.java:1) _0123456789ab x" + foo + " lambda$foo$0",
                d.deobfuscate("at a.B." + foo + "(B.java:1) _0123456789ab x" + foo + " lambda$" + foo + "$0"));
    }

    @Test
    void parseErrorIsReported() {
        Obfuscator o = new Obfuscator(hasher);
        ObfuscationException e = assertThrows(ObfuscationException.class, () -> o.obfuscate("class {", "Bad.java"));
        assertTrue(e.getMessage().contains("Bad.java"));
    }

    @Test
    void excludedIdentifiersAreKept() {
        Obfuscator o = new Obfuscator(hasher, Set.of("keepMe"),
                com.github.javaparser.ParserConfiguration.LanguageLevel.JAVA_21);
        String out = o.obfuscate("class A { private int keepMe; private int other; int f() { return keepMe + other; } }",
                "A.java");
        assertTrue(out.contains("keepMe + " + hasher.obfuscate("other")), out);
    }

    private static Object invokeRun(String source) throws Exception {
        Class<?> c = InMemoryCompiler.compile("demo.Inventory", source).loadClass("demo.Inventory");
        return c.getMethod("run").invoke(null);
    }

    private static String explode(String source) throws Exception {
        Class<?> c = InMemoryCompiler.compile("demo.Inventory", source).loadClass("demo.Inventory");
        Object inv = c.getConstructor().newInstance();
        c.getMethod("add", String.class).invoke(inv, "x");
        Method explode = c.getMethod("explode", int.class);
        try {
            explode.invoke(inv, 2);
            throw new AssertionError("expected exception");
        } catch (InvocationTargetException e) {
            StringWriter sw = new StringWriter();
            e.getCause().printStackTrace(new PrintWriter(sw));
            // Keep only the frames from the sample itself, which is all that obfuscation affects.
            StringBuilder sb = new StringBuilder();
            for (String line : sw.toString().split("\n")) {
                if (line.contains("demo.Inventory") || line.startsWith("java.")) {
                    sb.append(line).append('\n');
                }
            }
            return sb.toString();
        }
    }
}
