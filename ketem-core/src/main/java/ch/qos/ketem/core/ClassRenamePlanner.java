package ch.qos.ketem.core;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.github.javaparser.ParseResult;
import com.github.javaparser.Problem;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Name;
import com.github.javaparser.ast.expr.SimpleName;
import com.github.javaparser.ast.expr.TypePatternExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithImplements;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.TypeParameter;

/**
 * Decides which package-private top-level classes can be renamed. Unlike
 * private members, such a class is visible to every file in its package, so
 * all of them are examined before any file is rewritten.
 *
 * <p>A class is renamed only if every file that could refer to it is being
 * obfuscated and its simple name has no other meaning in the package. It is
 * kept when:
 * <ul>
 * <li>it is listed in the class name excludes or the excluded identifiers;</li>
 * <li>an excluded source file or a test source in the same package mentions
 * its name;</li>
 * <li>the name is also used in the package for a variable, parameter, enum
 * constant, nested or local type, type parameter, or an import from
 * elsewhere;</li>
 * <li>it declares a {@code main} method, directly implements
 * {@code Serializable}/{@code Externalizable}, or carries an annotation that
 * suggests reflective access by name.</li>
 * </ul>
 */
final class ClassRenamePlanner {

    private static final Set<String> SERIALIZATION_TYPES = Set.of("Serializable", "Externalizable");

    private final Obfuscator obfuscator;
    private final SourceSelector classNameFilter;
    private final Charset encoding;

    /** package -> class name -> declaring file */
    private final Map<String, Map<String, Path>> candidates = new TreeMap<>();
    /** package -> name -> why the name cannot be renamed */
    private final Map<String, Map<String, String>> unsafe = new HashMap<>();
    private final Map<String, String> skipped = new TreeMap<>();

    ClassRenamePlanner(Obfuscator obfuscator, SourceSelector classNameFilter, Charset encoding) {
        this.obfuscator = obfuscator;
        this.classNameFilter = classNameFilter;
        this.encoding = encoding;
    }

    /**
     * @param mainFiles      every Java file of the compile source roots, mapped
     *                       to whether it is selected for obfuscation
     * @param referenceRoots roots compiled against the result but not rewritten,
     *                       typically the test source roots
     */
    ClassRenamePlan plan(Map<Path, Boolean> mainFiles, List<Path> referenceRoots) throws IOException {
        for (Map.Entry<Path, Boolean> e : mainFiles.entrySet()) {
            if (e.getValue()) {
                scanSelected(e.getKey());
            } else {
                scanReferencesOnly(e.getKey(), "an excluded source file");
            }
        }
        for (Path root : referenceRoots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            List<Path> files;
            try (Stream<Path> s = Files.walk(root)) {
                files = s.filter(p -> p.toString().endsWith(".java")).sorted().collect(Collectors.toList());
            }
            for (Path f : files) {
                scanReferencesOnly(f, "test sources");
            }
        }

        Map<String, Set<String>> names = new HashMap<>();
        Map<Path, String> primaryTypes = new HashMap<>();
        for (Map.Entry<String, Map<String, Path>> pkgEntry : candidates.entrySet()) {
            String pkg = pkgEntry.getKey();
            Map<String, String> unsafeInPkg = unsafe.getOrDefault(pkg, Map.of());
            for (Map.Entry<String, Path> c : pkgEntry.getValue().entrySet()) {
                String name = c.getKey();
                String reason = unsafeInPkg.get(name);
                if (reason != null) {
                    skipped.put(qualify(pkg, name), reason);
                    continue;
                }
                names.computeIfAbsent(pkg, k -> new TreeSet<>()).add(name);
                Path file = c.getValue();
                if (file.getFileName().toString().equals(name + ".java")) {
                    primaryTypes.put(file, name);
                }
            }
        }
        return new ClassRenamePlan(names, primaryTypes, skipped);
    }

