package ch.qos.ketem.core;

import com.github.javaparser.ParserConfiguration.LanguageLevel;

/**
 * Maps Java version strings, as found in {@code maven.compiler.release} or
 * {@code maven.compiler.source}, to JavaParser language levels.
 */
public final class JavaLanguageLevels {

    private JavaLanguageLevels() {
    }

    /**
     * Accepts both the legacy ({@code 1.8}) and the modern ({@code 8},
     * {@code 21}) forms. Versions newer than this JavaParser release knows
     * about map to the newest level it supports.
     *
     * @throws IllegalArgumentException if the version cannot be interpreted
     */
    public static LanguageLevel forJavaVersion(String version) {
        String v = version == null ? "" : version.trim();
        if (v.matches("1\\.[0-4]")) {
            return LanguageLevel.valueOf("JAVA_1_" + v.charAt(2));
        }
        if (v.matches("1\\.\\d+")) {
            v = v.substring(2);
        }
        if (!v.matches("\\d+")) {
            throw new IllegalArgumentException("Unrecognized Java version '" + version + "'");
        }
        int feature = Integer.parseInt(v);
        int highest = highestFeatureVersion();
        if (feature > highest) {
            return LanguageLevel.valueOf("JAVA_" + highest);
        }
        try {
            return LanguageLevel.valueOf("JAVA_" + feature);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unrecognized Java version '" + version + "'");
        }
    }

    /** The newest non-preview {@code JAVA_n} level known to JavaParser. */
    static int highestFeatureVersion() {
        int highest = 0;
        for (LanguageLevel level : LanguageLevel.values()) {
            String name = level.name();
            if (name.matches("JAVA_\\d+")) {
                highest = Math.max(highest, Integer.parseInt(name.substring(5)));
            }
        }
        return highest;
    }
}
