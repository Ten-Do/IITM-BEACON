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
 * Every template links the site's stylesheets, scripts and WebJars through
 * a Thymeleaf link expression ({@code th:href="@{…}"}, {@code
 * th:src="@{…}"}): only a link built that way is rewritten to the file's
 * content-versioned URL ({@link StaticAssetCachingTest}). A plain {@code
 * href="/css/…"} would keep the plain URL, which browsers must revalidate
 * on every use.
 */
class StaticAssetLinksTemplatesTest {

    private static final Pattern PLAIN_ASSET_LINK =
            Pattern.compile("(?<![:\\w-])(src|href)\\s*=\\s*\"/(css|js|webjars)/[^\"]*\"");
    private static final Pattern THYMELEAF_ASSET_LINK =
            Pattern.compile("th:(src|href)\\s*=\\s*\"([^\"]*/(css|js|webjars)/[^\"]*)\"");

    private static List<String> templates() throws IOException {
        List<String> texts = new ArrayList<>();
        PathMatchingResourcePatternResolver resources = new PathMatchingResourcePatternResolver();
        for (Resource template : resources.getResources("classpath*:templates/**/*.html")) {
            texts.add(template.getFilename() + "\n" + template.getContentAsString(StandardCharsets.UTF_8));
        }
        assertThat(texts).isNotEmpty();
        return texts;
    }

    @Test
    void noTemplate_linksAnAssetByItsPlainPath() throws IOException {
        List<String> plain = new ArrayList<>();
        for (String template : templates()) {
            Matcher m = PLAIN_ASSET_LINK.matcher(template);
            while (m.find()) {
                plain.add(template.lines().findFirst().orElse("") + ": " + m.group());
            }
        }
        assertThat(plain).isEmpty();
    }

    @Test
    void everyThymeleafAssetLink_isALinkExpression() throws IOException {
        List<String> links = new ArrayList<>();
        for (String template : templates()) {
            Matcher m = THYMELEAF_ASSET_LINK.matcher(template);
            while (m.find()) {
                links.add(m.group(2));
            }
        }
        assertThat(links).as("asset links").isNotEmpty()
                .allSatisfy(link -> assertThat(link).matches("@\\{~?/(css|js|webjars)/[^}]+}"));
    }
}
