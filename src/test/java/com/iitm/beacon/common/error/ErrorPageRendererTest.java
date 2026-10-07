package com.iitm.beacon.common.error;

import static com.iitm.beacon.testsupport.HtmlSnippets.attribute;
import static com.iitm.beacon.testsupport.HtmlSnippets.elements;
import static com.iitm.beacon.testsupport.HtmlSnippets.openingTags;
import static org.assertj.core.api.Assertions.assertThat;

import com.iitm.beacon.testsupport.TemplateEngines;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.util.HtmlUtils;

/**
 * {@link ErrorPageRenderer}: the site's HTML error page, in the site's own
 * layout (the shared head and the visitor header), with the status's fixed
 * title and sentence and a link back to the homepage — rendered from the
 * real template, with no web request context (it is also used outside
 * Spring MVC, by the security filter chain).
 */
class ErrorPageRendererTest {

    private final ErrorPageRenderer renderer = new ErrorPageRenderer(TemplateEngines.classpathTemplates());

    @ParameterizedTest
    @ValueSource(ints = {400, 403, 404, 405, 406, 415, 500})
    void page_carriesTheStatusTitleAsTitleAndHeading_andItsSentence(int status) {
        ErrorPage expected = ErrorPage.forStatus(status);

        String html = renderer.html(HttpStatusCode.valueOf(status), Locale.ENGLISH);

        String title = HtmlUtils.htmlEscape(expected.title());
        assertThat(elements(html, "title")).containsExactly("<title>" + title + "</title>");
        assertThat(elements(html, "h1")).singleElement().satisfies(h1 -> assertThat(h1).contains(">" + title + "<"));
        assertThat(html).contains(HtmlUtils.htmlEscape(expected.message()));
    }

    @Test
    void page_isInTheSiteLayout_withALinkToTheHomepage() {
        String html = renderer.html(HttpStatus.NOT_FOUND, Locale.ENGLISH);

        assertThat(html).startsWith("<!DOCTYPE html>");
        assertThat(openingTags(html, "link"))
                .anySatisfy(link -> assertThat(attribute(link, "href")).contains("/css/beacon.css"));
        assertThat(html).contains("class=\"header-bar\"", "id=\"visitor-nav\"", "class=\"gallery-empty-state\"");
        assertThat(elements(html, "a"))
                .filteredOn(a -> a.contains("Back to homepage"))
                .singleElement()
                .satisfies(a -> assertThat(attribute(a, "href")).contains("/"));
    }

    @Test
    void page_highlightsNoNavEntry() {
        String html = renderer.html(HttpStatus.NOT_FOUND, Locale.ENGLISH);

        assertThat(elements(html, "nav")).singleElement()
                .satisfies(nav -> assertThat(nav).doesNotContain("active"));
    }

    @Test
    void response_isTheHtmlPage_withTheStatus_andKeepsTheGivenHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setAllow(Set.of(HttpMethod.GET, HttpMethod.POST));
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/admin/login");

        ResponseEntity<String> response = renderer.response(HttpStatus.METHOD_NOT_ALLOWED, headers, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getHeaders().getContentType())
                .isEqualTo(MediaType.parseMediaType("text/html;charset=UTF-8"));
        assertThat(response.getHeaders().getAllow()).containsExactlyInAnyOrder(HttpMethod.GET, HttpMethod.POST);
        assertThat(response.getBody()).isEqualTo(renderer.html(HttpStatus.METHOD_NOT_ALLOWED, request.getLocale()));
    }

    @Test
    void write_answersTheHtmlPage_replacingAContentTypeSetEarlier() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/moderation/queue/1/approve");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        renderer.write(HttpStatus.FORBIDDEN, request, response);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(MediaType.parseMediaType(response.getContentType()))
                .isEqualTo(MediaType.parseMediaType("text/html;charset=UTF-8"));
        assertThat(response.getContentAsString())
                .isEqualTo(renderer.html(HttpStatus.FORBIDDEN, request.getLocale()))
                .contains(HtmlUtils.htmlEscape(ErrorPage.forStatus(403).title()));
    }
}
