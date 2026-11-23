import { request } from "./client";

export const gradingApi = {
  listSubmissions: (examId, options) => request(`/api/v1/exams/${examId}/submissions`, options),
  assign: (examId, payload, options) => request(`/api/v1/exams/${examId}/assignments`, { ...options, method: "POST", body: payload }),
  listAssignments: (graderId, options) => request(`/api/v1/grading/assignments?graderId=${encodeURIComponent(graderId)}`, options),
  startAttempt: (assignmentId, options) => request(`/api/v1/grading/assignments/${assignmentId}/attempts`, { ...options, method: "POST", body: {} }),
  getAttempt: (attemptId, options) => request(`/api/v1/grading/attempts/${attemptId}`, options),
  getAttemptContext: (attemptId, options) => request(`/api/v1/grading/attempts/${attemptId}/context`, options),
  saveScores: (attemptId, scores, options) => request(`/api/v1/grading/attempts/${attemptId}/scores`, { ...options, method: "PUT", body: { scores } }),
  confirmAttempt: (attemptId, options) => request(`/api/v1/grading/attempts/${attemptId}/confirm`, { ...options, method: "POST" }),
  finalize: (submissionId, options) => request(`/api/v1/submissions/${submissionId}/finalize`, { ...options, method: "POST" }),
};
