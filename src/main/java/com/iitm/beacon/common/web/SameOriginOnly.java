package com.iitm.beacon.common.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method (or every handler method of a controller class)
 * that only answers requests sent by this site's own pages: {@link
 * SameOriginInterceptor} refuses any other request with a 403 before the
 * handler runs (see {@link SameOriginGuard} for the exact check).
 *
 * <p>This is a header check, not authentication: it stops other websites
 * (a browser never lets a page forge {@code Origin} or {@code
 * Sec-Fetch-Site}) and naive scripted scraping, but a client that sets those
 * headers by hand gets through.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SameOriginOnly {
}