    private void scanSelected(Path file) throws IOException {
        CompilationUnit cu = parse(file);
        String pkg = packageOf(cu);
        Map<String, String> u = unsafe.computeIfAbsent(pkg, k -> new LinkedHashMap<>());

        for (TypeDeclaration<?> t : cu.findAll(TypeDeclaration.class)) {
            if (!t.isTopLevelType()) {
                u.putIfAbsent(t.getNameAsString(), "its name is also used by a nested or local type");
            }
        }
        cu.findAll(TypeParameter.class).forEach(
                n -> u.putIfAbsent(n.getNameAsString(), "its name is also used by a type parameter"));
        List<SimpleName> variables = new ArrayList<>();
        cu.findAll(VariableDeclarator.class).forEach(n -> variables.add(n.getName()));
        cu.findAll(Parameter.class).forEach(n -> variables.add(n.getName()));
        cu.findAll(TypePatternExpr.class).forEach(n -> variables.add(n.getName()));
        variables.forEach(n -> u.putIfAbsent(n.getIdentifier(), "its name is also used by a variable"));
        cu.findAll(EnumConstantDeclaration.class).forEach(
                n -> u.putIfAbsent(n.getNameAsString(), "its name is also used by an enum constant"));
        for (ImportDeclaration imp : cu.getImports()) {
            if (imp.isAsterisk()) {
                continue;
            }
            Name name = imp.getName();
            String qualifier = name.getQualifier().map(Name::asString).orElse("");
            if (imp.isStatic() || !qualifier.equals(pkg)) {
                u.putIfAbsent(name.getIdentifier(), "a type or member with the same name is imported");
            }
        }

        for (TypeDeclaration<?> t : cu.getTypes()) {
            if (t.isPublic()) {
                continue;
            }
            String name = t.getNameAsString();
            String fqcn = qualify(pkg, name);
            String reason = null;
            if (!classNameFilter.isSelected(fqcn)) {
                reason = "listed in classNameExcludes";
            } else if (obfuscator.isExcludedIdentifier(name)) {
                reason = "listed in excludedIdentifiers";
            } else if (Obfuscator.hasSignificantAnnotation(t.getAnnotations())) {
                reason = "annotated, possibly accessed by name";
            } else if (!t.getMethodsByName("main").isEmpty()) {
                reason = "declares a main method";
            } else if (implementsSerialization(t)) {
                reason = "serializable; renaming would change its serialized form";
            }
            if (reason != null) {
                skipped.put(fqcn, reason);
            } else {
                candidates.computeIfAbsent(pkg, k -> new TreeMap<>()).put(name, file);
            }
        }
    }

    /** Every name mentioned in a file we will not rewrite must keep its meaning. */
    private void scanReferencesOnly(Path file, String what) throws IOException {
        CompilationUnit cu = parse(file);
        Map<String, String> u = unsafe.computeIfAbsent(packageOf(cu), k -> new LinkedHashMap<>());
        String reason = "referenced from " + what + " (" + file.getFileName() + ")";
        cu.findAll(SimpleName.class).forEach(n -> u.putIfAbsent(n.getIdentifier(), reason));
        cu.findAll(Name.class).forEach(n -> u.putIfAbsent(n.getIdentifier(), reason));
    }

    private static boolean implementsSerialization(TypeDeclaration<?> t) {
        if (!(t instanceof NodeWithImplements)) {
            return false;
        }
        for (ClassOrInterfaceType it : ((NodeWithImplements<?>) t).getImplementedTypes()) {
            if (SERIALIZATION_TYPES.contains(it.getNameAsString())) {
                return true;
            }
        }
        return false;
    }

    private CompilationUnit parse(Path file) throws IOException {
        String source = new String(Files.readAllBytes(file), encoding);
        ParseResult<CompilationUnit> r = obfuscator.getParser().parse(source);
        if (!r.isSuccessful() || r.getResult().isEmpty()) {
            String problems = r.getProblems().stream().map(Problem::getVerboseMessage)
                    .collect(Collectors.joining("\n"));
            throw new ObfuscationException("Failed to parse " + file + ":\n" + problems);
        }
        return r.getResult().get();
    }

    static String packageOf(CompilationUnit cu) {
        return cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");
    }

    private static String qualify(String pkg, String name) {
        return pkg.isEmpty() ? name : pkg + "." + name;
    }
}
