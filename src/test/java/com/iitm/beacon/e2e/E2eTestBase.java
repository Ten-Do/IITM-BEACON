package com.iitm.beacon.e2e;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import com.iitm.beacon.common.EmailNormalizer;
import com.iitm.beacon.config.OtpMailer;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.CDPSession;
import com.microsoft.playwright.Clock;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.BoundingBox;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Base class for browser end-to-end tests: the real application on a random
 * port (test configuration, the shared PostgreSQL test database) driven by
 * headless Chromium through
 * Playwright. A test drives a page into a state and asserts only with
 * screenshots, compared against baselines by {@link ScreenshotAssert}.
 *
 * <p>Tagged {@code e2e}, so plain {@code mvn test} skips every subclass; they
 * run only via {@code make e2e} (screenshots are only comparable when
 * rendered in the Playwright Docker image).
 *
 * <p>One Chromium per test class; every {@link #openPage(DevicePreset)}
 * creates a fresh, isolated browser context, closed after the test. Every
 * test starts with no testimonials ({@link E2eData#clear()}): all e2e
 * classes share one Spring context and one database. OTP codes
 * are captured from a mocked {@link OtpMailer} by the login helpers.
 *
 * <p>The test configuration allows 1000 code requests per email a minute, so
 * a test may log in as often as it needs. Here that is 1000 per 1000 minutes:
 * the same 1000 requests are available at once (the rate limiter starts
 * full), while the code pages' "Resend code" waits the window over the limit
 * — 60 s, as with the shipped one request a minute — rather than 1 s.
 */
@Tag("e2e")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "visitor.otp.request-window-per-email=PT1000M",
            "admin.otp.request-window-per-email=PT1000M"
        })
@Import(E2eData.class)
public abstract class E2eTestBase {

    private static final double ACTION_TIMEOUT_MS = 10_000;

    /** Where {@link #pauseClock()} starts the page's clock, and the moment it then stops it at. */
    private static final String CLOCK_START = "2026-01-01T09:00:00Z";
    private static final String CLOCK_PAUSED_AT = "2026-01-01T10:00:00Z";

    /** Nothing moves, fades or blinks while a screenshot is taken. */
    private static final String STABILIZING_CSS = """
            *, *::before, *::after {
              animation: none !important;
              transition: none !important;
              caret-color: transparent !important;
              scroll-behavior: auto !important;
            }
            """;

    /** Every image loaded and decoded (lazy ones switched to eager, or a full-page shot has blanks), at most 5 s. */
    private static final String AWAIT_IMAGES_JS = """
            async () => {
              const images = [...document.images];
              images.forEach((image) => { image.loading = 'eager'; });
              const settled = (image) => image.complete ? null : new Promise((resolve) => {
                image.addEventListener('load', resolve, { once: true });
                image.addEventListener('error', resolve, { once: true });
              });
              const timeout = new Promise((resolve) => setTimeout(resolve, 5000));
              await Promise.race([Promise.all(images.map(settled)), timeout]);
              await Promise.all(images.map((image) =>
                  image.naturalWidth > 0 ? image.decode().catch(() => null) : null));
            }""";

    /**
     * Applies the CSS given as its argument to the page as a constructed
     * stylesheet: the site's Content-Security-Policy ({@code style-src
     * 'self'}) blocks an inline {@code <style>} element — which is what
     * {@code Page.addStyleTag} would add — but not a constructed sheet.
     */
    private static final String ADOPT_STYLESHEET_JS = """
            css => {
              const sheet = new CSSStyleSheet();
              sheet.replaceSync(css);
              document.adoptedStyleSheets = [...document.adoptedStyleSheets, sheet];
            }""";

    /** A phone's tap highlight lingers over whatever the tap opened; screenshots would catch it. */
    private static final String NO_TAP_HIGHLIGHT_JS = "(" + ADOPT_STYLESHEET_JS + ")("
            + "'* { -webkit-tap-highlight-color: transparent !important; }');";

    private static Playwright playwright;
    private static Browser browser;

    private final ScreenshotAssert screenshots = ScreenshotAssert.fromSystemProperties();
    private final Map<String, String> lastOtpCodeByEmail = new ConcurrentHashMap<>();
    private final List<BrowserContext> contexts = new ArrayList<>();
    private Page currentPage;

    @LocalServerPort
    private int port;

    @Value("${admin.email}")
    private String adminEmail;

    @MockitoBean
    private OtpMailer otpMailer;

    @Autowired
    private E2eData data;

    @BeforeAll
    static void launchBrowser() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
    }

    @AfterAll
    static void closeBrowser() {
        playwright.close();
    }

    @BeforeEach
    void startFromNoTestimonialsAndRecordOtpCodes() {
        data.clear();
        lastOtpCodeByEmail.clear();
        doAnswer(invocation -> {
            lastOtpCodeByEmail.put(EmailNormalizer.normalize(invocation.getArgument(0)), invocation.getArgument(1));
            return null;
        }).when(otpMailer).sendOtp(anyString(), anyString());
    }

    @AfterEach
    void closeBrowserContexts() {
        contexts.forEach(BrowserContext::close);
        contexts.clear();
        currentPage = null;
    }

    // -- pages --

    /** Opens a page in a new, isolated browser context and makes it the current {@link #page()}. */
    protected Page openPage(DevicePreset preset) {
        BrowserContext context = browser.newContext(preset.contextOptions());
        context.addInitScript(NO_TAP_HIGHLIGHT_JS);
        context.setDefaultTimeout(ACTION_TIMEOUT_MS);
        context.setDefaultNavigationTimeout(ACTION_TIMEOUT_MS);
        contexts.add(context);
        currentPage = context.newPage();
        return currentPage;
    }

    protected Page page() {
        return currentPage;
    }

    protected String url(String path) {
        return "http://localhost:" + port + path;
    }

    protected void navigate(String path) {
        currentPage.navigate(url(path));
    }

    /**
     * Stops the clock (time and timers) of the current page's browser
     * context, from now on and in every page it opens after this — until a
     * test moves it on with {@code page().clock().runFor(…)}, or {@code
     * resume()}s it. Whatever counts down by it, like the code pages'
     * "Resend code", holds still for a screenshot. Call it before opening
     * the page whose clock should stand still.
     */
    protected void pauseClock() {
        currentPage.clock().install(new Clock.InstallOptions().setTime(CLOCK_START));
        currentPage.clock().pauseAt(CLOCK_PAUSED_AT);
    }

    protected E2eData data() {
        return data;
    }

    protected String adminEmail() {
        return adminEmail;
    }

    // -- screenshots: baseline <ThisClass>/<name>.png --

    protected void assertScreenshot(String name) {
        stabilize(currentPage);
        screenshots.assertMatches(getClass(), name, ScreenshotAssert.capture(currentPage, false));
    }

    protected void assertFullPageScreenshot(String name) {
        stabilize(currentPage);
        screenshots.assertMatches(getClass(), name, ScreenshotAssert.capture(currentPage, true));
    }

    protected void assertScreenshot(Locator element, String name) {
        stabilize(element.page());
        screenshots.assertMatches(getClass(), name, ScreenshotAssert.capture(element));
    }

    /**
     * {@code element} while a navigation is still on its way — a form submit
     * whose request {@code page.route} holds: Playwright's own screenshots
     * and evaluations wait for a pending navigation to finish. So the page
     * is stabilised and the element's box measured first, then {@code
     * startNavigation} runs (it must return with the navigation started and
     * still pending), and the viewport is taken straight through the Chrome
     * DevTools Protocol and cut to that box. The DevTools session is left to
     * close with the browser context: detaching it would wait for the page,
     * which DevTools holds back until the navigation is over.
     */
    protected void assertScreenshotWhileNavigating(Locator element, String name, Runnable startNavigation) {
        stabilize(currentPage);
        BoundingBox box = element.boundingBox();
        startNavigation.run();
        CDPSession devTools = currentPage.context().newCDPSession(currentPage);
        String viewport = devTools.send("Page.captureScreenshot").get("data").getAsString();
        screenshots.assertMatches(getClass(), name, crop(Base64.getDecoder().decode(viewport), box));
    }

    private static byte[] crop(byte[] png, BoundingBox box) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
            int left = (int) Math.floor(box.x);
            int top = (int) Math.floor(box.y);
            int right = (int) Math.ceil(box.x + box.width);
            int bottom = (int) Math.ceil(box.y + box.height);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image.getSubimage(left, top, right - left, bottom - top), "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void stabilize(Page page) {
        page.evaluate(ADOPT_STYLESHEET_JS, STABILIZING_CSS);
        page.evaluate("() => document.fonts.ready.then(() => true)");
        page.evaluate(AWAIT_IMAGES_JS);
    }

    // -- login through the real two-step OTP pages --

    protected void loginAsAdmin() {
        loginThroughUi("/admin/login", "#admin-email", adminEmail);
    }

    protected void loginAsVisitor(String email) {
        loginThroughUi("/submissions/login", "#email", email);
    }

    /**
     * Asks for a visitor login code for {@code email} on the real email step;
     * the browser ends on the code step. The code page has no URL of its own
     * to open directly: the email reaches it in the session.
     */
    protected void requestVisitorLoginCode(String email) {
        requestLoginCode("/submissions/login", "#email", email);
    }

    /** Asks for an admin login code on the real email step; the browser ends on the code step. */
    protected void requestAdminLoginCode() {
        requestLoginCode("/admin/login", "#admin-email", adminEmail);
    }

    /**
     * The login code mailed to {@code email} by the last {@link
     * #requestVisitorLoginCode} / {@link #requestAdminLoginCode}, waiting
     * until it has been sent.
     */
    protected String mailedLoginCode(String email) {
        String key = EmailNormalizer.normalize(email);
        currentPage.waitForCondition(() -> lastOtpCodeByEmail.containsKey(key));
        return lastOtpCodeByEmail.get(key);
    }

    /** Forgets the code last mailed to {@code email}, so {@link #mailedLoginCode} waits for the new one. */
    private void requestLoginCode(String loginPath, String emailField, String email) {
        lastOtpCodeByEmail.remove(EmailNormalizer.normalize(email));
        navigate(loginPath);
        currentPage.locator(emailField).fill(email);
        currentPage.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Send code")).click();
        currentPage.waitForURL(url -> url.contains(loginPath + "/code"));
    }

    private void loginThroughUi(String loginPath, String emailField, String email) {
        requestLoginCode(loginPath, emailField, email);
        currentPage.locator("#code").fill(mailedLoginCode(email));
        currentPage.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Verify")).click();
        currentPage.waitForURL(url -> !url.contains(loginPath));
    }
}
