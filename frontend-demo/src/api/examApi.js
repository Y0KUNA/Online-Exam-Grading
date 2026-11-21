import { API_BASE_URL, ApiError, request, upload } from "./client";

export const DEFAULT_PAPER_TEMPLATE_ID = "0a809b8a-4b10-4b64-9305-000000000001";

export const examApi = {
  listExams: (options) => request("/api/v1/exams", options),
  createExam: (exam, options) => request("/api/v1/exams", { ...options, method: "POST", body: exam }),
  setTemplate: (examId, templateId, options) => request(`/api/v1/exams/${examId}/template`, { ...options, method: "PUT", body: { templateId } }),
  addQuestion: (examId, question, options) => request(`/api/v1/exams/${examId}/questions`, { ...options, method: "POST", body: question }),
  createRubric: (examId, content, options) => request(`/api/v1/exams/${examId}/rubrics`, { ...options, method: "POST", body: { content } }),
  activateRubric: (examId, rubricId, options) => request(`/api/v1/exams/${examId}/rubrics/${rubricId}/activate`, { ...options, method: "POST", body: { confirmed: true } }),
  activateExam: (examId, options) => request(`/api/v1/exams/${examId}/activate`, { ...options, method: "POST" }),
  closeExam: (examId, options) => request(`/api/v1/exams/${examId}/close`, { ...options, method: "POST" }),
  importRoster: (examId, file, options) => upload(`/api/v1/exams/${examId}/candidates/import`, file, options),
  uploadSubmission: async (exam, candidate, file, { templateId, token, signal } = {}) => {
    const examId = exam.examId;
    const initResponse = await request(`/api/v1/exams/${examId}/submissions`, {
      token,
      signal,
      method: "POST",
      body: {
        candidateId: candidate.candidateId,
        templateId: templateId ?? DEFAULT_PAPER_TEMPLATE_ID,
        fileName: file.name,
        fileSizeBytes: file.size,
        contentType: file.type || "application/pdf",
        examName: exam.name,
        examTime: exam.examTime ?? exam.time,
        subject: exam.subject,
        candidateName: candidate.fullName,
      },
    });

    if (!initResponse?.submissionId) return initResponse;

    const uploadConfig = initResponse.upload ?? {};
    const uploadUrl = uploadConfig.url ?? uploadConfig.uploadUrl ?? null;
    if (uploadUrl) {
      const form = new FormData();
      for (const [name, value] of Object.entries(uploadConfig.fields ?? {})) {
        form.append(name, String(value));
      }
      form.append("file", file, file.name);

      const rawUpload = await fetch(uploadUrl, {
        method: "POST",
        signal,
        body: form,
      });

      if (!rawUpload.ok) {
        const rawText = await rawUpload.text().catch(() => "");
        throw new ApiError(`Không thể tải bài thi lên storage (${rawUpload.status})`, rawUpload.status, rawText ? { message: rawText } : null);
      }
    }

    return request(`/api/v1/submissions/${initResponse.submissionId}/complete`, {
      token,
      signal,
      method: "POST",
      body: {},
    });
  },
  listCandidates: (examId, options) => request(`/api/v1/exams/${examId}/candidates`, options),
  listSubmissionBindings: (examId, options) => request(`/api/v1/exams/${examId}/submission-bindings`, options),
  exportResults: async (examId, options = {}) => {
    const response = await fetch(`${API_BASE_URL}/api/v1/exams/${examId}/results/export`, {
      headers: options.token ? { Authorization: `Bearer ${options.token}` } : {},
    });
    if (!response.ok) throw new ApiError("Không thể xuất kết quả", response.status);
    return response.blob();
  },
};
