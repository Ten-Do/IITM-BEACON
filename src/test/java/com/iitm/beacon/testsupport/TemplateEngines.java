package com.iitm.beacon.testsupport;

import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/**
 * A Thymeleaf engine over the application's real templates
 * ({@code src/main/resources/templates}), for unit tests that render a
 * template without starting a Spring context.
 */
public final class TemplateEngines {

    private TemplateEngines() {
    }

    public static SpringTemplateEngine classpathTemplates() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }
}
