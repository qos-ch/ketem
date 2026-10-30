package ch.qos.ketem.maven;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import com.github.javaparser.ParserConfiguration.LanguageLevel;

import ch.qos.ketem.core.IdentifierDictionary;
import ch.qos.ketem.core.IdentifierHasher;
import ch.qos.ketem.core.JavaLanguageLevels;
import ch.qos.ketem.core.ObfuscationException;
import ch.qos.ketem.core.Obfuscator;
import ch.qos.ketem.core.SourceSelector;
import ch.qos.ketem.core.SourceTreeObfuscator;

/**
 * Obfuscates the project's main sources into {@code outputDirectory} and
 * replaces the compile source roots with it, so that {@code compile} (and any
 * later source packaging) sees only the obfuscated sources.
 *
 * <p>Runs in {@code process-sources} so that sources generated during
 * {@code generate-sources} are included.
 */
@Mojo(name = "obfuscate", defaultPhase = LifecyclePhase.PROCESS_SOURCES, threadSafe = true)
public class ObfuscateMojo extends AbstractMojo {

    private static final LanguageLevel DEFAULT_LANGUAGE_LEVEL = LanguageLevel.JAVA_21;
    private static final int WEAK_SALT_MAX_LENGTH = 4;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /**
     * Salt mixed into every hash. Keep it secret and stable: it is needed to
     * retrace stack traces. Prefer passing it via {@code -Dketem.salt} or a
     * settings.xml profile rather than committing it to the POM.
     */
    @Parameter(property = "ketem.salt", required = true)
    private String salt;

    /** Packages (including sub-packages) to obfuscate. */
    @Parameter
    private List<String> packages;

    /** Fully qualified class names (by source file) to obfuscate. */
    @Parameter
    private List<String> classes;

    /** Packages (including sub-packages) or fully qualified classes to leave untouched. */
    @Parameter
    private List<String> excludes;

    /** Identifiers that must never be renamed, e.g. names used reflectively. */
    @Parameter
    private List<String> excludedIdentifiers;

    /** Number of hex digits of the MD5 hash kept in obfuscated names. */
    @Parameter(property = "ketem.hashLength", defaultValue = "12")
    private int hashLength;

    /**
     * JavaParser language level used for parsing, e.g. {@code JAVA_17}. When
     * unset, it is derived from {@code maven.compiler.release}, otherwise from
     * {@code maven.compiler.source}, otherwise {@code JAVA_21}.
     */
    @Parameter(property = "ketem.languageLevel")
    private String languageLevel;

    @Parameter(defaultValue = "${project.build.directory}/ketem/src", required = true)
    private File outputDirectory;

    /**
     * Original identifiers that were renamed; combine with the salt to retrace.
     * Written outside {@code target/} so that {@code mvn clean} keeps it. It must
     * not be published or committed: add {@code private/} to your VCS ignore list.
     */
    @Parameter(property = "ketem.dictionaryFile",
            defaultValue = "${project.basedir}/private/ketem-identifiers-${project.version}.txt", required = true)
    private File dictionaryFile;

    @Parameter(defaultValue = "${project.build.sourceEncoding}")
    private String encoding;

    @Parameter(property = "ketem.skip", defaultValue = "false")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Ketem obfuscation skipped");
            return;
        }
        LanguageLevel level = resolveLanguageLevel();
        IdentifierHasher hasher;
        try {
            hasher = new IdentifierHasher(salt, hashLength);
        } catch (IllegalArgumentException e) {
            throw new MojoFailureException(e.getMessage());
        }
        if (salt.codePointCount(0, salt.length()) <= WEAK_SALT_MAX_LENGTH) {
            getLog().warn("The Ketem salt is " + WEAK_SALT_MAX_LENGTH + " characters or less, which offers "
                    + "little protection. Use a long random value kept in a secret store.");
        }
        Charset charset = encoding == null || encoding.isEmpty() ? Charset.defaultCharset() : Charset.forName(encoding);

        Path out = outputDirectory.toPath().toAbsolutePath().normalize();
        List<Path> roots = new ArrayList<>();
        for (String r : project.getCompileSourceRoots()) {
            Path p = new File(r).toPath().toAbsolutePath().normalize();
            if (!p.equals(out)) {
                roots.add(p);
            }
        }

        Obfuscator obfuscator = new Obfuscator(hasher, excludedIdentifiers, level);
        SourceTreeObfuscator tree = new SourceTreeObfuscator(obfuscator,
                new SourceSelector(packages, classes, excludes), charset);
        try {
            deleteRecursively(out);
            tree.process(roots, out);
            new IdentifierDictionary(obfuscator.getRenamedIdentifiers()).write(dictionaryFile.toPath());
        } catch (ObfuscationException e) {
            throw new MojoFailureException(e.getMessage(), e);
        } catch (IOException e) {
            throw new MojoExecutionException("Obfuscation failed", e);
        }

        // Maven 3 exposes the live list; replace the original roots by the obfuscated one.
        project.getCompileSourceRoots().clear();
        project.addCompileSourceRoot(out.toString());

        getLog().info("Ketem obfuscated " + tree.getObfuscatedCount() + " file(s), copied "
                + tree.getCopiedCount() + " other file(s), renamed "
                + obfuscator.getRenamedIdentifiers().size() + " distinct identifier(s)");
        getLog().info("Identifier dictionary: " + dictionaryFile);
    }

    private LanguageLevel resolveLanguageLevel() throws MojoFailureException {
        if (languageLevel != null && !languageLevel.isBlank()) {
            try {
                return LanguageLevel.valueOf(languageLevel.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new MojoFailureException("Unknown languageLevel '" + languageLevel + "'");
            }
        }
        for (String property : new String[] {"maven.compiler.release", "maven.compiler.source"}) {
            String version = project.getProperties().getProperty(property);
            if (version != null && !version.isBlank()) {
                try {
                    LanguageLevel level = JavaLanguageLevels.forJavaVersion(version);
                    getLog().info("Parsing sources as " + level + " (from " + property + "=" + version.trim() + ")");
                    return level;
                } catch (IllegalArgumentException e) {
                    throw new MojoFailureException("Cannot derive languageLevel from " + property + ": "
                            + e.getMessage() + ". Set <languageLevel> explicitly.");
                }
            }
        }
        getLog().info("Parsing sources as " + DEFAULT_LANGUAGE_LEVEL
                + " (neither maven.compiler.release nor maven.compiler.source is set)");
        return DEFAULT_LANGUAGE_LEVEL;
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) s.sorted(Comparator.reverseOrder())::iterator) {
                Files.delete(p);
            }
        }
    }
}
