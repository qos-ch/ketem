package ch.qos.ketem.core;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces obfuscated identifiers in arbitrary text (typically a stack trace)
 * with the original identifiers. The reverse mapping is rebuilt from the salt
 * and the set of candidate original identifiers.
 *
 * <p>Obfuscated tokens embedded in synthetic names, such as
 * {@code lambda$_3f2a9c01b7de$0}, are handled too. Unknown tokens are left
 * unchanged; if two candidates hash to the same token (only possible when the
 * candidates are a superset of the obfuscated names) they are shown as
 * {@code {a|b}}.
 */
public final class StackTraceDeobfuscator {

    private final Map<String, String> reverse = new HashMap<>();
    private final Pattern token;

    public StackTraceDeobfuscator(IdentifierHasher hasher, Collection<String> originalIdentifiers) {
        Map<String, TreeSet<String>> candidates = new HashMap<>();
        for (String id : originalIdentifiers) {
            candidates.computeIfAbsent(hasher.obfuscate(id), k -> new TreeSet<>()).add(id);
        }
        candidates.forEach((obf, ids) ->
                reverse.put(obf, ids.size() == 1 ? ids.first() : "{" + String.join("|", ids) + "}"));
        this.token = Pattern.compile("(?<![A-Za-z0-9_])_[0-9a-f]{" + hasher.getHashLength() + "}(?![A-Za-z0-9_])");
    }

    public String deobfuscate(String text) {
        Matcher m = token.matcher(text);
        StringBuilder sb = new StringBuilder(text.length());
        while (m.find()) {
            String original = reverse.getOrDefault(m.group(), m.group());
            m.appendReplacement(sb, Matcher.quoteReplacement(original));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public int size() {
        return reverse.size();
    }
}
