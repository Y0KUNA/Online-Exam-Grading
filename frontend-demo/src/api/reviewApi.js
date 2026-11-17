import { request } from "./client";

export const reviewApi = {
  listReviews: (examId, options) => request(`/api/v1/reviews?examId=${encodeURIComponent(examId)}`, options),
  open: (review, options) => request("/api/v1/reviews", { ...options, method: "POST", body: review }),
  addNote: (reviewId, note, options) => request(`/api/v1/reviews/${reviewId}/notes`, { ...options, method: "POST", body: note }),
  decide: (reviewId, decision, options) => request(`/api/v1/reviews/${reviewId}/decision`, { ...options, method: "POST", body: decision }),
  finalize: (examId, results, options) => request(`/api/v1/exams/${examId}/results/finalize`, { ...options, method: "POST", body: { results } }),
};
