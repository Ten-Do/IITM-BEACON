package com.iitm.beacon.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.iitm.beacon.common.otp.OtpCodeGenerator;
import com.iitm.beacon.common.ratelimit.RateLimiterService;
import com.iitm.beacon.config.SmtpOtpMailer;
import com.iitm.beacon.config.VisitorOtpProperties;
import com.iitm.beacon.testsupport.LogCapture;
import com.iitm.beacon.testsupport.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * UC-VISITOR-LOGIN when the code can't be emailed: {@link
 * VisitorOtpService#requestOtp} returns exactly as it does after a sent code
 * (the request step answers the same for every email — decision 17) and logs
 * a warning naming neither the address nor the mail server's message, which
 * names it. The code stays issued: "Resend code" simply tries again. Uses
 * the production {@link SmtpOtpMailer} over a mocked {@link JavaMailSender}.
 */
class VisitorOtpServiceMailFailureTest {

    private static final String EMAIL = "jane@example.com";
    private static final String IP = "203.0.113.10";

    @RegisterExtension
    final LogCapture logs = new LogCapture();

    private JavaMailSender mailSender;
    private VisitorOtpService visitorOtpService;

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        RateLimiterService rateLimiter = mock(RateLimiterService.class);
        when(rateLimiter.tryConsume(anyString(), any(), anyInt(), any())).thenReturn(true);
        visitorOtpService = new VisitorOtpService(
                new VisitorOtpProperties(Duration.ofMinutes(5), 5, 1, Duration.ofMinutes(1), 5, Duration.ofMinutes(1)),
                new OtpCodeGenerator(),
                new SmtpOtpMailer(mailSender, "no-reply@iitm-beacon.example"),
                new MutableClock(Instant.parse("2026-01-01T00:00:00Z")),
                rateLimiter);
    }

    private void mailServerRejectsTheRecipient() {
        doThrow(new MailSendException("550 5.1.1 Recipient address rejected: " + EMAIL))
                .when(mailSender).send(any(SimpleMailMessage.class));
    }

    private String attemptedCode() {
        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(sent.capture());
        String text = sent.getValue().getText();
        return text.substring(text.length() - 6);
    }

    @Test
    void codeCannotBeEmailed_requestReturnsNormally() {
        mailServerRejectsTheRecipient();

        assertThatCode(() -> visitorOtpService.requestOtp(EMAIL, IP)).doesNotThrowAnyException();
    }

    @Test
    void codeCannotBeEmailed_warnsWithTheFailuresTypeOnly_neverTheAddressOrTheServersMessage() {
        mailServerRejectsTheRecipient();

        visitorOtpService.requestOtp(EMAIL, IP);

        assertThat(logs.warnings()).singleElement().satisfies(warning -> assertThat(warning)
                .contains("could not be emailed")
                .contains("MailSendException"));
        assertThat(logs.all())
                .noneMatch(line -> line.contains("jane"))
                .noneMatch(line -> line.contains("550"))
                .noneMatch(line -> line.contains("Recipient address rejected"));
        assertThat(logs.errors()).isEmpty();
    }

    @Test
    void codeCannotBeEmailed_theIssuedCodeStillVerifies() {
        mailServerRejectsTheRecipient();
        visitorOtpService.requestOtp(EMAIL, IP);

        assertThat(visitorOtpService.verify(EMAIL, attemptedCode()))
                .isEqualTo(new VisitorOtpVerifyResult.Verified(EMAIL));
    }

    @Test
    void codeCannotBeEmailed_aResendOnceTheServerAcceptsIt_sendsANewCodeThatVerifies() {
        mailServerRejectsTheRecipient();
        visitorOtpService.requestOtp(EMAIL, IP);

        reset(mailSender);
        visitorOtpService.requestOtp(EMAIL, IP);

        assertThat(visitorOtpService.verify(EMAIL, attemptedCode()))
                .isInstanceOf(VisitorOtpVerifyResult.Verified.class);
    }
}
