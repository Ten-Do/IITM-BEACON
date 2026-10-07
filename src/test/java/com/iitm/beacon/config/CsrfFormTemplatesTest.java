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
 * Every {@code <form method="post">} in every template carries the CSRF
 * token (BL-004) because it sets its URL with {@code th:action}: that is the
 * attribute Thymeleaf's Spring integration hooks to insert the hidden {@code
 * _csrf} field. A form with a plain {@code action} would post without the
 * token and get a 403. The slices' own tests check the rendered pages.
 */
class CsrfFormTemplatesTest {

    private static final Pattern FORM_TAG = Pattern.compile("<form\\b[^>]*>", Pattern.DOTALL);
    private static final Pattern POST_METHOD = Pattern.compile("\\smethod=\"post\"", Pattern.CASE_INSENSITIVE);

    private record Form(String template, String tag) {
    }

    private static List<Form> postForms() throws IOException {
        List<Form> forms = new ArrayList<>();
        Resource[] templates = new PathMatchingResourcePatternResolver().getResources("classpath*:templates/**/*.html");
        for (Resource template : templates) {
            String html = template.getContentAsString(StandardCharsets.UTF_8);
            Matcher m = FORM_TAG.matcher(html);
            while (m.find()) {
                if (POST_METHOD.matcher(m.group()).find()) {
                    forms.add(new Form(template.getFilename(), m.group()));
                }
            }
        }
        return forms;
    }

    @Test
    void everyPostFormInEveryTemplate_setsItsUrlWithThAction() throws IOException {
        List<Form> forms = postForms();

        assertThat(forms).as("POST forms in the templates").isNotEmpty();
        assertThat(forms).allSatisfy(form -> assertThat(form.tag())
                .as("%s: %s", form.template(), form.tag())
                .containsPattern("\\sth:action=\""));
    }
}
