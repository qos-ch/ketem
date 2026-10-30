package ch.qos.ketem.core;

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

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.Problem;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
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
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
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
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;

/**
 * Obfuscates identifiers whose scope is confined to a single compilation unit:
 * local variables, parameters, pattern variables, private fields and private
 * methods. Public and protected API, class names and package names are left
 * untouched, so each file can be processed independently and the rest of the
 * build (and its consumers) keeps compiling.
 *
 * <p>Formatting is preserved via JavaParser's lexical preserving printer, so
 * line numbers in stack traces still point at the original source lines.
 *
 * <p>Name resolution is purely syntactic (no classpath), so the obfuscator is
 * deliberately conservative: a member is renamed only when every reference to
 * it within the file can be attributed to its declaration. One known blind
 * spot: a nested or anonymous class that <em>inherits</em> a field or method
 * from an external supertype with the same simple name as a private member of
 * the enclosing class. Add such names to the excluded identifiers.
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

    public Obfuscator(IdentifierHasher hasher) {
        this(hasher, Collections.emptySet(), LanguageLevel.JAVA_21);
    }

    public Obfuscator(IdentifierHasher hasher, Collection<String> excludedIdentifiers, LanguageLevel languageLevel) {
        this.hasher = hasher;
        this.excluded = new HashSet<>(DEFAULT_EXCLUDED_IDENTIFIERS);
        if (excludedIdentifiers != null) {
            this.excluded.addAll(excludedIdentifiers);
        }
        this.parser = new JavaParser(new ParserConfiguration().setLanguageLevel(languageLevel));
    }

    public JavaParser getParser() {
        return parser;
    }

    /** All original identifiers renamed so far by this instance. */
    public Set<String> getRenamedIdentifiers() {
        return new TreeSet<>(obfuscatedToOriginal.values());
    }

    /**
     * @param source   Java source text
     * @param origin   used in error messages only, e.g. a file path
     * @return the obfuscated source
     */
    public String obfuscate(String source, String origin) {
        ParseResult<CompilationUnit> result = parser.parse(source);
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            String problems = result.getProblems().stream().map(Problem::getVerboseMessage)
                    .collect(Collectors.joining("\n"));
            throw new ObfuscationException("Failed to parse " + origin + ":\n" + problems);
        }
        CompilationUnit cu = result.getResult().get();
        LexicalPreservingPrinter.setup(cu);
        new Pass(cu).run();
        return LexicalPreservingPrinter.print(cu);
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

    private static boolean hasSignificantAnnotation(NodeList<AnnotationExpr> annotations) {
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

    /** Analysis and renaming of a single compilation unit. */
    private final class Pass {

        private final CompilationUnit cu;
        private final Map<String, List<TypeDeclaration<?>>> typesBySimpleName = new HashMap<>();
        private final Set<String> unsafeFieldNames = new HashSet<>();
        private final Set<String> unsafeMethodNames = new HashSet<>();
        private final Map<String, Boolean> renamableMethodNames = new HashMap<>();
        private final Set<SimpleName> toRename = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Set<MethodReferenceExpr> methodRefsToRename = Collections.newSetFromMap(new IdentityHashMap<>());

        Pass(CompilationUnit cu) {
            this.cu = cu;
        }

        void run() {
            for (TypeDeclaration<?> t : cu.findAll(TypeDeclaration.class)) {
                typesBySimpleName.computeIfAbsent(t.getNameAsString(), k -> new java.util.ArrayList<>()).add(t);
            }
            collectUnsafeNames();
            collectDeclarations();
            collectUses();
            // Mutate only after all analysis is done; resolution relies on original names.
            for (SimpleName n : toRename) {
                n.setIdentifier(rename(n.getIdentifier()));
            }
            for (MethodReferenceExpr r : methodRefsToRename) {
                r.setIdentifier(rename(r.getIdentifier()));
            }
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
