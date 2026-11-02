package ch.qos.ketem.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.Position;
import com.github.javaparser.Problem;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.CompactConstructorDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.Name;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SimpleName;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.TypeExpr;
import com.github.javaparser.ast.expr.TypePatternExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.nodeTypes.SwitchNode;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;

/**
 * Obfuscates identifiers whose scope is confined to a single compilation unit:
 * local variables, parameters, pattern variables, private fields and private
 * methods. Public and protected API, class names and package names are left
 * untouched, so each file can be processed independently and the rest of the
 * build (and its consumers) keeps compiling.
 *
 * <p>The syntax tree is used for analysis only; new names are spliced into the
 * original text, so formatting is preserved exactly and line numbers in stack
 * traces still point at the original source lines.
 *
 * <p>Name resolution is purely syntactic (no classpath), so the obfuscator is
 * deliberately conservative: a member is renamed only when every reference to
 * it within the file can be attributed to its declaration. One known blind
 * spot: a nested or anonymous class that <em>inherits</em> a field or method
 * from an external supertype with the same simple name as a private member of
 * the enclosing class. Add such names to the excluded identifiers.
 *
 * <p>Optionally, all comments, Javadoc included, are removed (keeping a header
 * before the package declaration), leaving their line breaks in place.
 *
 * <p>Optionally, package-private top-level classes are renamed too; see
 * {@link ClassRenamePlanner} for how they are chosen.
 *
 * <p>Instances accumulate the mapping across files and fail fast on a hash
 * collision, so use one instance per build.
 */
public final class Obfuscator {

    /** Names with a contract to the JVM or serialization machinery. */
    public static final Set<String> DEFAULT_EXCLUDED_IDENTIFIERS = Set.of(
            "serialVersionUID", "serialPersistentFields",
            "writeObject", "readObject", "readObjectNoData", "writeReplace", "readResolve",
            "_");

    /** Annotations that do not imply reflective access to the annotated member. */
    private static final Set<String> NEUTRAL_ANNOTATIONS = Set.of(
            "Override", "SuppressWarnings", "Deprecated", "SafeVarargs", "FunctionalInterface");

    private final IdentifierHasher hasher;
    private final Set<String> excluded;
    private final JavaParser parser;
    private final Map<String, String> obfuscatedToOriginal = new HashMap<>();
    private boolean stripComments;

    public Obfuscator(IdentifierHasher hasher) {
        this(hasher, Collections.emptySet(), LanguageLevel.JAVA_21);
    }

    public Obfuscator(IdentifierHasher hasher, Collection<String> excludedIdentifiers, LanguageLevel languageLevel) {
        this.hasher = hasher;
        this.excluded = new HashSet<>(DEFAULT_EXCLUDED_IDENTIFIERS);
        if (excludedIdentifiers != null) {
            this.excluded.addAll(excludedIdentifiers);
        }
        // Tab size 1 makes parser columns equal to character offsets within a line.
        this.parser = new JavaParser(new ParserConfiguration().setLanguageLevel(languageLevel).setTabSize(1));
    }

    /**
     * Removes all comments, Javadoc included, except those before the package
     * declaration.
     */
    public void setStripComments(boolean stripComments) {
        this.stripComments = stripComments;
    }

    public boolean isStripComments() {
        return stripComments;
    }

    public JavaParser getParser() {
        return parser;
    }

    /** All original identifiers renamed so far by this instance. */
    public Set<String> getRenamedIdentifiers() {
        return new TreeSet<>(obfuscatedToOriginal.values());
    }

    /** The obfuscated form of {@code original}, without recording it. */
    public String obfuscatedName(String original) {
        return hasher.obfuscate(original);
    }

    /**
     * @param source   Java source text
     * @param origin   used in error messages only, e.g. a file path
     * @return the obfuscated source
     */
    public String obfuscate(String source, String origin) {
        return obfuscate(source, origin, ClassRenamePlan.NONE);
    }

