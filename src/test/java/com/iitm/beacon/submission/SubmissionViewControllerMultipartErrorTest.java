package com.iitm.beacon.submission;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

/**
 * {@link SubmissionViewController}'s controller-local {@code MultipartException}
 * handler. MockMvc never runs a real multipart parser, so the failure a real
 * over-limit upload raises during argument binding is injected here from the
 * first collaborator the POST handler calls; the real Tomcat path is covered
 * by {@code SubmissionUploadLimitsTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
class SubmissionViewControllerMultipartErrorTest {

    private static final String UPLOAD_ERROR =
            "Your upload was too large or contained too many files. Please try again with fewer or smaller photos.";

    @Autowired
    private MockMvc mockMvc;

    @MockitoSpyBean
    private SubmissionService submissionService;

    private static Authentication visitor(String email) {
        return new UsernamePasswordAuthenticationToken(
                email, null, List.of(new SimpleGrantedAuthority("ROLE_VISITOR")));
    }

    @Test
    void formPost_uploadTooLarge_redirectsBackToFormWithFlashError() throws Exception {
        doThrow(new MaxUploadSizeExceededException(-1L)).when(submissionService).determineMode(anyString());

        mockMvc.perform(multipart("/submissions/form")
                        .param("firstName", "David")
                        .with(authentication(visitor("multipart-too-large@example.com"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/submissions/form"))
                .andExpect(flash().attribute("error", UPLOAD_ERROR));
    }

    @Test
    void formPost_malformedMultipart_redirectsBackToFormWithFlashError() throws Exception {
        doThrow(new MultipartException("Failed to parse multipart servlet request"))
                .when(submissionService)
                .determineMode(anyString());

        mockMvc.perform(multipart("/submissions/form")
                        .param("firstName", "David")
                        .with(authentication(visitor("multipart-malformed@example.com"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/submissions/form"))
                .andExpect(flash().attribute("error", UPLOAD_ERROR));
    }

    @Test
    void formPost_multipartFailure_flashErrorIsRenderedOnTheFollowingFormPage() throws Exception {
        doThrow(new MaxUploadSizeExceededException(-1L)).when(submissionService).determineMode(anyString());
        Authentication visitor = visitor("multipart-follow-redirect@example.com");

        MvcResult redirect = mockMvc.perform(multipart("/submissions/form")
                        .param("firstName", "David")
                        .with(authentication(visitor)))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        mockMvc.perform(get("/submissions/form")
                        .flashAttrs(redirect.getFlashMap())
                        .with(authentication(visitor)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(UPLOAD_ERROR)))
                // Never leak the container/Spring exception text into the page.
                .andExpect(content().string(not(containsString("Failed to parse multipart"))));
    }
}
