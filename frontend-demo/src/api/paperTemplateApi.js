import { API_BASE_URL, ApiError, getStoredToken, request } from "./client";

export const paperTemplateApi = {
  list: (options) => request("/api/v1/paper-templates", options),
  remove: (templateId, options) => request(`/api/v1/paper-templates/${templateId}`, { ...options, method: "DELETE" }),
  create: async ({ name, description, pageWidth, pageHeight, masks, crops, redactions }, file, { token = getStoredToken(), signal } = {}) => {
    const form = new FormData();
    form.append("metadata", JSON.stringify({ name, description, pageWidth, pageHeight, masks, crops, redactions }));
    form.append("file", file, file.name);
    const response = await fetch(`${API_BASE_URL}/api/v1/paper-templates`, {
      method: "POST", signal, headers: { Accept: "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}) }, body: form,
    });
    const payload = (response.headers.get("content-type") ?? "").includes("application/json") ? await response.json() : null;
    if (!response.ok) throw new ApiError(payload?.message ?? `Yêu cầu thất bại (${response.status})`, response.status, payload);
    return payload;
  },
};