    /**
     * @param plan package-private top-level classes to rename, computed over
     *             the whole source tree
     */
    public String obfuscate(String source, String origin, ClassRenamePlan plan) {
        ParseResult<CompilationUnit> result = parse(source, origin);
        CompilationUnit cu = result.getResult().get();
        List<Comment> comments = commentsToStrip(result, cu);
        return new Pass(cu, plan.namesFor(ClassRenamePlanner.packageOf(cu))).run(source, origin, comments);
    }

    /**
     * Removes comments, if enabled, without renaming anything. Used for files
     * excluded from obfuscation that should still lose their comments.
     */
    public String stripCommentsOnly(String source, String origin) {
        ParseResult<CompilationUnit> result = parse(source, origin);
        SourceEditor editor = new SourceEditor(source, origin);
        for (Comment c : commentsToStrip(result, result.getResult().get())) {
            editor.removeComment(c);
        }
        return editor.apply();
    }

    private ParseResult<CompilationUnit> parse(String source, String origin) {
        ParseResult<CompilationUnit> result = parser.parse(source);
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            String problems = result.getProblems().stream().map(Problem::getVerboseMessage)
                    .collect(Collectors.joining("\n"));
            throw new ObfuscationException("Failed to parse " + origin + ":\n" + problems);
        }
        return result;
    }

    /**
     * Comments before the package declaration (typically a license header) are
     * always kept. Files in the default package have no package line; there,
     * comments before the first import or type are kept.
     */
    private List<Comment> commentsToStrip(ParseResult<CompilationUnit> result, CompilationUnit cu) {
        if (!stripComments) {
            return Collections.emptyList();
        }
        Optional<Position> header = cu.getPackageDeclaration().flatMap(Node::getBegin);
        if (header.isEmpty()) {
            header = Stream.concat(cu.getImports().stream(), cu.getTypes().stream())
                    .map(Node::getBegin).flatMap(Optional::stream).min(Position::compareTo);
        }
        List<Comment> strip = new ArrayList<>();
        for (Comment c : result.getCommentsCollection().map(cc -> cc.getComments())
                .orElseGet(TreeSet::new)) {
            Optional<Position> end = c.getEnd();
            if (end.isEmpty() || (header.isPresent() && end.get().isBefore(header.get()))) {
                continue;
            }
            strip.add(c);
        }
        return strip;
    }

    private String rename(String original) {
        String obf = hasher.obfuscate(original);
        String previous = obfuscatedToOriginal.putIfAbsent(obf, original);
        if (previous != null && !previous.equals(original)) {
            throw new ObfuscationException("Hash collision: '" + previous + "' and '" + original + "' both map to "
                    + obf + ". Use a different salt or a longer hash length.");
        }
        return obf;
    }

    private boolean isExcluded(String name) {
        return excluded.contains(name);
    }

    boolean isExcludedIdentifier(String name) {
        return isExcluded(name);
    }

    static boolean hasSignificantAnnotation(NodeList<AnnotationExpr> annotations) {
        for (AnnotationExpr a : annotations) {
            if (!NEUTRAL_ANNOTATIONS.contains(a.getName().getIdentifier())) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsIdentity(List<? extends Node> list, Node n) {
        for (Node x : list) {
            if (x == n) {
                return true;
            }
        }
        return false;
    }

    /**
     * Collects edits by source position and applies them to the original text.
     *
     * <p>An identifier edit targets the last {@code identifier} characters of a
     * node's range, and checks that they really spell the identifier.
     *
     * <p>A removed comment is replaced by the line breaks it contained, so line
     * numbers do not change. Horizontal whitespace next to it is removed as
     * well, so that no trailing blanks are left behind; a single space is kept
     * if the comment separated two tokens on the same line.
     */
    private final class SourceEditor {

        private final String source;
        private final String origin;
        private final int[] lineStarts;
        private final List<Edit> identifierEdits = new ArrayList<>();
        private final List<int[]> commentSpans = new ArrayList<>();

        SourceEditor(String source, String origin) {
            this.source = source;
            this.origin = origin;
            List<Integer> starts = new ArrayList<>();
            starts.add(0);
            for (int i = 0; i < source.length(); i++) {
                char c = source.charAt(i);
                if (c == '\n' || (c == '\r' && (i + 1 == source.length() || source.charAt(i + 1) != '\n'))) {
                    starts.add(i + 1);
                }
            }
            lineStarts = starts.stream().mapToInt(Integer::intValue).toArray();
        }

        void replaceIdentifierAt(Node node, String identifier) {
            Position end = node.getEnd().orElseThrow(() ->
                    new ObfuscationException("No source position for '" + identifier + "' in " + origin));
            int endOffset = offset(end) + 1;
            int start = endOffset - identifier.length();
            if (start < 0 || !source.startsWith(identifier, start)) {
                throw new ObfuscationException("Cannot locate '" + identifier + "' at line " + end.line + " in "
                        + origin + "; the file may use Unicode escapes in identifiers");
            }
            identifierEdits.add(new Edit(start, endOffset, rename(identifier)));
        }

        void removeComment(Comment comment) {
            int start = offset(comment.getBegin().get());
            int end = offset(comment.getEnd().get()) + 1;
            if (start < 0 || end > source.length() || source.charAt(start) != '/') {
                throw new ObfuscationException("Cannot locate a comment at line " + comment.getBegin().get().line
                        + " in " + origin);
            }
            commentSpans.add(new int[] {start, end});
        }

        String apply() {
            List<Edit> edits = new java.util.ArrayList<>(identifierEdits);
            edits.addAll(commentEdits());
            edits.sort((a, b) -> Integer.compare(b.start, a.start));
            StringBuilder sb = new StringBuilder(source);
            for (Edit e : edits) {
                sb.replace(e.start, e.end, e.text);
            }
            return sb.toString();
        }

        private List<Edit> commentEdits() {
            commentSpans.sort((a, b) -> Integer.compare(a[0], b[0]));
            List<int[]> merged = new ArrayList<>();
            for (int[] span : commentSpans) {
                int e = span[1];
                while (e < source.length() && isBlank(source.charAt(e))) {
                    e++;
                }
                int[] last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
                if (last != null && span[0] <= last[1]) {
                    last[1] = Math.max(last[1], e);
                } else {
                    merged.add(new int[] {span[0], e});
                }
            }
            List<Edit> edits = new ArrayList<>();
            for (int[] span : merged) {
                // Nothing but blanks left after the comment(s): drop the indentation too.
                if (span[1] == source.length() || isLineBreak(source.charAt(span[1]))) {
                    while (span[0] > 0 && isBlank(source.charAt(span[0] - 1))) {
                        span[0]--;
                    }
                }
                StringBuilder breaks = new StringBuilder();
                for (int i = span[0]; i < span[1]; i++) {
                    if (isLineBreak(source.charAt(i))) {
                        breaks.append(source.charAt(i));
                    }
                }
                String text = breaks.toString();
                if (text.isEmpty() && span[0] > 0 && span[1] < source.length()
                        && !Character.isWhitespace(source.charAt(span[0] - 1))
                        && !Character.isWhitespace(source.charAt(span[1]))) {
                    text = " "; // e.g. int/*c*/x must not become intx
                }
                edits.add(new Edit(span[0], span[1], text));
            }
            return edits;
        }

        private int offset(Position p) {
            return lineStarts[p.line - 1] + p.column - 1;
        }

        private boolean isBlank(char c) {
            return c == ' ' || c == '\t' || c == '\f';
        }

        private boolean isLineBreak(char c) {
            return c == '\n' || c == '\r';
        }
    }

    private static final class Edit {
        final int start;
        final int end;
        final String text;

        Edit(int start, int end, String text) {
            this.start = start;
            this.end = end;
            this.text = text;
        }
    }

    /** Analysis and renaming of a single compilation unit. */
    private final class Pass {

        private final CompilationUnit cu;
        private final String pkg;
        private final Set<String> classNames;
        private final Map<String, List<TypeDeclaration<?>>> typesBySimpleName = new HashMap<>();
        private final Set<String> unsafeFieldNames = new HashSet<>();
        private final Set<String> unsafeMethodNames = new HashSet<>();
        private final Map<String, Boolean> renamableMethodNames = new HashMap<>();
        private final Set<SimpleName> toRename = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Set<MethodReferenceExpr> methodRefsToRename = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Set<Name> qualifiedNamesToRename = Collections.newSetFromMap(new IdentityHashMap<>());

        Pass(CompilationUnit cu, Set<String> classNames) {
            this.cu = cu;
            this.pkg = ClassRenamePlanner.packageOf(cu);
            this.classNames = classNames;
        }

        String run(String source, String origin, List<Comment> commentsToStrip) {
            for (TypeDeclaration<?> t : cu.findAll(TypeDeclaration.class)) {
                typesBySimpleName.computeIfAbsent(t.getNameAsString(), k -> new ArrayList<>()).add(t);
            }
            collectUnsafeNames();
            collectDeclarations();
            collectUses();
            collectClassRenames();
            // The AST is never modified: new names are spliced into the original
            // text, which preserves formatting (and thus line numbers) exactly.
            SourceEditor editor = new SourceEditor(source, origin);
            for (SimpleName n : toRename) {
                editor.replaceIdentifierAt(n, n.getIdentifier());
            }
            // For these nodes the identifier is the last token of the node's range.
            for (MethodReferenceExpr r : methodRefsToRename) {
                editor.replaceIdentifierAt(r, r.getIdentifier());
            }
            for (Name n : qualifiedNamesToRename) {
                editor.replaceIdentifierAt(n, n.getIdentifier());
            }
            for (Comment c : commentsToStrip) {
                editor.removeComment(c);
            }
            return editor.apply();
        }

        // ------------------------------------------------- package-private classes

        /**
         * Renames the planned package-private top-level classes: declarations,
         * constructors and every reference by simple name or by name qualified
         * with this package. The planner has already ruled out names that are
         * shadowed or have another meaning in the package, so a simple name
         * matching a planned class always refers to it.
         */
        private void collectClassRenames() {
            if (classNames.isEmpty()) {
                return;
            }
            for (TypeDeclaration<?> t : cu.getTypes()) {
                if (classNames.contains(t.getNameAsString())) {
                    toRename.add(t.getName());
                    t.getConstructors().forEach(c -> toRename.add(c.getName()));
                    t.findAll(CompactConstructorDeclaration.class, c -> c.getParentNode().orElse(null) == t)
                            .forEach(c -> toRename.add(c.getName()));
                }
            }
            for (ClassOrInterfaceType t : cu.findAll(ClassOrInterfaceType.class)) {
                if (classNames.contains(t.getNameAsString())
                        && (t.getScope().isEmpty() || pkg.equals(dottedType(t.getScope().get())))) {
                    toRename.add(t.getName());
                }
            }
            for (NameExpr ne : cu.findAll(NameExpr.class)) {
                if (classNames.contains(ne.getNameAsString())) {
                    toRename.add(ne.getName());
                }
            }
            for (FieldAccessExpr fa : cu.findAll(FieldAccessExpr.class)) {
                if (classNames.contains(fa.getNameAsString()) && !pkg.isEmpty()
                        && pkg.equals(dottedExpr(fa.getScope()))) {
                    toRename.add(fa.getName());
                }
            }
            // Names in imports, annotations and Outer.this / Outer.super.
            for (Name n : cu.findAll(Name.class)) {
                if (!classNames.contains(n.getIdentifier()) || n.findAncestor(PackageDeclaration.class).isPresent()) {
                    continue;
                }
                boolean matches = n.getQualifier().isPresent()
                        ? pkg.equals(n.getQualifier().get().asString())
                        : n.findAncestor(ImportDeclaration.class).isEmpty();
                if (matches) {
                    qualifiedNamesToRename.add(n);
                }
            }
        }

        private String dottedType(ClassOrInterfaceType t) {
            return t.getScope().isPresent() ? dottedType(t.getScope().get()) + "." + t.getNameAsString()
                    : t.getNameAsString();
        }

        private String dottedExpr(Expression e) {
            if (e instanceof NameExpr) {
                return ((NameExpr) e).getNameAsString();
            }
            if (e instanceof FieldAccessExpr) {
                String scope = dottedExpr(((FieldAccessExpr) e).getScope());
                return scope == null ? null : scope + "." + ((FieldAccessExpr) e).getNameAsString();
            }
            return null;
        }

        // ---------------------------------------------------------------- analysis

        /**
         * A member name accessed through an expression we cannot attribute to a
         * type in this file (e.g. {@code other.x}) might refer to one of our
         * private members, so such names are never renamed.
         */
        private void collectUnsafeNames() {
            for (FieldAccessExpr fa : cu.findAll(FieldAccessExpr.class)) {
                if (targetContainer(fa.getScope(), fa) == null) {
                    unsafeFieldNames.add(fa.getNameAsString());
                }
            }
            for (MethodCallExpr mc : cu.findAll(MethodCallExpr.class)) {
                if (mc.getScope().isPresent() && targetContainer(mc.getScope().get(), mc) == null) {
                    unsafeMethodNames.add(mc.getNameAsString());
                }
            }
            for (MethodReferenceExpr mr : cu.findAll(MethodReferenceExpr.class)) {
                if (methodRefContainer(mr) == null) {
                    unsafeMethodNames.add(mr.getIdentifier());
                }
            }
        }

        private void collectDeclarations() {
            for (VariableDeclarator v : cu.findAll(VariableDeclarator.class)) {
                if (shouldRename(v)) {
                    toRename.add(v.getName());
                }
            }
            for (Parameter p : cu.findAll(Parameter.class)) {
                if (shouldRename(p)) {
                    toRename.add(p.getName());
                }
            }
            for (TypePatternExpr tp : cu.findAll(TypePatternExpr.class)) {
                if (shouldRename(tp)) {
                    toRename.add(tp.getName());
                }
            }
            for (MethodDeclaration md : cu.findAll(MethodDeclaration.class)) {
                if (isRenamableMethodName(md.getNameAsString())) {
                    toRename.add(md.getName());
                }
            }
        }

        private void collectUses() {
            for (NameExpr ne : cu.findAll(NameExpr.class)) {
                Node decl = resolveVariable(ne, ne.getNameAsString());
                if (decl != null && shouldRename(decl)) {
                    toRename.add(ne.getName());
                }
            }
            for (FieldAccessExpr fa : cu.findAll(FieldAccessExpr.class)) {
                Node container = targetContainer(fa.getScope(), fa);
                if (container != null) {
                    Node decl = findField(container, fa.getNameAsString());
                    if (decl != null && shouldRename(decl)) {
                        toRename.add(fa.getName());
                    }
                }
            }
            for (MethodCallExpr mc : cu.findAll(MethodCallExpr.class)) {
                String name = mc.getNameAsString();
                if (!isRenamableMethodName(name)) {
                    continue;
                }
                Node container = mc.getScope().isPresent()
                        ? targetContainer(mc.getScope().get(), mc)
                        : enclosingContainerDeclaringMethod(mc, name);
                if (container != null && declaresMethod(container, name)) {
                    toRename.add(mc.getName());
                }
            }
            for (MethodReferenceExpr mr : cu.findAll(MethodReferenceExpr.class)) {
                String name = mr.getIdentifier();
                if (!isRenamableMethodName(name)) {
                    continue;
                }
                Node container = methodRefContainer(mr);
                if (container != null && declaresMethod(container, name)) {
                    methodRefsToRename.add(mr);
                }
            }
        }

        // ------------------------------------------------------- rename decisions

        private boolean shouldRename(Node decl) {
            if (decl instanceof VariableDeclarator) {
                VariableDeclarator v = (VariableDeclarator) decl;
                String name = v.getNameAsString();
                if (isExcluded(name)) {
                    return false;
                }
                Node parent = v.getParentNode().orElse(null);
                if (parent instanceof FieldDeclaration) {
                    FieldDeclaration fd = (FieldDeclaration) parent;
                    return fd.isPrivate() && !unsafeFieldNames.contains(name)
                            && !hasSignificantAnnotation(fd.getAnnotations());
                }
                if (parent instanceof VariableDeclarationExpr) {
                    return !hasSignificantAnnotation(((VariableDeclarationExpr) parent).getAnnotations());
                }
                return false;
            }
            if (decl instanceof Parameter) {
                Parameter p = (Parameter) decl;
                if (isExcluded(p.getNameAsString()) || hasSignificantAnnotation(p.getAnnotations())) {
                    return false;
                }
                Node parent = p.getParentNode().orElse(null);
                if (parent instanceof RecordDeclaration) {
                    return false; // record component: part of the public API
                }
                if (parent instanceof ConstructorDeclaration
                        && parent.getParentNode().orElse(null) instanceof RecordDeclaration) {
                    return false; // canonical constructor parameters must match component names
                }
                return true;
            }
            if (decl instanceof TypePatternExpr) {
                return !isExcluded(((TypePatternExpr) decl).getNameAsString());
            }
            return false;
        }

        private boolean isRenamableMethodName(String name) {
            return renamableMethodNames.computeIfAbsent(name, n -> {
                if (isExcluded(n) || unsafeMethodNames.contains(n)) {
                    return false;
                }
                List<MethodDeclaration> decls = cu.findAll(MethodDeclaration.class,
                        md -> md.getNameAsString().equals(n));
                if (decls.isEmpty()) {
                    return false;
                }
                // Any non-private overload means the name is (also) API or an override.
                for (MethodDeclaration md : decls) {
                    if (!md.isPrivate() || hasSignificantAnnotation(md.getAnnotations())) {
                        return false;
                    }
                }
                return true;
            });
        }

        // ------------------------------------------------------- name resolution

        /**
         * Lexically resolves a simple variable name used at {@code use}.
         *
         * @return the declaring node (VariableDeclarator, Parameter,
         *         TypePatternExpr or EnumConstantDeclaration), or null if the
         *         name is not declared in this file (inherited, static import...)
         */
        private Node resolveVariable(Node use, String name) {
            Node child = use;
            Node parent = use.getParentNode().orElse(null);
            while (parent != null) {
                Node decl = declaredIn(parent, child, name);
                if (decl != null) {
                    return decl;
                }
                child = parent;
                parent = parent.getParentNode().orElse(null);
            }
            return null;
        }

        private Node declaredIn(Node parent, Node child, String name) {
            if (isContainerBoundary(parent, child)) {
                return findField(parent, name);
            }
            if (parent instanceof BlockStmt) {
                return declaredBefore(((BlockStmt) parent).getStatements(), child, name);
            }
            if (parent instanceof SwitchEntry) {
                SwitchEntry entry = (SwitchEntry) parent;
                if (!containsIdentity(entry.getStatements(), child)) {
                    return null;
                }
                Node d = declaredBefore(entry.getStatements(), child, name);
                if (d == null && entry.getParentNode().orElse(null) instanceof SwitchNode) {
                    // Old-style switch groups share one block scope.
                    for (SwitchEntry previous : ((SwitchNode) entry.getParentNode().get()).getEntries()) {
                        if (previous == entry) {
                            break;
                        }
                        d = declaredBefore(previous.getStatements(), null, name);
                        if (d != null) {
                            break;
                        }
                    }
                }
                return d;
            }
            if (parent instanceof VariableDeclarationExpr) {
                return findDeclarator(((VariableDeclarationExpr) parent).getVariables(), name);
            }
            if (parent instanceof ForStmt) {
                for (Expression init : ((ForStmt) parent).getInitialization()) {
                    if (init instanceof VariableDeclarationExpr) {
                        Node d = findDeclarator(((VariableDeclarationExpr) init).getVariables(), name);
                        if (d != null) {
                            return d;
                        }
                    }
                }
                return null;
            }
            if (parent instanceof ForEachStmt) {
                return findDeclarator(((ForEachStmt) parent).getVariable().getVariables(), name);
            }
            if (parent instanceof TryStmt) {
                for (Expression res : ((TryStmt) parent).getResources()) {
                    if (res instanceof VariableDeclarationExpr) {
                        Node d = findDeclarator(((VariableDeclarationExpr) res).getVariables(), name);
                        if (d != null) {
                            return d;
                        }
                    }
                }
                return null;
            }
            if (parent instanceof CatchClause) {
                Parameter p = ((CatchClause) parent).getParameter();
                return p.getNameAsString().equals(name) ? p : null;
            }
            if (parent instanceof CallableDeclaration || parent instanceof LambdaExpr) {
                NodeList<Parameter> params = parent instanceof LambdaExpr
                        ? ((LambdaExpr) parent).getParameters()
                        : ((CallableDeclaration<?>) parent).getParameters();
                for (Parameter p : params) {
                    if (p.getNameAsString().equals(name)) {
                        return p;
                    }
                }
                return findPattern(parent, name);
            }
            if (parent instanceof InitializerDeclaration || parent instanceof CompactConstructorDeclaration) {
                return findPattern(parent, name);
            }
            return null;
        }

        /**
         * Pattern variables have flow-sensitive scope; approximating it with the
         * whole enclosing callable is safe because locals are always renamed.
         */
        private Node findPattern(Node scope, String name) {
            List<TypePatternExpr> found = scope.findAll(TypePatternExpr.class,
                    tp -> tp.getNameAsString().equals(name));
            return found.isEmpty() ? null : found.get(0);
        }

        private Node declaredBefore(List<Statement> statements, Node stop, String name) {
            for (Statement s : statements) {
                if (s == stop) {
                    break;
                }
                if (s instanceof ExpressionStmt && ((ExpressionStmt) s).getExpression() instanceof VariableDeclarationExpr) {
                    VariableDeclarationExpr vde = (VariableDeclarationExpr) ((ExpressionStmt) s).getExpression();
                    Node d = findDeclarator(vde.getVariables(), name);
                    if (d != null) {
                        return d;
                    }
                }
            }
            return null;
        }

        private Node findDeclarator(List<VariableDeclarator> vars, String name) {
            for (VariableDeclarator v : vars) {
                if (v.getNameAsString().equals(name)) {
                    return v;
                }
            }
            return null;
        }

        // ------------------------------------------------------------ containers
        // A "container" is a node holding members: a type declaration, the body
        // of an anonymous class, or the body of an enum constant.

        private boolean isContainerBoundary(Node parent, Node child) {
            if (parent instanceof TypeDeclaration) {
                return true;
            }
            if (parent instanceof ObjectCreationExpr) {
                Optional<NodeList<BodyDeclaration<?>>> body = ((ObjectCreationExpr) parent).getAnonymousClassBody();
                return body.isPresent() && containsIdentity(body.get(), child);
            }
            if (parent instanceof EnumConstantDeclaration) {
                return containsIdentity(((EnumConstantDeclaration) parent).getClassBody(), child);
            }
            return false;
        }

        private List<BodyDeclaration<?>> membersOf(Node container) {
            if (container instanceof TypeDeclaration) {
                return ((TypeDeclaration<?>) container).getMembers();
            }
            if (container instanceof ObjectCreationExpr) {
                return ((ObjectCreationExpr) container).getAnonymousClassBody().orElseGet(NodeList::new);
            }
            if (container instanceof EnumConstantDeclaration) {
                return ((EnumConstantDeclaration) container).getClassBody();
            }
            return Collections.emptyList();
        }

        private Node findField(Node container, String name) {
            if (container instanceof RecordDeclaration) {
                for (Parameter p : ((RecordDeclaration) container).getParameters()) {
                    if (p.getNameAsString().equals(name)) {
                        return p;
                    }
                }
            }
            if (container instanceof EnumDeclaration) {
                for (EnumConstantDeclaration ec : ((EnumDeclaration) container).getEntries()) {
                    if (ec.getNameAsString().equals(name)) {
                        return ec;
                    }
                }
            }
            for (BodyDeclaration<?> m : membersOf(container)) {
                if (m instanceof FieldDeclaration) {
                    Node d = findDeclarator(((FieldDeclaration) m).getVariables(), name);
                    if (d != null) {
                        return d;
                    }
                }
            }
            return null;
        }

        private boolean declaresMethod(Node container, String name) {
            for (BodyDeclaration<?> m : membersOf(container)) {
                if (m instanceof MethodDeclaration && ((MethodDeclaration) m).getNameAsString().equals(name)) {
                    return true;
                }
            }
            return false;
        }

        private Node enclosingContainer(Node n) {
            Node child = n;
            Node parent = n.getParentNode().orElse(null);
            while (parent != null) {
                if (isContainerBoundary(parent, child)) {
                    return parent;
                }
                child = parent;
                parent = parent.getParentNode().orElse(null);
            }
            return null;
        }

        private Node enclosingContainerDeclaringMethod(Node n, String name) {
            Node c = enclosingContainer(n);
            while (c != null) {
                if (declaresMethod(c, name)) {
                    return c;
                }
                c = enclosingContainer(c);
            }
            return null;
        }

        /**
         * The container a qualified member access refers to: {@code this},
         * {@code Outer.this}, or {@code Outer} naming a type declared in this
         * file. Null when the target cannot be attributed to this file.
         */
        private Node targetContainer(Expression scope, Node use) {
            if (scope instanceof ThisExpr) {
                ThisExpr t = (ThisExpr) scope;
                if (t.getTypeName().isPresent()) {
                    return uniqueType(t.getTypeName().get().getIdentifier());
                }
                return enclosingContainer(use);
            }
            if (scope instanceof NameExpr) {
                NameExpr ne = (NameExpr) scope;
                if (resolveVariable(ne, ne.getNameAsString()) == null) {
                    return uniqueType(ne.getNameAsString());
                }
            }
            return null;
        }

        private Node methodRefContainer(MethodReferenceExpr mr) {
            Expression scope = mr.getScope();
            if (scope instanceof ThisExpr) {
                return targetContainer(scope, mr);
            }
            if (scope instanceof TypeExpr && ((TypeExpr) scope).getType() instanceof ClassOrInterfaceType) {
                ClassOrInterfaceType t = (ClassOrInterfaceType) ((TypeExpr) scope).getType();
                if (t.getScope().isEmpty()) {
                    return uniqueType(t.getNameAsString());
                }
            }
            if (scope instanceof NameExpr) {
                return targetContainer(scope, mr);
            }
            return null;
        }

        private Node uniqueType(String simpleName) {
            List<TypeDeclaration<?>> types = typesBySimpleName.get(simpleName);
            return types != null && types.size() == 1 ? types.get(0) : null;
        }
    }
}
