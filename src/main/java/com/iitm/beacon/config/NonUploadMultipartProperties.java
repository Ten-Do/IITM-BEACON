package com.iitm.beacon.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Binds {@code beacon.web.non-upload-multipart-max-size} ({@code
 * BEACON_NON_UPLOAD_MULTIPART_MAX_SIZE}): the largest multipart body {@link
 * NonUploadMultipartFilter} lets through to anything but the submission
 * endpoints, whose own limits are the servlet container's multipart
 * settings ({@code spring.servlet.multipart.*}).
 */
@ConfigurationProperties(prefix = "beacon.web")
public record NonUploadMultipartProperties(DataSize nonUploadMultipartMaxSize) {
}
