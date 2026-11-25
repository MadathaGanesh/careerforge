package com.careerforge.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.Map;
import java.util.UUID;

public final class WorkflowRequests {
    private WorkflowRequests() {}

    public record Analyze(@NotNull UUID jobId, @NotNull UUID resumeVersionId) {}
    public record Tailor(@NotNull UUID analysisId) {}
    public record DraftJob(@NotBlank @Size(min = 80, max = 50000) String rawJd,
                           @Size(max = 250) String title, @Size(max = 250) String companyName,
                           @Size(max = 250) String location, @Size(max = 250) String salaryDisclosed,
                           @Size(max = 2048) String jobUrl) {}
    public record PasteResume(@NotBlank @Size(max = 250) String title,
                              @NotBlank @Size(min = 25, max = 100000) String pastedText) {}
    public record ConfirmResume(boolean confirm, @NotNull Map<String, Object> structuredContent,
                                @Size(max = 100000) String contentText) {}
    public record ConfirmJob(boolean confirm, @NotNull Map<String, Object> structuredJd) {}
    public record Export(@NotBlank @Size(max = 8) String format) {}
    public record EditProposal(@NotBlank @Size(max = 2000) String proposedText,
                               @PositiveOrZero Integer revision) {}
    public record DecideProposal(@NotBlank @Size(max = 9) String decision,
                                @PositiveOrZero Integer revision, @Size(max = 2000) String proposedText) {}
    public record GenerateInterview(@NotNull UUID jobId, UUID resumeVersionId) {}
    public record StartInterview(@NotNull UUID jobId) {}
    public record RecordAnswer(@NotNull UUID questionId, @NotBlank @Size(max = 12000) String answer) {}
}
