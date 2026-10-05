package com.iitm.beacon.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iitm.beacon.common.crypto.EmailLookupHashService;
import com.iitm.beacon.config.OtpMailer;
import com.iitm.beacon.domain.country.Country;
import com.iitm.beacon.domain.country.CountryRepository;
import com.iitm.beacon.domain.testimonial.Testimonial;
import com.iitm.beacon.domain.testimonial.TestimonialRepository;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
class VisitorAuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TestimonialRepository testimonialRepository;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private EmailLookupHashService emailLookupHashService;

    @MockitoBean
    private OtpMailer otpMailer;

    private String requestAndCaptureCode(String email) throws Exception {
        mockMvc.perform(post("/api/submissions/otp/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OtpRequestRequest(email))))
                .andExpect(status().isAccepted());

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(otpMailer, atLeastOnce()).sendOtp(eq(email), captor.capture());
        return captor.getValue();
    }

    private Testimonial persistTestimonialFor(String email) {
        Country india = countryRepository.findById("IN").orElseThrow();
        Testimonial testimonial = Testimonial.builder()
                .firstName("David")
                .lastName("Jones")
                .rollNumber("GE26Z001")
                .admissionYear(2024)
                .email(email)
                .emailLookupHash(emailLookupHashService.hash(email))
                .country(india)
                .recommendationScore(8)
                .dataProcessingConsent(true)
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
        return testimonialRepository.saveAndFlush(testimonial);
    }

    @Test
    void requestReturns202WithEmptyBody() throws Exception {
        mockMvc.perform(post("/api/submissions/otp/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OtpRequestRequest("newvisitor@example.com"))))
                .andExpect(status().isAccepted())
                .andExpect(content().string(emptyOrNullString()));
    }

    @Test
    void verifyWithCorrectCode_noExistingTestimonial_returnsCreateModeWithNullId() throws Exception {
        String email = "create-mode@example.com";
        String code = requestAndCaptureCode(email);

        mockMvc.perform(post("/api/submissions/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OtpVerifyRequest(email, code))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("CREATE"))
                .andExpect(jsonPath("$.testimonialId").doesNotExist());
    }

    @Test
    void verifyWithCorrectCode_existingTestimonial_returnsEditModeWithTestimonialId() throws Exception {
        String email = "edit-mode@example.com";
        Testimonial existing = persistTestimonialFor(email);
        String code = requestAndCaptureCode(email);

        mockMvc.perform(post("/api/submissions/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OtpVerifyRequest(email, code))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("EDIT"))
                .andExpect(jsonPath("$.testimonialId").value(existing.getId()));
    }

    @Test
    void requestResponse_identicalForExistingAndNonExistingTestimonial() throws Exception {
        String existingEmail = "has-testimonial@example.com";
        String newEmail = "no-testimonial@example.com";
        persistTestimonialFor(existingEmail);

        MvcResult existingResult = mockMvc.perform(post("/api/submissions/otp/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OtpRequestRequest(existingEmail))))
                .andReturn();

        MvcResult newResult = mockMvc.perform(post("/api/submissions/otp/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OtpRequestRequest(newEmail))))
                .andReturn();

        assertThat(existingResult.getResponse().getStatus())
                .isEqualTo(newResult.getResponse().getStatus());
        assertThat(existingResult.getResponse().getContentAsByteArray())
                .isEqualTo(newResult.getResponse().getContentAsByteArray());
    }

    @Test
    void verifySuccessEstablishesSessionWithVisitorRoleAndPrincipal() throws Exception {
        String email = "session-check@example.com";
        String code = requestAndCaptureCode(email);

        MvcResult result = mockMvc.perform(post("/api/submissions/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OtpVerifyRequest(email, code))))
                .andExpect(status().isOk())
                .andReturn();

        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).isNotNull();
        SecurityContext securityContext = (SecurityContext)
                session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(securityContext).isNotNull();
        Authentication authentication = securityContext.getAuthentication();
        assertThat(authentication.getPrincipal()).isEqualTo(email);
        assertThat(authentication.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_VISITOR");
    }

    @Test
    void verifyWithWrongCodeReturns401WithErrorResponseBody() throws Exception {
        String email = "wrong-code@example.com";
        requestAndCaptureCode(email);

        mockMvc.perform(post("/api/submissions/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OtpVerifyRequest(email, "ZZZZZZ"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.path").value("/api/submissions/otp/verify"));
    }

    @Test
    void requestWithBlankEmailReturns400() throws Exception {
        mockMvc.perform(post("/api/submissions/otp/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void requestWithMalformedEmailReturns400() throws Exception {
        mockMvc.perform(post("/api/submissions/otp/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void verifyWithWrongLengthCodeReturns400() throws Exception {
        mockMvc.perform(post("/api/submissions/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"someone@example.com\",\"code\":\"123\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void concurrentVerifyWithSameCorrectCode_exactlyOneSucceeds() throws Exception {
        String email = "concurrent-visitor@example.com";
        String code = requestAndCaptureCode(email);

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    int status = mockMvc.perform(post("/api/submissions/otp/verify")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(new OtpVerifyRequest(email, code))))
                            .andReturn()
                            .getResponse()
                            .getStatus();
                    if (status == 200) {
                        successes.incrementAndGet();
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
        ready.await();
        start.countDown();
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        assertThat(successes.get()).isEqualTo(1);
    }

    // -- GET /api/submissions/session (session ping for session-check.js) --

    private MockHttpSession loggedInVisitorSession(String email) throws Exception {
        String code = requestAndCaptureCode(email);
        MvcResult result = mockMvc.perform(post("/api/submissions/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OtpVerifyRequest(email, code))))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    @Test
    void sessionPing_liveVisitorSession_returns204WithNoBodyAndIsNeverCached() throws Exception {
        MockHttpSession session = loggedInVisitorSession("ping-live@example.com");

        mockMvc.perform(get("/api/submissions/session").session(session))
                .andExpect(status().isNoContent())
                .andExpect(content().string(emptyOrNullString()))
                .andExpect(header().string("Cache-Control", containsString("no-store")));
    }

    @Test
    void sessionPing_expiredVisitorSession_returnsJson401() throws Exception {
        MockHttpSession session = loggedInVisitorSession("ping-expired@example.com");
        session.invalidate();

        mockMvc.perform(get("/api/submissions/session").session(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.path").value("/api/submissions/session"));
    }

    @Test
    void sessionPing_anonymous_returnsJson401() throws Exception {
        mockMvc.perform(get("/api/submissions/session"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void sessionPing_adminSession_returnsJson403() throws Exception {
        var admin = new UsernamePasswordAuthenticationToken(
                "admin@example.com", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

        mockMvc.perform(get("/api/submissions/session").with(authentication(admin)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void sessionPing_isGetOnly() throws Exception {
        var visitor = new UsernamePasswordAuthenticationToken(
                "ping-post@example.com", null, List.of(new SimpleGrantedAuthority("ROLE_VISITOR")));

        mockMvc.perform(post("/api/submissions/session").with(authentication(visitor)))
                .andExpect(status().isForbidden());
    }
}
