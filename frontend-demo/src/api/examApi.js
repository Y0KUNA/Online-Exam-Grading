import { API_BASE_URL, ApiError, request, upload } from "./client";

export const examApi = {
  listExams: (options) => request("/api/v1/exams", options),
  createExam: (exam, options) => request("/api/v1/exams", { ...options, method: "POST", body: exam }),
  addQuestion: (examId, question, options) => request(`/api/v1/exams/${examId}/questions`, { ...options, method: "POST", body: question }),
  createRubric: (examId, content, options) => request(`/api/v1/exams/${examId}/rubrics`, { ...options, method: "POST", body: { content } }),
  activateRubric: (examId, rubricId, options) => request(`/api/v1/exams/${examId}/rubrics/${rubricId}/activate`, { ...options, method: "POST", body: { confirmed: true } }),
  importRoster: (examId, file, options) => upload(`/api/v1/exams/${examId}/candidates/import`, file, options),
  listCandidates: (examId, options) => request(`/api/v1/exams/${examId}/candidates`, options),
  exportResults: async (examId, options = {}) => {
    const response = await fetch(`${API_BASE_URL}/api/v1/exams/${examId}/results/export`, {
      headers: options.token ? { Authorization: `Bearer ${options.token}` } : {},
    });
    if (!response.ok) throw new ApiError("Không thể xuất kết quả", response.status);
    return response.blob();
  },
};
