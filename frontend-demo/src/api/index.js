import { authApi } from "./authApi";
import { examApi } from "./examApi";
import { gradingApi } from "./gradingApi";
import { reviewApi } from "./reviewApi";
import { paperTemplateApi } from "./paperTemplateApi";

// Compatibility facade while pages are progressively moved to focused APIs.
export const api = { ...authApi, ...examApi, ...gradingApi, ...reviewApi, ...paperTemplateApi };
export { authApi, examApi, gradingApi, reviewApi, paperTemplateApi };
