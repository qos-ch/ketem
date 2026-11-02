package ch.qos.ketem.core;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Package-private top-level classes chosen for renaming, as decided by
 * {@link ClassRenamePlanner} after looking at every file in their package.
 */
public final class ClassRenamePlan {

    public static final ClassRenamePlan NONE =
            new ClassRenamePlan(Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap());

    private final Map<String, Set<String>> namesByPackage;
    private final Map<Path, String> renamedPrimaryTypes;
    private final Map<String, String> skipped;

    ClassRenamePlan(Map<String, Set<String>> namesByPackage, Map<Path, String> renamedPrimaryTypes,
            Map<String, String> skipped) {
        this.namesByPackage = namesByPackage;
        this.renamedPrimaryTypes = renamedPrimaryTypes;
        this.skipped = skipped;
    }

    /** Simple names of the classes to rename in the given package. */
    public Set<String> namesFor(String pkg) {
        return namesByPackage.getOrDefault(pkg, Collections.emptySet());
    }

    /**
     * The original name of the renamed class that gives {@code sourceFile} its
     * name, if any. Such files are written under the obfuscated name.
     */
    public Optional<String> renamedPrimaryType(Path sourceFile) {
        return Optional.ofNullable(renamedPrimaryTypes.get(sourceFile));
    }

    public int renamedCount() {
        int n = 0;
        for (Set<String> names : namesByPackage.values()) {
            n += names.size();
        }
        return n;
    }

    /** Package-private classes that were eligible but kept, mapped to the reason. */
    public Map<String, String> getSkipped() {
        return skipped;
    }
}
