import { request } from "./client";

export const authApi = {
  login: (credentials) => request("/api/v1/auth/login", { method: "POST", body: credentials }),
  registerGrader: (registration) => request("/api/v1/auth/register/grader", { method: "POST", body: registration }),
  me: (options) => request("/api/v1/users/me", options),
};
