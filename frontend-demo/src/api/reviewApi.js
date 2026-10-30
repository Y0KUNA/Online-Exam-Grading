import { request } from "./client";

export const reviewApi = {
  listReviews: (examId, options) => request(`/api/v1/reviews?examId=${encodeURIComponent(examId)}`, options),
};
