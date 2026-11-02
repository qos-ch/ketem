package ch.qos.ketem.core;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

/** Compiles a single source file in memory and loads the resulting classes. */
final class InMemoryCompiler {

    private InMemoryCompiler() {
    }

    static ClassLoader compile(String className, String source) {
        return compile(Map.of(className.replace('.', '/') + ".java", source));
    }

    /** @param sources relative source path (e.g. {@code demo/Foo.java}) to source text */
    static ClassLoader compile(Map<String, String> sources) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Map<String, ByteArrayOutputStream> classes = new HashMap<>();
        List<JavaFileObject> files = new ArrayList<>();
        sources.forEach((path, source) -> files.add(new SimpleJavaFileObject(
                URI.create("string:///" + path), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        }));
        JavaFileManager fm = new ForwardingJavaFileManager<JavaFileManager>(
                compiler.getStandardFileManager(null, null, null)) {
            @Override
            public JavaFileObject getJavaFileForOutput(Location location, String name, JavaFileObject.Kind kind,
                    FileObject sibling) {
                return new SimpleJavaFileObject(URI.create("mem:///" + name + ".class"), kind) {
                    @Override
                    public OutputStream openOutputStream() {
                        return classes.computeIfAbsent(name, k -> new ByteArrayOutputStream());
                    }
                };
            }
        };
        StringWriter errors = new StringWriter();
        boolean ok = compiler.getTask(errors, fm, null, List.of("--release", "21", "-g"), null, files).call();
        if (!ok) {
            throw new AssertionError("Compilation failed:\n" + errors + "\n--- sources ---\n" + sources);
        }
        return new ClassLoader(InMemoryCompiler.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                ByteArrayOutputStream b = classes.get(name);
                if (b == null) {
                    throw new ClassNotFoundException(name);
                }
                byte[] bytes = b.toByteArray();
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
    }
}
