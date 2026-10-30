package ch.qos.ketem.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Decides which compilation units are obfuscated, based on fully qualified
 * names of their primary type.
 *
 * <ul>
 * <li>A package entry matches that package and all of its sub-packages.</li>
 * <li>A class entry matches exactly that fully qualified class name.</li>
 * <li>An exclude entry matches either a class name exactly, or a package and
 * its sub-packages.</li>
 * </ul>
 * If no package and no class is given, every compilation unit is included
 * (subject to the excludes).
 */
public final class SourceSelector {

    private final List<String> packages;
    private final List<String> classes;
    private final List<String> excludes;

    public SourceSelector(Collection<String> packages, Collection<String> classes, Collection<String> excludes) {
        this.packages = clean(packages);
        this.classes = clean(classes);
        this.excludes = clean(excludes);
    }

    public static SourceSelector all() {
        return new SourceSelector(null, null, null);
    }

    /**
     * @param fqcn fully qualified name of the compilation unit's primary type,
     *             e.g. {@code com.example.Foo}, derived from its path
     */
    public boolean isSelected(String fqcn) {
        String pkg = packageOf(fqcn);
        boolean included = packages.isEmpty() && classes.isEmpty();
        for (String p : packages) {
            if (inPackage(pkg, p)) {
                included = true;
                break;
            }
        }
        if (!included && classes.contains(fqcn)) {
            included = true;
        }
        if (!included) {
            return false;
        }
        for (String e : excludes) {
            if (fqcn.equals(e) || inPackage(pkg, e)) {
                return false;
            }
        }
        return true;
    }

    private static boolean inPackage(String pkg, String prefix) {
        return pkg.equals(prefix) || pkg.startsWith(prefix + ".");
    }

    private static String packageOf(String fqcn) {
        int i = fqcn.lastIndexOf('.');
        return i < 0 ? "" : fqcn.substring(0, i);
    }

    private static List<String> clean(Collection<String> in) {
        List<String> out = new ArrayList<>();
        if (in != null) {
            for (String s : in) {
                if (s != null && !s.isBlank()) {
                    out.add(s.trim());
                }
            }
        }
        return out;
    }
}
