package com.iitm.beacon.testsupport;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal regex helpers for asserting on server-rendered HTML in MockMvc
 * tests (no HTML parser is on the test classpath). Only fit for markup the
 * app itself renders: double-quoted attributes, and no nesting of the
 * element asked for in {@link #elements(String, String)}.
 */
public final class HtmlSnippets {

    private HtmlSnippets() {
    }

    /** Every {@code <tag …>…</tag>} element in {@code html}, in document order (not for nested elements). */
    public static List<String> elements(String html, String tag) {
        Matcher m = Pattern.compile("<" + tag + "\\b[^>]*>[\\s\\S]*?</" + tag + ">").matcher(html);
        List<String> found = new ArrayList<>();
        while (m.find()) {
            found.add(m.group());
        }
        return found;
    }

    /** Every opening {@code <tag …>} in {@code html}, in document order. */
    public static List<String> openingTags(String html, String tag) {
        Matcher m = Pattern.compile("<" + tag + "\\b[^>]*>").matcher(html);
        List<String> found = new ArrayList<>();
        while (m.find()) {
            found.add(m.group());
        }
        return found;
    }

    /** Every opening tag, of any element, that carries attribute {@code name}, in document order. */
    public static List<String> openingTagsWithAttribute(String html, String name) {
        return openingTags(html, "[a-zA-Z][a-zA-Z0-9-]*").stream()
                .filter(tag -> attribute(tag, name).isPresent())
                .toList();
    }

    /** The first opening {@code <tag …>} in {@code html}; fails if there is none. */
    public static String openingTag(String html, String tag) {
        List<String> tags = openingTags(html, tag);
        if (tags.isEmpty()) {
            throw new AssertionError("No <" + tag + "> in: " + html);
        }
        return tags.get(0);
    }

    /**
     * The value of attribute {@code name} on the opening tag {@code tag}: empty
     * if the attribute is absent, {@code ""} if it is present without a value.
     */
    public static Optional<String> attribute(String tag, String name) {
        Matcher m = Pattern.compile("\\s" + Pattern.quote(name) + "(?:=\"([^\"]*)\")?(?=[\\s/>])").matcher(tag);
        if (!m.find()) {
            return Optional.empty();
        }
        return Optional.of(m.group(1) == null ? "" : m.group(1));
    }
}
