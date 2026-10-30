package ch.qos.ketem.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.github.javaparser.ParserConfiguration.LanguageLevel;

class JavaLanguageLevelsTest {

    @Test
    void modernAndLegacyForms() {
        assertEquals(LanguageLevel.JAVA_8, JavaLanguageLevels.forJavaVersion("8"));
        assertEquals(LanguageLevel.JAVA_8, JavaLanguageLevels.forJavaVersion("1.8"));
        assertEquals(LanguageLevel.JAVA_1_4, JavaLanguageLevels.forJavaVersion("1.4"));
        assertEquals(LanguageLevel.JAVA_17, JavaLanguageLevels.forJavaVersion(" 17 "));
        assertEquals(LanguageLevel.JAVA_21, JavaLanguageLevels.forJavaVersion("21"));
    }

    @Test
    void futureVersionMapsToNewestKnown() {
        LanguageLevel newest = LanguageLevel.valueOf("JAVA_" + JavaLanguageLevels.highestFeatureVersion());
        assertEquals(newest, JavaLanguageLevels.forJavaVersion("99"));
    }

    @Test
    void garbageIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> JavaLanguageLevels.forJavaVersion("seventeen"));
        assertThrows(IllegalArgumentException.class, () -> JavaLanguageLevels.forJavaVersion(""));
        assertThrows(IllegalArgumentException.class, () -> JavaLanguageLevels.forJavaVersion(null));
    }
}
