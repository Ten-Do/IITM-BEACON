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
class SmtpNotificationMailerTest {

    @Mock
    private JavaMailSender mailSender;

    @Test
    void sendsAnEmailWithTheGivenSubjectAndBody() {
        SmtpNotificationMailer mailer = new SmtpNotificationMailer(mailSender, "no-reply@iitm-beacon.example");

        mailer.send("visitor@example.com", "Your testimonial was approved", "Thanks for sharing your story.");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        SimpleMailMessage sent = captor.getValue();

        assertThat(sent.getFrom()).isEqualTo("no-reply@iitm-beacon.example");
        assertThat(sent.getTo()).containsExactly("visitor@example.com");
        assertThat(sent.getSubject()).isEqualTo("Your testimonial was approved");
        assertThat(sent.getText()).isEqualTo("Thanks for sharing your story.");
    }
}
