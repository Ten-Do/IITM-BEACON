package com.iitm.beacon.adminauth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record OtpRequestRequest(@NotBlank @Email String email) {
}
