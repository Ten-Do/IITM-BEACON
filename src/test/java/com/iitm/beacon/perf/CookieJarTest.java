package com.iitm.beacon.perf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The perf clients' cookies: what a browser keeps from {@code Set-Cookie}
 * response headers (in header order) and sends back in its {@code Cookie}
 * header — enough for the session and the XSRF-TOKEN, which both rotate at
 * the admin login.
 */
class CookieJarTest {

    @Test
    void anEmptyJar_sendsNoCookieHeader() {
        CookieJar jar = new CookieJar();

        assertThat(jar.cookieHeader()).isEmpty();
        assertThat(jar.get("XSRF-TOKEN")).isEmpty();
    }

    @Test
    void attributesAreNotPartOfTheValue() {
        CookieJar jar = new CookieJar();

        jar.accept(List.of("XSRF-TOKEN=abc-123; Path=/; SameSite=Lax"));

        assertThat(jar.get("XSRF-TOKEN")).hasValue("abc-123");
        assertThat(jar.cookieHeader()).hasValue("XSRF-TOKEN=abc-123");
    }

    @Test
    void severalCookies_areSentTogetherInTheOrderFirstReceived() {
        CookieJar jar = new CookieJar();

        jar.accept(List.of("XSRF-TOKEN=t1; Path=/", "JSESSIONID=s1; Path=/; HttpOnly"));

        assertThat(jar.cookieHeader()).hasValue("XSRF-TOKEN=t1; JSESSIONID=s1");
    }

    @Test
    void aCookieSetAgain_replacesTheOldValue() {
        CookieJar jar = new CookieJar();
        jar.accept(List.of("JSESSIONID=old; Path=/"));

        jar.accept(List.of("JSESSIONID=new; Path=/"));

        assertThat(jar.cookieHeader()).hasValue("JSESSIONID=new");
    }

    @Test
    void maxAgeZeroOrNegative_deletesTheCookie_whateverTheAttributesCase() {
        CookieJar jar = new CookieJar();
        jar.accept(List.of("A=1", "B=2", "C=3"));

        jar.accept(List.of("A=; Max-Age=0; Path=/", "B=x; max-age=-1"));

        assertThat(jar.cookieHeader()).hasValue("C=3");
    }

    @Test
    void aDeletionFollowedByANewValueInTheSameResponse_keepsTheNewValue() {
        CookieJar jar = new CookieJar();
        jar.accept(List.of("XSRF-TOKEN=before-login"));

        jar.accept(List.of(
                "XSRF-TOKEN=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:10 GMT; Path=/",
                "XSRF-TOKEN=after-login; Path=/; SameSite=Lax"));

        assertThat(jar.get("XSRF-TOKEN")).hasValue("after-login");
    }

    @Test
    void aPositiveMaxAge_keepsTheCookie() {
        CookieJar jar = new CookieJar();

        jar.accept(List.of("A=1; Max-Age=60"));

        assertThat(jar.get("A")).hasValue("1");
    }

    @Test
    void aValueContainingEqualsSigns_isKeptWhole() {
        CookieJar jar = new CookieJar();

        jar.accept(List.of("T=YWJj==; Path=/"));

        assertThat(jar.get("T")).hasValue("YWJj==");
    }

    @Test
    void anEmptyValueWithoutMaxAge_isStillACookie() {
        CookieJar jar = new CookieJar();

        jar.accept(List.of("E=; Path=/"));

        assertThat(jar.cookieHeader()).hasValue("E=");
    }

    @Test
    void aHeaderWithoutANameValuePair_isIgnored() {
        CookieJar jar = new CookieJar();

        jar.accept(List.of("", "   ", "garbage; Path=/", "=nameless"));

        assertThat(jar.cookieHeader()).isEmpty();
    }

    @Test
    void whitespaceAroundNameAndValue_isTrimmed() {
        CookieJar jar = new CookieJar();

        jar.accept(List.of("  A =  1 ; Path=/"));

        assertThat(jar.cookieHeader()).hasValue("A=1");
    }

    @Test
    void nullHeaders_areRejected() {
        assertThatThrownBy(() -> new CookieJar().accept(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void aCopy_isIndependentOfTheOriginal() {
        CookieJar original = new CookieJar();
        original.accept(List.of("JSESSIONID=s1"));

        CookieJar copy = original.copy();
        copy.accept(List.of("JSESSIONID=s2", "X=1"));

        assertThat(original.cookieHeader()).hasValue("JSESSIONID=s1");
        assertThat(copy.cookieHeader()).hasValue("JSESSIONID=s2; X=1");
    }
}
