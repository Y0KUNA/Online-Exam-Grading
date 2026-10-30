import { request } from "./client";

export const gradingApi = {
  listSubmissions: (examId, options) => request(`/api/v1/exams/${examId}/submissions`, options),
  finalize: (submissionId, options) => request(`/api/v1/submissions/${submissionId}/finalize`, { ...options, method: "POST" }),
};
