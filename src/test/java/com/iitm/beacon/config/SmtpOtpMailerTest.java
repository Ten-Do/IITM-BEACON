package com.iitm.beacon.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

@ExtendWith(MockitoExtension.class)
class SmtpOtpMailerTest {

    @Mock
    private JavaMailSender mailSender;

    @Test
    void sendsAnEmailWithTheCodeInTheBody() {
        SmtpOtpMailer mailer = new SmtpOtpMailer(mailSender, "no-reply@iitm-beacon.example");

        mailer.sendOtp("visitor@example.com", "ABC234");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        SimpleMailMessage sent = captor.getValue();

        assertThat(sent.getFrom()).isEqualTo("no-reply@iitm-beacon.example");
        assertThat(sent.getTo()).containsExactly("visitor@example.com");
        assertThat(sent.getSubject()).isEqualTo("Your IITM Beacon login code");
        assertThat(sent.getText()).contains("ABC234");
    }
}
