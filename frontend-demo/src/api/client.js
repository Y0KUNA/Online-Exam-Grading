const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080").replace(/\/$/, "");

export class ApiError extends Error {
  constructor(message, status, payload) {
    super(message);
    this.status = status;
    this.payload = payload;
  }
}

export const getStoredToken = () => sessionStorage.getItem("exam-grading.access-token") ?? undefined;

export async function request(path, { token = getStoredToken(), method = "GET", body, headers = {}, signal } = {}) {
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

export async function upload(path, file, { token = getStoredToken(), signal } = {}) {
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

export { API_BASE_URL };
