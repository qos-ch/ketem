package ch.qos.ketem.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Maps an original identifier to its obfuscated form: an underscore followed by
 * the first {@code hashLength} hex digits of {@code MD5(salt + ":" + identifier)}.
 *
 * <p>The mapping is deterministic, so given the salt and the set of original
 * identifiers the obfuscated names can always be recomputed and reversed.
 */
public final class IdentifierHasher {

    public static final int DEFAULT_HASH_LENGTH = 12;
    public static final int MIN_HASH_LENGTH = 6;
    public static final int MAX_HASH_LENGTH = 32;

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final byte[] saltPrefix;
    private final int hashLength;

    public IdentifierHasher(String salt) {
        this(salt, DEFAULT_HASH_LENGTH);
    }

    public IdentifierHasher(String salt, int hashLength) {
        if (salt == null || salt.isEmpty()) {
            throw new IllegalArgumentException("salt must not be empty");
        }
        if (hashLength < MIN_HASH_LENGTH || hashLength > MAX_HASH_LENGTH) {
            throw new IllegalArgumentException(
                    "hashLength must be between " + MIN_HASH_LENGTH + " and " + MAX_HASH_LENGTH + ", got " + hashLength);
        }
        this.saltPrefix = (salt + ":").getBytes(StandardCharsets.UTF_8);
        this.hashLength = hashLength;
    }

    public int getHashLength() {
        return hashLength;
    }

    public String obfuscate(String identifier) {
        MessageDigest md5 = newMd5();
        md5.update(saltPrefix);
        byte[] digest = md5.digest(identifier.getBytes(StandardCharsets.UTF_8));
        char[] out = new char[hashLength + 1];
        out[0] = '_';
        for (int i = 0; i < hashLength; i++) {
            int b = digest[i / 2] & 0xff;
            out[i + 1] = HEX[(i % 2 == 0) ? (b >>> 4) : (b & 0x0f)];
        }
        return new String(out);
    }

    private static MessageDigest newMd5() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 not available", e);
        }
    }
}
