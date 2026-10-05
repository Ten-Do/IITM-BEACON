package com.iitm.beacon.e2e;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import com.iitm.beacon.common.EmailNormalizer;
import com.iitm.beacon.config.OtpMailer;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
 * port (test configuration, H2) driven by headless Chromium through
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
 * classes share one Spring context and one in-memory H2 database. OTP codes
 * are captured from a mocked {@link OtpMailer} by the login helpers.
 */
@Tag("e2e")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(E2eData.class)
public abstract class E2eTestBase {

    private static final double ACTION_TIMEOUT_MS = 10_000;

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

    /** A phone's tap highlight lingers over whatever the tap opened; screenshots would catch it. */
    private static final String NO_TAP_HIGHLIGHT_JS = """
            document.addEventListener('DOMContentLoaded', () => {
              const style = document.createElement('style');
              style.textContent = '* { -webkit-tap-highlight-color: transparent !important; }';
              document.head.append(style);
            });""";

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

    private static void stabilize(Page page) {
        page.addStyleTag(new Page.AddStyleTagOptions().setContent(STABILIZING_CSS));
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

    private void loginThroughUi(String loginPath, String emailField, String email) {
        String key = EmailNormalizer.normalize(email);
        lastOtpCodeByEmail.remove(key);
        navigate(loginPath);
        currentPage.locator(emailField).fill(email);
        currentPage.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Send code")).click();
        currentPage.waitForURL(url -> url.contains(loginPath + "/code"));
        currentPage.waitForCondition(() -> lastOtpCodeByEmail.containsKey(key));
        currentPage.locator("#code").fill(lastOtpCodeByEmail.get(key));
        currentPage.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Verify")).click();
        currentPage.waitForURL(url -> !url.contains(loginPath));
    }
}
