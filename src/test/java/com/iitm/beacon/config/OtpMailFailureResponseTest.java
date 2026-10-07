package com.iitm.beacon.config;

import static com.iitm.beacon.testsupport.Csrf.csrfField;
import static com.iitm.beacon.testsupport.Csrf.csrfHeader;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.iitm.beacon.testsupport.LogCapture;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * A login code that can't be emailed (UC-ADMIN-OTP-REQUEST,
 * UC-VISITOR-LOGIN) is answered exactly like one that was sent — on both
 * logins, REST and page alike — so the answer never tells whether the typed
 * email is the admin's (decision 4). Nothing logged names the address: not
 * the warning, and not the mail server's message, which does. Compared:
 * status, every header (except the {@code Date} and cookie values, which
 * differ per request anyway) and the body. The production {@link
 * SmtpOtpMailer} is wired over a mocked {@link JavaMailSender}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(OtpMailFailureResponseTest.SmtpMailerConfig.class)
class OtpMailFailureResponseTest {

    private static final String ADMIN_EMAIL = "admin@example.com";

    @RegisterExtension
    final LogCapture logs = new LogCapture();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JavaMailSender mailSender;

    /** The four ways to ask for a login code, each with an email whose code is really sent. */
    enum Flow {
        ADMIN_PAGE(ADMIN_EMAIL),
        ADMIN_API(ADMIN_EMAIL),
        VISITOR_PAGE("jane.page@example.com"),
        VISITOR_API("jane.api@example.com");

        final String email;

        Flow(String email) {
            this.email = email;
        }

        MockHttpServletRequestBuilder request(String email) {
            return switch (this) {
                case ADMIN_PAGE -> post("/admin/login/request").with(csrfField()).param("email", email);
                case VISITOR_PAGE -> post("/submissions/login").with(csrfField()).param("email", email);
                case ADMIN_API -> json("/api/admin/auth/otp/request", email);
                case VISITOR_API -> json("/api/submissions/otp/request", email);
            };
        }

        private static MockHttpServletRequestBuilder json(String path, String email) {
            return post(path).with(csrfHeader())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"" + email + "\"}");
        }
    }

    private MockHttpServletResponse ask(Flow flow, String email) throws Exception {
        return mockMvc.perform(flow.request(email)).andReturn().getResponse();
    }

    private void mailServerRejects(String recipient) {
        doThrow(new MailSendException("550 5.1.1 Recipient address rejected: " + recipient))
                .when(mailSender).send(any(SimpleMailMessage.class));
    }

    @ParameterizedTest
    @EnumSource(Flow.class)
    void codeThatCannotBeEmailed_isAnsweredExactlyLikeASentOne(Flow flow) throws Exception {
        MockHttpServletResponse sent = ask(flow, flow.email);
        verify(mailSender).send(any(SimpleMailMessage.class));
        mailServerRejects(flow.email);

        MockHttpServletResponse notSent = ask(flow, flow.email);

        assertThat(notSent.getStatus()).isEqualTo(sent.getStatus()).isLessThan(400);
        assertThat(comparableHeaders(notSent)).isEqualTo(comparableHeaders(sent));
        assertThat(notSent.getContentAsString()).isEqualTo(sent.getContentAsString());
    }

    @ParameterizedTest
    @EnumSource(Flow.class)
    void codeThatCannotBeEmailed_warnsOnce_namingNeitherTheAddressNorTheServersMessage(Flow flow) throws Exception {
        mailServerRejects(flow.email);

        ask(flow, flow.email);

        String localPart = flow.email.substring(0, flow.email.indexOf('@'));
        assertThat(logs.warnings()).singleElement()
                .satisfies(warning -> assertThat(warning).contains("could not be emailed", "MailSendException"));
        assertThat(logs.all())
                .noneMatch(line -> line.contains(localPart + "@"))
                .noneMatch(line -> line.contains("Recipient address rejected"));
        assertThat(logs.errors()).isEmpty();
    }

    /** The admin's code failing to go out looks exactly like typing someone else's email, for which nothing is sent. */
    @Test
    void adminPage_failedAdminCode_looksLikeAnotherEmail() throws Exception {
        mailServerRejects(ADMIN_EMAIL);

        MockHttpServletResponse adminsEmail = ask(Flow.ADMIN_PAGE, ADMIN_EMAIL);
        MockHttpServletResponse otherEmail = ask(Flow.ADMIN_PAGE, "someone-else@example.com");

        assertThat(adminsEmail.getStatus()).isEqualTo(otherEmail.getStatus()).isEqualTo(302);
        assertThat(comparableHeaders(adminsEmail)).isEqualTo(comparableHeaders(otherEmail));
        assertThat(adminsEmail.getContentAsString()).isEqualTo(otherEmail.getContentAsString());
    }

    @Test
    void adminApi_failedAdminCode_looksLikeAnotherEmail() throws Exception {
        mailServerRejects(ADMIN_EMAIL);

        MockHttpServletResponse adminsEmail = ask(Flow.ADMIN_API, ADMIN_EMAIL);
        MockHttpServletResponse otherEmail = ask(Flow.ADMIN_API, "someone-else@example.com");

        assertThat(adminsEmail.getStatus()).isEqualTo(otherEmail.getStatus()).isEqualTo(202);
        assertThat(comparableHeaders(adminsEmail)).isEqualTo(comparableHeaders(otherEmail));
        assertThat(adminsEmail.getContentAsString()).isEqualTo(otherEmail.getContentAsString());
    }

    @Test
    void adminPage_anotherEmail_triesToSendNothing() throws Exception {
        ask(Flow.ADMIN_PAGE, "someone-else@example.com");

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    /** Every header as name → values, the {@code Date} and each cookie's value blanked. */
    private static Map<String, List<String>> comparableHeaders(MockHttpServletResponse response) {
        Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String name : response.getHeaderNames()) {
            List<String> values = new ArrayList<>();
            for (String value : response.getHeaders(name)) {
                if ("Date".equalsIgnoreCase(name)) {
                    values.add("{date}");
                } else if ("Set-Cookie".equalsIgnoreCase(name)) {
                    values.add(value.replaceFirst("^([^=]+)=[^;]*", "$1={value}"));
                } else {
                    values.add(value);
                }
            }
            headers.put(name, values);
        }
        return headers;
    }

    @TestConfiguration
    static class SmtpMailerConfig {

        /** The production mailer, over the test's mocked {@link JavaMailSender}. */
        @Bean
        @Primary
        OtpMailer smtpOtpMailer(JavaMailSender mailSender) {
            return new SmtpOtpMailer(mailSender, "no-reply@iitm-beacon.example");
        }
    }
}
