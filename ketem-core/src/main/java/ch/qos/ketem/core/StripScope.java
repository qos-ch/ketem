package ch.qos.ketem.core;

/** Which files have their comments removed when comment stripping is on. */
public enum StripScope {

    /** Only the files that are obfuscated. */
    OBFUSCATED,

    /**
     * Every file in the designated packages and classes, including those
     * excluded from obfuscation. Excluded files keep their identifiers.
     */
    PACKAGES
}
