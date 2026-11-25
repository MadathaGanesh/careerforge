package com.careerforge.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class WorkspaceRequests {
    private WorkspaceRequests() {}

    record Account(@NotBlank @Size(max = 200) String name) {}
    record Profile(@Size(max = 250) String headline, @Size(max = 250) String location,
                   @Min(0) @Max(80) BigDecimal yearsExperience,
                   @Size(max = 100) List<@NotBlank @Size(max = 120) String> skills,
                   @Size(max = 100) List<@NotBlank @Size(max = 120) String> targetRoles,
                   Map<String, Object> preferences) {}
    record Application(@NotNull UUID jobId, UUID resumeVersionId, @Size(max = 12000) String notes) {}
    record EditApplication(@Size(max = 32) String status, @Size(max = 12000) String notes,
                           @Size(max = 1000) String salaryOffered) {}
    record Reminder(@NotBlank @Size(max = 250) String title, @NotBlank @Size(max = 45) String dueAt,
                    UUID applicationId) {}
    record ReminderStatus(@NotNull Boolean completed) {}
    record SalaryObservation(@NotBlank @Size(max = 250) String role, @NotBlank @Size(max = 250) String location,
                             @Min(0) @Max(80) BigDecimal experienceYears,
                             @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal lowValue,
                             @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal highValue,
                             @Pattern(regexp = "|[A-Za-z]{3}") String currency,
                             @NotBlank @Size(max = 250) String sourceLabel, @Size(max = 2048) String sourceUrl,
                             @NotNull LocalDate observedAt) {}
    record SalaryEstimate(@NotBlank @Size(max = 250) String role, @NotBlank @Size(max = 250) String location,
                          @Pattern(regexp = "|[A-Za-z]{3}") String currency) {}
    record MemoryPreference(@NotNull Boolean enabled) {}
    record Fact(@NotBlank @Size(max = 2000) String description,
                @Size(max = 64) String factType, boolean verifiedByUser) {}
    record Confirmation(String confirmation) {}
    record Notification(String title, String message, UUID userId, String targetUrl, boolean email) {}
}
