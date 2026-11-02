package ch.qos.ketem.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

class CommentStrippingTest {

    static final IdentifierHasher HASHER = new IdentifierHasher("comment-salt");

    static final String SOURCE = String.join("\n",
            "/*",
            " * License header",
            " */",
            "package c;",
            "",
            "/**",
            " * Class doc.",
            " * @deprecated use D",
            " */",
            "@Deprecated",
            "public class C {",
            "    /** field doc */ int x = 1;",
            "    // whole-line comment",
            "    int y = x /* inline */ + 1; // trailing",
            "    int/*glued*/z = 2;",
            "    /* a */ /* b */",
            "    int w = 3;",
            "}",
            "");

    @Test
    void stripCommentsRemovesAllButHeaderAndKeepsLines() {
        String expected = String.join("\n",
                "/*",
                " * License header",
                " */",
                "package c;",
                "",
                "",
                "",
                "",
                "",
                "@Deprecated",
                "public class C {",
                "    int x = 1;",
                "",
                "    int y = x + 1;",
                "    int z = 2;",
                "",
                "    int w = 3;",
                "}",
                "");
        assertEquals(expected, obfuscator(false, true).obfuscate(SOURCE, "C.java"));
    }

    @Test
    void stripJavadocKeepsOtherComments() {
        String expected = SOURCE
                .replace("/**\n * Class doc.\n * @deprecated use D\n */\n", "\n\n\n\n")
                .replace("    /** field doc */ int x = 1;", "    int x = 1;");
        assertEquals(expected, obfuscator(true, false).obfuscate(SOURCE, "C.java"));
    }

    @Test
    void javadocBeforePackageIsKept() {
        String src = "/** Licensed under ... */\npackage c;\n\n/** doc */\nclass D {\n}\n";
        assertEquals("/** Licensed under ... */\npackage c;\n\n\nclass D {\n}\n",
                obfuscator(true, false).obfuscate(src, "D.java"));
    }

    @Test
    void defaultPackageKeepsHeaderBeforeFirstImportOrType() {
        String src = "// header\nimport java.util.List;\n// gone\nclass E {\n    List<String> l; // gone too\n}\n";
        assertEquals("// header\nimport java.util.List;\n\nclass E {\n    List<String> l;\n}\n",
                obfuscator(false, true).obfuscate(src, "E.java"));
    }

    @Test
    void crLfLineEndingsArePreserved() {
        String src = "package c;\r\n/**\r\n * doc\r\n */\r\nclass F { // x\r\n}\r\n";
        assertEquals("package c;\r\n\r\n\r\n\r\nclass F {\r\n}\r\n", obfuscator(false, true).obfuscate(src, "F.java"));
    }

    @Test
    void strippedSampleBehavesIdenticallyAndRetraces() throws Exception {
        String original;
        try (InputStream in = getClass().getResourceAsStream("/sample/demo/Inventory.java")) {
            original = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Obfuscator o = obfuscator(true, true);
        String stripped = o.obfuscate(original, "Inventory.java");
        assertNotEquals(original.contains("//"), stripped.contains("//"));
        assertEquals(original.lines().count(), stripped.lines().count());

        ClassLoader a = InMemoryCompiler.compile("demo.Inventory", original);
        ClassLoader b = InMemoryCompiler.compile("demo.Inventory", stripped);
        assertEquals(a.loadClass("demo.Inventory").getMethod("run").invoke(null),
                b.loadClass("demo.Inventory").getMethod("run").invoke(null));

        StackTraceDeobfuscator d = new StackTraceDeobfuscator(HASHER, o.getRenamedIdentifiers());
        assertEquals(explode(a), d.deobfuscate(explode(b)));
    }

    private static Obfuscator obfuscator(boolean javadoc, boolean all) {
        Obfuscator o = new Obfuscator(HASHER);
        o.setStripJavadoc(javadoc);
        o.setStripComments(all);
        return o;
    }

    private static String explode(ClassLoader cl) throws Exception {
        Class<?> c = cl.loadClass("demo.Inventory");
        Object inv = c.getConstructor().newInstance();
        c.getMethod("add", String.class).invoke(inv, "x");
        try {
            c.getMethod("explode", int.class).invoke(inv, 2);
            throw new AssertionError("expected exception");
        } catch (InvocationTargetException e) {
            StringWriter sw = new StringWriter();
            e.getCause().printStackTrace(new PrintWriter(sw));
            return Stream.of(sw.toString().split("\n"))
                    .filter(l -> l.contains("demo.Inventory") || l.startsWith("java."))
                    .collect(Collectors.joining("\n"));
        }
    }
}
