const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080").replace(/\/$/, "");
const storedToken = () => sessionStorage.getItem("exam-grading.access-token") ?? undefined;

export class ApiError extends Error {
  constructor(message, status, payload) {
    super(message);
    this.status = status;
    this.payload = payload;
  }
}

export async function api(path, { token = storedToken(), method = "GET", body, headers = {}, signal } = {}) {
  const response = await fetch(`${API_BASE_URL}${path}`, {
    method,
    signal,
    headers: {
      Accept: "application/json",
      ...(body ? { "Content-Type": "application/json" } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...headers,
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const contentType = response.headers.get("content-type") ?? "";
  const payload = contentType.includes("application/json") ? await response.json() : null;
  if (!response.ok) throw new ApiError(payload?.message ?? `Yêu cầu thất bại (${response.status})`, response.status, payload);
  return payload;
}

async function upload(path, file, { token = storedToken(), signal } = {}) {
  const form = new FormData();
  form.append("file", file);
  const response = await fetch(`${API_BASE_URL}${path}`, {
    method: "POST",
    signal,
    headers: { Accept: "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: form,
  });
  const contentType = response.headers.get("content-type") ?? "";
  const payload = contentType.includes("application/json") ? await response.json() : null;
  if (!response.ok) throw new ApiError(payload?.message ?? `Yêu cầu thất bại (${response.status})`, response.status, payload);
  return payload;
}

export const gradingApi = {
  login: (credentials) => api("/api/v1/auth/login", { method: "POST", body: credentials }),
  listExams: (options) => api("/api/v1/exams", options),
  createExam: (exam, options) => api("/api/v1/exams", { ...options, method: "POST", body: exam }),
  addQuestion: (examId, question, options) => api(`/api/v1/exams/${examId}/questions`, { ...options, method: "POST", body: question }),
  createRubric: (examId, content, options) => api(`/api/v1/exams/${examId}/rubrics`, { ...options, method: "POST", body: { content } }),
  activateRubric: (examId, rubricId, options) => api(`/api/v1/exams/${examId}/rubrics/${rubricId}/activate`, { ...options, method: "POST", body: { confirmed: true } }),
  importRoster: (examId, file, options) => upload(`/api/v1/exams/${examId}/candidates/import`, file, options),
  uploadSubmission: async (examId, candidateId, file, options = {}) => {
    const { templateId, ...requestOptions } = options;
    const initResponse = await api(`/api/v1/exams/${examId}/submissions`, {
      ...requestOptions,
      method: "POST",
      body: {
        candidateId,
        templateId,
        fileName: file.name,
        fileSizeBytes: file.size,
        contentType: file.type || "application/pdf",
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
      const response = await fetch(uploadUrl, { method: "POST", body: form, signal: requestOptions.signal });
      if (!response.ok) {
        const errorText = await response.text().catch(() => "");
        throw new ApiError(`Không thể tải bài thi lên storage (${response.status})`, response.status, errorText ? { message: errorText } : null);
      }
    }

    return api(`/api/v1/submissions/${initResponse.submissionId}/complete`, {
      ...requestOptions,
      method: "POST",
      body: {},
    });
  },
  listCandidates: (examId, options) => api(`/api/v1/exams/${examId}/candidates`, options),
  listSubmissions: (examId, options) => api(`/api/v1/exams/${examId}/submissions`, options),
  listReviews: (examId, options) => api(`/api/v1/reviews?examId=${encodeURIComponent(examId)}`, options),
  finalize: (submissionId, options) => api(`/api/v1/submissions/${submissionId}/finalize`, { ...options, method: "POST" }),
  exportResults: async (examId, options = {}) => {
    const response = await fetch(`${API_BASE_URL}/api/v1/exams/${examId}/results/export`, {
      headers: options.token ? { Authorization: `Bearer ${options.token}` } : {},
    });
    if (!response.ok) throw new ApiError("Không thể xuất kết quả", response.status);
    return response.blob();
  },
};
