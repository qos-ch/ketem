package ch.qos.ketem.core;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

    private boolean renamePackagePrivateClasses;
    private SourceSelector classNameFilter = SourceSelector.all();
    private List<Path> referenceRoots = new ArrayList<>();
    private ClassRenamePlan classRenamePlan = ClassRenamePlan.NONE;

    private int obfuscatedCount;
    private int copiedCount;

    public SourceTreeObfuscator(Obfuscator obfuscator, SourceSelector selector, Charset encoding) {
        this.obfuscator = obfuscator;
        this.selector = selector;
        this.encoding = encoding;
    }

    /**
     * Also renames package-private top-level classes in the selected files.
     *
     * @param classNameExcludes classes (or packages) whose package-private
     *                          classes keep their names
     * @param referenceRoots    roots that are compiled against the result but
     *                          not rewritten, e.g. test sources; classes they
     *                          mention keep their names
     */
    public void enableClassRenaming(Collection<String> classNameExcludes, List<Path> referenceRoots) {
        this.renamePackagePrivateClasses = true;
        this.classNameFilter = new SourceSelector(null, null, classNameExcludes);
        this.referenceRoots = new ArrayList<>(referenceRoots);
    }

    public void process(List<Path> sourceRoots, Path outputDir) throws IOException {
        Map<Path, Path> seen = new HashMap<>();
        Map<Path, Path> fileToRel = new LinkedHashMap<>();
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
                fileToRel.put(file, rel);
            }
        }

        if (renamePackagePrivateClasses) {
            Map<Path, Boolean> javaFiles = new LinkedHashMap<>();
            fileToRel.forEach((file, rel) -> {
                if (isJava(rel)) {
                    javaFiles.put(file, selector.isSelected(fqcnOf(rel)));
                }
            });
            classRenamePlan = new ClassRenamePlanner(obfuscator, classNameFilter, encoding)
                    .plan(javaFiles, referenceRoots);
        }

        Set<Path> targets = new HashSet<>();
        for (Map.Entry<Path, Path> e : fileToRel.entrySet()) {
            Path file = e.getKey();
            Path rel = e.getValue();
            boolean obfuscate = isJava(rel) && selector.isSelected(fqcnOf(rel));
            Path target = outputDir.resolve(rel);
            Optional<String> primary = obfuscate ? classRenamePlan.renamedPrimaryType(file) : Optional.empty();
            if (primary.isPresent()) {
                target = target.resolveSibling(obfuscator.obfuscatedName(primary.get()) + ".java");
            }
            if (!targets.add(target)) {
                throw new ObfuscationException("Two source files map to " + outputDir.relativize(target));
            }
            Files.createDirectories(target.getParent());
            if (obfuscate) {
                String source = new String(Files.readAllBytes(file), encoding);
                Files.write(target, obfuscator.obfuscate(source, file.toString(), classRenamePlan).getBytes(encoding));
                obfuscatedCount++;
            } else {
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                copiedCount++;
            }
        }
    }

    private static boolean isJava(Path rel) {
        return rel.toString().endsWith(".java");
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

    public ClassRenamePlan getClassRenamePlan() {
        return classRenamePlan;
    }
}
