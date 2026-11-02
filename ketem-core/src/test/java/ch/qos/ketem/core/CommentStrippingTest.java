package ch.qos.ketem.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
        assertEquals(expected, obfuscator().obfuscate(SOURCE, "C.java"));
    }

    @Test
    void javadocBeforePackageIsKept() {
        String src = "/** Licensed under ... */\npackage c;\n\n/** doc */\nclass D {\n}\n";
        assertEquals("/** Licensed under ... */\npackage c;\n\n\nclass D {\n}\n",
                obfuscator().obfuscate(src, "D.java"));
    }

    @Test
    void defaultPackageKeepsHeaderBeforeFirstImportOrType() {
        String src = "// header\nimport java.util.List;\n// gone\nclass E {\n    List<String> l; // gone too\n}\n";
        assertEquals("// header\nimport java.util.List;\n\nclass E {\n    List<String> l;\n}\n",
                obfuscator().obfuscate(src, "E.java"));
    }

    @Test
    void crLfLineEndingsArePreserved() {
        String src = "package c;\r\n/**\r\n * doc\r\n */\r\nclass F { // x\r\n}\r\n";
        assertEquals("package c;\r\n\r\n\r\n\r\nclass F {\r\n}\r\n", obfuscator().obfuscate(src, "F.java"));
    }

    @Test
    void strippedSampleBehavesIdenticallyAndRetraces() throws Exception {
        String original;
        try (InputStream in = getClass().getResourceAsStream("/sample/demo/Inventory.java")) {
            original = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Obfuscator o = obfuscator();
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

    @Test
    void stripScopePackagesAlsoStripsExcludedFilesWithoutRenaming(@TempDir Path tmp) throws IOException {
        Path out = processTree(tmp, StripScope.PACKAGES);
        assertEquals("package p;\n\nclass B {\n    private int count;\n\n}\n", read(out, "p/B.java"));
        assertEquals(EXCLUDED_ELSEWHERE, read(out, "q/C.java"), "outside the designated packages");
        String a = read(out, "p/A.java");
        assertTrue(a.contains(HASHER.obfuscate("total")) && !a.contains("//"), a);
    }

    @Test
    void stripScopeObfuscatedLeavesExcludedFilesAlone(@TempDir Path tmp) throws IOException {
        Path out = processTree(tmp, StripScope.OBFUSCATED);
        assertEquals(EXCLUDED_IN_PACKAGE, read(out, "p/B.java"));
        assertEquals(EXCLUDED_ELSEWHERE, read(out, "q/C.java"));
    }

    static final String EXCLUDED_IN_PACKAGE =
            "package p;\n/** doc */\nclass B {\n    private int count; // kept name\n    // note\n}\n";
    static final String EXCLUDED_ELSEWHERE = "package q;\n/** doc */\nclass C {\n}\n";

    private static Path processTree(Path tmp, StripScope scope) throws IOException {
        Path src = tmp.resolve("src");
        write(src, "p/A.java", "package p;\n\nclass A {\n    private int total; // gone\n}\n");
        write(src, "p/B.java", EXCLUDED_IN_PACKAGE);
        write(src, "q/C.java", EXCLUDED_ELSEWHERE);
        SourceTreeObfuscator tree = new SourceTreeObfuscator(obfuscator(),
                new SourceSelector(List.of("p"), null, List.of("p.B")), StandardCharsets.UTF_8);
        tree.setStripScope(scope);
        Path out = tmp.resolve("out");
        tree.process(List.of(src), out);
        assertEquals(scope == StripScope.PACKAGES ? 1 : 0, tree.getStrippedOnlyCount());
        return out;
    }

    private static void write(Path root, String rel, String content) throws IOException {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    private static String read(Path root, String rel) throws IOException {
        return Files.readString(root.resolve(rel));
    }

    private static Obfuscator obfuscator() {
        Obfuscator o = new Obfuscator(HASHER);
        o.setStripComments(true);
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
