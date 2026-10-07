package com.iitm.beacon.common.error;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorViewResolver;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.View;

/**
 * The view Spring Boot's {@code /error} dispatch shows a browser (a request
 * accepting HTML): the site's HTML error page for the status, instead of
 * Boot's whitelabel page. That dispatch only answers what escapes the
 * application — a view that fails while rendering, a filter's failure —
 * since {@link GlobalExceptionHandler} answers everything a handler throws.
 * Boot's model (path, timestamp, error) is not shown: the page says only
 * its fixed text (NFR-ERROR-TRANSPARENCY). Being an {@link
 * ErrorViewResolver} bean, this replaces Boot's default one.
 */
@Component
public class ErrorPageViewResolver implements ErrorViewResolver {

    private final ErrorPageRenderer errorPages;

    public ErrorPageViewResolver(ErrorPageRenderer errorPages) {
        this.errorPages = errorPages;
    }

    @Override
    public ModelAndView resolveErrorView(HttpServletRequest request, HttpStatus status, Map<String, Object> model) {
        View page = (ignoredModel, req, res) -> errorPages.write(status, req, res);
        ModelAndView view = new ModelAndView(page);
        view.setStatus(status);
        return view;
    }
}
