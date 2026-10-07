package com.iitm.beacon.adminauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.iitm.beacon.common.otp.OtpCodeGenerator;
import com.iitm.beacon.common.ratelimit.RateLimiterService;
import com.iitm.beacon.config.AdminOtpProperties;
import com.iitm.beacon.config.AdminProperties;
import com.iitm.beacon.config.OtpMailer;
import com.iitm.beacon.config.SmtpOtpMailer;
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
 * UC-ADMIN-OTP-REQUEST when the code can't be emailed: {@link
 * OtpService#requestOtp} returns exactly as it does after a sent code — a
 * different outcome would reveal that the typed email is the admin's
 * (decision 4) — and logs a warning naming neither the address nor the mail
 * server's message, which names it. The code stays issued: "Resend code"
 * simply tries again. Uses the production {@link SmtpOtpMailer} over a
 * mocked {@link JavaMailSender}.
 */
class OtpServiceMailFailureTest {

    private static final String ADMIN_EMAIL = "admin@example.com";
    private static final String IP = "203.0.113.10";

    @RegisterExtension
    final LogCapture logs = new LogCapture();

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private JavaMailSender mailSender;
    private OtpService otpService;

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        RateLimiterService rateLimiter = mock(RateLimiterService.class);
        when(rateLimiter.tryConsume(anyString(), any(), anyInt(), any())).thenReturn(true);
        otpService = otpServiceSendingWith(new SmtpOtpMailer(mailSender, "no-reply@iitm-beacon.example"), rateLimiter);
    }

    private OtpService otpServiceSendingWith(OtpMailer mailer, RateLimiterService rateLimiter) {
        return new OtpService(
                new AdminProperties(ADMIN_EMAIL),
                new AdminOtpProperties(Duration.ofMinutes(5), 5, 1, Duration.ofMinutes(1), 5, Duration.ofMinutes(1)),
                new OtpCodeGenerator(),
                mailer,
                clock,
                rateLimiter);
    }

    private void mailServerRejectsTheRecipient() {
        doThrow(new MailSendException("550 5.1.1 Recipient address rejected: " + ADMIN_EMAIL))
                .when(mailSender).send(any(SimpleMailMessage.class));
    }

    /** The code in the message the mailer tried to send. */
    private String attemptedCode() {
        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(sent.capture());
        String text = sent.getValue().getText();
        return text.substring(text.length() - 6);
    }

    @Test
    void codeCannotBeEmailed_requestReturnsNormally() {
        mailServerRejectsTheRecipient();

        assertThatCode(() -> otpService.requestOtp(ADMIN_EMAIL, IP)).doesNotThrowAnyException();
    }

    @Test
    void codeCannotBeEmailed_warnsWithTheFailuresTypeOnly_neverTheAddressOrTheServersMessage() {
        mailServerRejectsTheRecipient();

        otpService.requestOtp(ADMIN_EMAIL, IP);

        assertThat(logs.warnings()).singleElement().satisfies(warning -> assertThat(warning)
                .contains("could not be emailed")
                .contains("MailSendException"));
        assertThat(logs.all())
                .noneMatch(line -> line.contains("admin@"))
                .noneMatch(line -> line.contains("550"))
                .noneMatch(line -> line.contains("Recipient address rejected"));
        assertThat(logs.errors()).isEmpty();
    }

    @Test
    void codeCannotBeEmailed_theIssuedCodeStillVerifies() {
        mailServerRejectsTheRecipient();
        otpService.requestOtp(ADMIN_EMAIL, IP);

        OtpVerifyResult result = otpService.verify(ADMIN_EMAIL, attemptedCode());

        assertThat(result).isEqualTo(new OtpVerifyResult.Verified(ADMIN_EMAIL));
    }

    @Test
    void codeCannotBeEmailed_aResendOnceTheServerAcceptsIt_sendsANewCodeThatVerifies() {
        mailServerRejectsTheRecipient();
        otpService.requestOtp(ADMIN_EMAIL, IP);

        reset(mailSender);
        otpService.requestOtp(ADMIN_EMAIL, IP);

        assertThat(otpService.verify(ADMIN_EMAIL, attemptedCode())).isInstanceOf(OtpVerifyResult.Verified.class);
        assertThat(logs.warnings()).hasSize(1);
    }

    /** Nothing is sent for another email, so nothing can fail — and nothing is logged either. */
    @Test
    void notTheAdminsEmail_nothingIsSentAndNothingWarned() {
        mailServerRejectsTheRecipient();

        otpService.requestOtp("someone-else@example.com", IP);

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
        assertThat(logs.warnings()).isEmpty();
    }

    /** Only a mail failure is swallowed: any other failure of the mailer is a bug and still surfaces. */
    @Test
    void mailerFailsWithSomethingOtherThanAMailFailure_itPropagates() {
        OtpMailer broken = mock(OtpMailer.class);
        doThrow(new IllegalStateException("bug")).when(broken).sendOtp(any(), any());
        RateLimiterService rateLimiter = mock(RateLimiterService.class);
        when(rateLimiter.tryConsume(anyString(), any(), anyInt(), any())).thenReturn(true);

        assertThatThrownBy(() -> otpServiceSendingWith(broken, rateLimiter).requestOtp(ADMIN_EMAIL, IP))
                .isInstanceOf(IllegalStateException.class);
    }
}
