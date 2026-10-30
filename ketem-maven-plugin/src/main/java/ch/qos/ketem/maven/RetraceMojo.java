package ch.qos.ketem.maven;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import ch.qos.ketem.core.IdentifierDictionary;
import ch.qos.ketem.core.IdentifierHasher;
import ch.qos.ketem.core.StackTraceDeobfuscator;

/**
 * De-obfuscates a stack trace file using the salt and the identifier
 * dictionary, e.g. {@code mvn ketem:retrace -Dketem.salt=... -Dketem.trace=crash.txt}.
 */
@Mojo(name = "retrace", requiresProject = false, threadSafe = true)
public class RetraceMojo extends AbstractMojo {

    @Parameter(property = "ketem.salt", required = true)
    private String salt;

    @Parameter(property = "ketem.hashLength", defaultValue = "12")
    private int hashLength;

    @Parameter(property = "ketem.dictionaryFile",
            defaultValue = "${project.basedir}/private/ketem-identifiers-${project.version}.txt", required = true)
    private File dictionaryFile;

    /** File containing the obfuscated stack trace. */
    @Parameter(property = "ketem.trace", required = true)
    private File trace;

    /** Optional output file; the result is logged when absent. */
    @Parameter(property = "ketem.output")
    private File output;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (!dictionaryFile.isFile()) {
            throw new MojoFailureException("Identifier dictionary not found: " + dictionaryFile);
        }
        try {
            IdentifierDictionary dict = IdentifierDictionary.read(dictionaryFile.toPath());
            StackTraceDeobfuscator d = new StackTraceDeobfuscator(new IdentifierHasher(salt, hashLength),
                    dict.getIdentifiers());
            String result = d.deobfuscate(new String(Files.readAllBytes(trace.toPath()), StandardCharsets.UTF_8));
            if (output != null) {
                Files.write(output.toPath(), result.getBytes(StandardCharsets.UTF_8));
                getLog().info("Retraced stack trace written to " + output);
            } else {
                getLog().info("Retraced stack trace:\n" + result);
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Retrace failed", e);
        } catch (IllegalArgumentException e) {
            throw new MojoFailureException(e.getMessage());
        }
    }
}
