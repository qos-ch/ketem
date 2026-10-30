package ch.qos.ketem.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Paths;
import java.util.List;

import org.junit.jupiter.api.Test;

class SourceSelectorTest {

    @Test
    void packagesClassesAndExcludes() {
        SourceSelector s = new SourceSelector(List.of("com.acme.core"), List.of("com.acme.api.Special"),
                List.of("com.acme.core.generated", "com.acme.core.Keep"));
        assertTrue(s.isSelected("com.acme.core.Foo"));
        assertTrue(s.isSelected("com.acme.core.sub.Bar"));
        assertTrue(s.isSelected("com.acme.api.Special"));
        assertFalse(s.isSelected("com.acme.api.Other"));
        assertFalse(s.isSelected("com.acme.coreother.Foo"));
        assertFalse(s.isSelected("com.acme.core.generated.Gen"));
        assertFalse(s.isSelected("com.acme.core.Keep"));
    }

    @Test
    void emptyIncludesSelectEverything() {
        SourceSelector s = new SourceSelector(null, null, List.of("x.y"));
        assertTrue(s.isSelected("a.B"));
        assertTrue(s.isSelected("Root"));
        assertFalse(s.isSelected("x.y.Z"));
    }

    @Test
    void fqcnFromPath() {
        assertEquals("com.acme.Foo", SourceTreeObfuscator.fqcnOf(Paths.get("com/acme/Foo.java")));
        assertEquals("Root", SourceTreeObfuscator.fqcnOf(Paths.get("Root.java")));
    }
}
