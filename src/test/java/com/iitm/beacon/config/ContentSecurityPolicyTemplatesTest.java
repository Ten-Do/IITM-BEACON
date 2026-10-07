package com.iitm.beacon.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Nothing in the templates or the site's own scripts relies on what the
 * Content-Security-Policy ({@link SecurityHeadersTest}) blocks, so a page
 * never silently loses a style or a behaviour in the browser: no {@code
 * <style>} element or {@code style} attribute (a score's colour is a {@code
 * score-N} class; Alpine's {@code x-bind:style} sets properties through the
 * CSSOM, which the policy allows), no inline {@code <script>}, no event
 * handler attribute, no {@code javascript:} URL, and no script that
 * evaluates text or writes a {@code style} attribute.
 */
class ContentSecurityPolicyTemplatesTest {

    private record Source(String name, String text) {
    }

    private static List<Source> load(String pattern) throws IOException {
        List<Source> sources = new ArrayList<>();
        for (Resource resource : new PathMatchingResourcePatternResolver().getResources(pattern)) {
            sources.add(new Source(resource.getFilename(), resource.getContentAsString(StandardCharsets.UTF_8)));
        }
        assertThat(sources).as(pattern).isNotEmpty();
        return sources;
    }

    private static List<String> matches(List<Source> sources, Pattern pattern) {
        List<String> found = new ArrayList<>();
        for (Source source : sources) {
            Matcher m = pattern.matcher(source.text());
            while (m.find()) {
                found.add(source.name() + ": " + m.group());
            }
        }
        return found;
    }

    @Test
    void templates_haveNoStyleElementOrStyleAttribute() throws IOException {
        List<Source> templates = load("classpath*:templates/**/*.html");

        assertThat(matches(templates, Pattern.compile("<style\\b", Pattern.CASE_INSENSITIVE))).isEmpty();
        assertThat(matches(templates, Pattern.compile("\\s(th:)?style\\s*=", Pattern.CASE_INSENSITIVE))).isEmpty();
    }

    @Test
    void templates_haveNoEventHandlerAttributeOrJavascriptUrl() throws IOException {
        List<Source> templates = load("classpath*:templates/**/*.html");

        assertThat(matches(templates, Pattern.compile("\\s(th:)?on[a-z]+\\s*=", Pattern.CASE_INSENSITIVE))).isEmpty();
        assertThat(matches(templates, Pattern.compile("=\\s*[\"']\\s*javascript:", Pattern.CASE_INSENSITIVE)))
                .isEmpty();
    }

    /** Every script is a file of the site's; a tag replaced by a fragment ({@code th:replace}) is the fragment's. */
    @Test
    void templates_haveNoInlineScript() throws IOException {
        List<String> inline = new ArrayList<>();
        for (String tag : matches(load("classpath*:templates/**/*.html"), Pattern.compile("<script\\b[^>]*>"))) {
            if (!tag.matches("(?s).*\\s(th:)?src=.*") && !tag.matches("(?s).*\\sth:(replace|insert)=.*")) {
                inline.add(tag);
            }
        }
        assertThat(inline).isEmpty();
    }

    @Test
    void scripts_neitherEvaluateTextNorWriteAStyleAttribute() throws IOException {
        List<Source> scripts = load("classpath*:static/js/**/*.js");

        assertThat(matches(scripts, Pattern.compile("\\beval\\s*\\(|new\\s+Function\\b|setTimeout\\s*\\(\\s*['\"]")))
                .isEmpty();
        Pattern styleAttribute = Pattern.compile(
                "setAttribute\\(\\s*['\"]style['\"]|\\.cssText\\b|\\.style\\s*=[^=]|\\sstyle=",
                Pattern.CASE_INSENSITIVE);
        assertThat(matches(scripts, styleAttribute)).isEmpty();
    }
}
