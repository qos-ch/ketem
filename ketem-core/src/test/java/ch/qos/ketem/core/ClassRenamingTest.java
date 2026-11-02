package ch.qos.ketem.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClassRenamingTest {

    static final IdentifierHasher HASHER = new IdentifierHasher("class-salt");

    @TempDir
    Path tmp;
    Path main;
    Path test;
    Path out;
    Obfuscator obfuscator;
    SourceTreeObfuscator tree;

    @BeforeEach
    void setUp() throws IOException {
        main = tmp.resolve("main");
        test = tmp.resolve("test");
        out = tmp.resolve("out");
        write(main, "demo2/Api.java", String.join("\n",
                "package demo2;",
                "",
                "import java.util.function.Supplier;",
                "",
                "public class Api {",
                "    public static String run() {",
                "        Helper h = new Helper(3);",
                "        Supplier<Helper> s = Helper::new;",
                "        String Shadow = \"shadow\";",
                "        StringBuilder sb = new StringBuilder();",
                "        sb.append(h.twice()).append(',');",
                "        sb.append(Helper.CONST).append(',');",
                "        sb.append(demo2.Helper.make().twice()).append(',');",
                "        sb.append(s.get().twice()).append(',');",
                "        sb.append(Color.RED.label()).append(',');",
                "        sb.append(new Pair(1, 2).sum()).append(',');",
                "        sb.append(Kept.class.getSimpleName()).append(',');",
                "        sb.append(Reflected.NAME).append(',');",
                "        sb.append(UsedByTest.ID).append(',');",
                "        sb.append(Shadow);",
                "        return sb.toString();",
                "    }",
                "",
                "    public static void boom() {",
                "        Helper.make().explode();",
                "    }",
                "}",
                "",
                "record Pair(int a, int b) {",
                "    Pair {",
                "        if (a < 0) {",
                "            throw new IllegalArgumentException();",
                "        }",
                "    }",
                "",
                "    int sum() {",
                "        return a + b;",
                "    }",
                "}",
                ""));
        write(main, "demo2/Helper.java", String.join("\n",
                "package demo2;",
                "",
                "class Helper {",
                "    static final int CONST = 7;",
                "    private final int value;",
                "",
                "    Helper() {",
                "        this(1);",
                "    }",
                "",
                "    Helper(int value) {",
                "        this.value = value;",
                "    }",
                "",
                "    static Helper make() {",
                "        return new Helper(5);",
                "    }",
                "",
                "    int twice() {",
                "        Inner in = new Inner();",
                "        return in.get() * 2;",
                "    }",
                "",
                "    void explode() {",
                "        throw new IllegalStateException(\"boom \" + value);",
                "    }",
                "",
                "    class Inner {",
                "        int get() {",
                "            return Helper.this.value;",
                "        }",
                "    }",
                "}",
                ""));
        write(main, "demo2/Color.java", "package demo2;\n\nenum Color {\n    RED;\n\n    String label() {\n        return \"red\";\n    }\n}\n");
        write(main, "demo2/Kept.java", "package demo2;\n\nclass Kept {\n}\n");
        write(main, "demo2/Reflected.java", "package demo2;\n\nclass Reflected {\n    static final String NAME = \"r\";\n}\n");
        write(main, "demo2/Legacy.java", "package demo2;\n\nclass Legacy {\n    String n = Reflected.NAME;\n}\n");
        write(main, "demo2/UsedByTest.java", "package demo2;\n\nclass UsedByTest {\n    static final int ID = 9;\n}\n");
        write(main, "demo2/Launcher.java", "package demo2;\n\nclass Launcher {\n    public static void main(String[] args) {\n    }\n}\n");
        write(main, "demo2/Shadow.java", "package demo2;\n\nclass Shadow {\n}\n");
        write(test, "demo2/UsedByTestTest.java", "package demo2;\n\nclass UsedByTestTest {\n    int id = UsedByTest.ID;\n}\n");

        obfuscator = new Obfuscator(HASHER);
        tree = new SourceTreeObfuscator(obfuscator,
                new SourceSelector(List.of("demo2"), null, List.of("demo2.Legacy")), StandardCharsets.UTF_8);
        tree.enableClassRenaming(List.of("demo2.Kept"), List.of(test));
        tree.process(List.of(main), out);
    }

    @Test
    void renamesEligibleClassesAndKeepsTheRest() {
        assertTrue(obfuscator.getRenamedIdentifiers().containsAll(List.of("Helper", "Color", "Pair")));
        Map<String, String> skipped = tree.getClassRenamePlan().getSkipped();
        assertEquals("listed in classNameExcludes", skipped.get("demo2.Kept"));
        assertTrue(skipped.get("demo2.Reflected").contains("excluded source file (Legacy.java)"), skipped.toString());
        assertTrue(skipped.get("demo2.UsedByTest").contains("test sources"), skipped.toString());
        assertEquals("declares a main method", skipped.get("demo2.Launcher"));
        assertEquals("its name is also used by a variable", skipped.get("demo2.Shadow"));
        assertFalse(skipped.containsKey("demo2.Legacy"), "unselected files are not candidates");
        assertFalse(obfuscator.getRenamedIdentifiers().contains("Api"), "public classes keep their names");
    }

    @Test
    void renamesSourceFilesOfRenamedClasses() throws IOException {
        Path pkg = out.resolve("demo2");
        assertTrue(Files.exists(pkg.resolve(HASHER.obfuscate("Helper") + ".java")));
        assertTrue(Files.exists(pkg.resolve(HASHER.obfuscate("Color") + ".java")));
        assertFalse(Files.exists(pkg.resolve("Helper.java")));
        assertTrue(Files.exists(pkg.resolve("Api.java")), "Pair is a secondary type of Api.java");
        assertTrue(Files.exists(pkg.resolve("Kept.java")));
        String legacy = Files.readString(pkg.resolve("Legacy.java"));
        assertEquals(Files.readString(main.resolve("demo2/Legacy.java")), legacy, "excluded files are copied verbatim");
        String api = Files.readString(pkg.resolve("Api.java"));
        assertFalse(api.contains("Helper"), api);
        assertTrue(api.contains("demo2." + HASHER.obfuscate("Helper") + ".make()"), api);
    }

    @Test
    void obfuscatedTreeCompilesAndBehavesIdentically() throws Exception {
        ClassLoader original = InMemoryCompiler.compile(sources(main));
        ClassLoader obfuscated = InMemoryCompiler.compile(sources(out));
        assertEquals(run(original), run(obfuscated));
        obfuscated.loadClass("demo2." + HASHER.obfuscate("Helper"));
    }

    @Test
    void stackTraceThroughRenamedClassRetraces() throws Exception {
        String originalTrace = boom(InMemoryCompiler.compile(sources(main)));
        String obfuscatedTrace = boom(InMemoryCompiler.compile(sources(out)));
        String helper = HASHER.obfuscate("Helper");
        assertTrue(obfuscatedTrace.contains("demo2." + helper + ".explode(" + helper + ".java:"), obfuscatedTrace);
        StackTraceDeobfuscator d = new StackTraceDeobfuscator(HASHER, obfuscator.getRenamedIdentifiers());
        assertEquals(originalTrace, d.deobfuscate(obfuscatedTrace));
    }

    private static Object run(ClassLoader cl) throws Exception {
        return cl.loadClass("demo2.Api").getMethod("run").invoke(null);
    }

    private static String boom(ClassLoader cl) throws Exception {
        try {
            cl.loadClass("demo2.Api").getMethod("boom").invoke(null);
            throw new AssertionError("expected exception");
        } catch (InvocationTargetException e) {
            StringWriter sw = new StringWriter();
            e.getCause().printStackTrace(new PrintWriter(sw));
            return Stream.of(sw.toString().split("\n"))
                    .filter(l -> l.contains("demo2") || l.startsWith("java."))
                    .collect(Collectors.joining("\n"));
        }
    }

    private static Map<String, String> sources(Path root) throws IOException {
        Map<String, String> m = new TreeMap<>();
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : (Iterable<Path>) s.filter(f -> f.toString().endsWith(".java"))::iterator) {
                m.put(root.relativize(p).toString(), Files.readString(p));
            }
        }
        return m;
    }

    private static void write(Path root, String rel, String content) throws IOException {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }
}
