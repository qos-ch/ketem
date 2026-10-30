package ch.qos.ketem.core;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Copies one or more source roots into an output directory, obfuscating the
 * selected {@code .java} files and copying everything else verbatim.
 */
public final class SourceTreeObfuscator {

    private final Obfuscator obfuscator;
    private final SourceSelector selector;
    private final Charset encoding;

    private int obfuscatedCount;
    private int copiedCount;

    public SourceTreeObfuscator(Obfuscator obfuscator, SourceSelector selector, Charset encoding) {
        this.obfuscator = obfuscator;
        this.selector = selector;
        this.encoding = encoding;
    }

    public void process(List<Path> sourceRoots, Path outputDir) throws IOException {
        Map<Path, Path> seen = new HashMap<>();
        for (Path root : sourceRoots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            List<Path> files;
            try (Stream<Path> s = Files.walk(root)) {
                files = s.filter(Files::isRegularFile).sorted().collect(Collectors.toList());
            }
            for (Path file : files) {
                Path rel = root.relativize(file);
                Path other = seen.putIfAbsent(rel, root);
                if (other != null) {
                    throw new ObfuscationException(rel + " exists in both " + other + " and " + root);
                }
                Path target = outputDir.resolve(rel);
                Files.createDirectories(target.getParent());
                if (rel.toString().endsWith(".java") && selector.isSelected(fqcnOf(rel))) {
                    String source = new String(Files.readAllBytes(file), encoding);
                    Files.write(target, obfuscator.obfuscate(source, file.toString()).getBytes(encoding));
                    obfuscatedCount++;
                } else {
                    Files.copy(file, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    copiedCount++;
                }
            }
        }
    }

    /** {@code com/example/Foo.java} becomes {@code com.example.Foo}. */
    static String fqcnOf(Path rel) {
        List<String> parts = new ArrayList<>();
        for (Path p : rel) {
            parts.add(p.toString());
        }
        String last = parts.remove(parts.size() - 1);
        parts.add(last.substring(0, last.length() - ".java".length()));
        return String.join(".", parts);
    }

    public int getObfuscatedCount() {
        return obfuscatedCount;
    }

    public int getCopiedCount() {
        return copiedCount;
    }
}
