package ch.qos.ketem.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Stream;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.SimpleName;

/**
 * The set of original identifiers that were obfuscated. Together with the salt
 * it is sufficient to reverse the obfuscation. The salt itself is never
 * written to the dictionary file.
 */
public final class IdentifierDictionary {

    private static final String HEADER = "# ketem identifier dictionary: one original identifier per line";

    private final SortedSet<String> identifiers = new TreeSet<>();

    public IdentifierDictionary() {
    }

    public IdentifierDictionary(Collection<String> identifiers) {
        this.identifiers.addAll(identifiers);
    }

    public void addAll(Collection<String> ids) {
        identifiers.addAll(ids);
    }

    public SortedSet<String> getIdentifiers() {
        return identifiers;
    }

    public void write(Path file) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        List<String> lines = new ArrayList<>(identifiers.size() + 1);
        lines.add(HEADER);
        lines.addAll(identifiers);
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    public static IdentifierDictionary read(Path file) throws IOException {
        IdentifierDictionary dict = new IdentifierDictionary();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String s = line.trim();
            if (!s.isEmpty() && !s.startsWith("#")) {
                dict.identifiers.add(s);
            }
        }
        return dict;
    }

    /**
     * Collects every simple name occurring in the (original, non-obfuscated)
     * Java sources under {@code root}. This is a superset of the obfuscated
     * identifiers and can stand in for a lost dictionary file.
     */
    public static IdentifierDictionary fromSources(Path root, JavaParser parser) throws IOException {
        IdentifierDictionary dict = new IdentifierDictionary();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                try {
                    ParseResult<CompilationUnit> r = parser.parse(p);
                    r.getResult().ifPresent(cu ->
                            cu.findAll(SimpleName.class).forEach(n -> dict.identifiers.add(n.getIdentifier())));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        return dict;
    }
}
