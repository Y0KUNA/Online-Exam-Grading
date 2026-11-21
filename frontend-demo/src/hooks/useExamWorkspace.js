import { useEffect, useMemo, useState } from "react";
import { authApi, examApi, gradingApi, paperTemplateApi, reviewApi } from "../api/index.js";
import { mapExams } from "../utils/examFormat";

const TOKEN_KEY = "exam-grading.access-token";

const toWorkspaceRole = (apiRole) => ({
  GRADER: "grader",
  COUNCIL: "council",
  EXAM_OFFICE: "office",
}[apiRole] ?? null);

const getTokenRole = (token) => JSON.parse(
  atob(token.split(".")[1].replace(/-/g, "+").replace(/_/g, "/")),
).role;

const toItems = (payload) => Array.isArray(payload) ? payload : payload?.items ?? payload?.content ?? [];

export default function useExamWorkspace() {
  const [authToken, setAuthToken] = useState(() => sessionStorage.getItem(TOKEN_KEY));
  const [role, setRole] = useState(() => {
    try { return toWorkspaceRole(getTokenRole(sessionStorage.getItem(TOKEN_KEY))); }
    catch { return null; }
  });
  const [authReady, setAuthReady] = useState(false);
  const [page, setPage] = useState("home");
  const [creating, setCreating] = useState(false);
  const [configuring, setConfiguring] = useState(false);
  const [selectedExamId, setSelectedExamId] = useState(null);
  const [examItems, setExamItems] = useState([]);
  const [submissions, setSubmissions] = useState([]);
  const [reviews, setReviews] = useState([]);
  const [apiError, setApiError] = useState("");
  const [loading, setLoading] = useState(true);
  const [revision, setRevision] = useState(0);
  const [user, setUser] = useState(null);
  const [templates, setTemplates] = useState([]);

  const exams = useMemo(() => mapExams(examItems), [examItems]);
  const selectedExam = exams.find((item) => item.examId === selectedExamId) ?? exams[0];

  useEffect(() => {
    if (!authToken) { setAuthReady(true); return undefined; }
    const aborter = new AbortController();
    authApi.me({ token: authToken, signal: aborter.signal })
      .then((account) => { setUser(account); setAuthReady(true); })
      .catch((cause) => {
        if (cause.name === "AbortError") return;
        sessionStorage.removeItem(TOKEN_KEY);
        setAuthToken(null);
        setRole(null);
        setUser(null);
        setAuthReady(true);
      });
    return () => aborter.abort();
  }, [authToken]);

  useEffect(() => {
    if (!authToken || !authReady) { setLoading(false); return undefined; }
    const aborter = new AbortController();
    examApi.listExams({ signal: aborter.signal })
      .then(async (payload) => {
        const enriched = await Promise.all(toItems(payload).map(async (exam) => {
          const calls = [gradingApi.listSubmissions(exam.examId), reviewApi.listReviews(exam.examId)];
          if (role === "office") calls.unshift(examApi.listCandidates(exam.examId));
          const results = await Promise.all(calls);
          const candidates = role === "office" ? toItems(results[0]) : null;
          const submissionItems = toItems(results[role === "office" ? 1 : 0]);
          const reviewItems = toItems(results[role === "office" ? 2 : 1]);
          return {
            ...exam,
            candidateCount: candidates?.length ?? exam.candidateCount ?? 0,
            submittedCount: submissionItems.length,
            gradedCount: submissionItems.filter((item) => item.status === "COMPLETED").length,
            reviewCount: reviewItems.filter((item) => item.status !== "RESOLVED").length,
          };
        }));
        setExamItems(enriched);
        setSelectedExamId((current) => current ?? enriched[0]?.examId ?? null);
        setApiError("");
      })
      .catch((cause) => {
        if (cause.name !== "AbortError") {
          setExamItems([]);
          setApiError("Không thể tải dữ liệu từ máy chủ.");
        }
      })
      .finally(() => { if (!aborter.signal.aborted) setLoading(false); });
    return () => aborter.abort();
  }, [authToken, role, authReady]);

  useEffect(() => {
    if (!authToken || role !== "office") { setTemplates([]); return undefined; }
    const aborter = new AbortController();
    paperTemplateApi.list({ signal: aborter.signal })
      .then((payload) => setTemplates(toItems(payload)))
      .catch((cause) => { if (cause.name !== "AbortError") setApiError("Không thể tải danh sách mẫu giấy thi."); });
    return () => aborter.abort();
  }, [authToken, role, revision]);

  useEffect(() => {
    if (!selectedExamId) return undefined;
    const aborter = new AbortController();
    Promise.all([
      gradingApi.listSubmissions(selectedExamId, { signal: aborter.signal }),
      reviewApi.listReviews(selectedExamId, { signal: aborter.signal }),
    ])
      .then(([nextSubmissions, nextReviews]) => {
        setSubmissions(toItems(nextSubmissions));
        setReviews(toItems(nextReviews));
      })
      .catch((cause) => {
        if (cause.name !== "AbortError") setApiError("Một số dữ liệu chi tiết chưa tải được từ API.");
      });
    return () => aborter.abort();
  }, [selectedExamId, revision]);

  const login = async (credentials) => {
    const response = await authApi.login(credentials);
    const workspace = toWorkspaceRole(getTokenRole(response.accessToken));
    if (!workspace) throw new Error("Tài khoản không có vai trò hợp lệ trong hệ thống.");
    sessionStorage.setItem(TOKEN_KEY, response.accessToken);
    setAuthToken(response.accessToken);
    setRole(workspace);
    setAuthReady(true);
    try { setUser(await authApi.me({ token: response.accessToken })); }
    catch { setUser(null); }
  };

  const logout = () => {
    sessionStorage.removeItem(TOKEN_KEY);
    setAuthToken(null);
    setUser(null);
    setPage("home");
  };

  const createExam = async (form, rosterFile) => {
    const created = await examApi.createExam(form);
    setExamItems((items) => [created, ...items]);
    setSelectedExamId(created.examId);
    setRevision((value) => value + 1);
    return rosterFile ? examApi.importRoster(created.examId, rosterFile) : null;
  };

  const exportResults = async (examId) => {
    try {
      const file = await examApi.exportResults(examId);
      const url = URL.createObjectURL(file);
      const link = document.createElement("a");
      link.href = url;
      link.download = `ket-qua-${examId}.csv`;
      link.click();
      URL.revokeObjectURL(url);
    } catch (cause) { setApiError(cause.message); }
  };

  const createTemplate = async (metadata, file) => {
    const created = await paperTemplateApi.create(metadata, file);
    setTemplates((items) => [created, ...items]);
    return created;
  };
  const deleteTemplate = async (templateId) => {
    await paperTemplateApi.remove(templateId);
    setTemplates((items) => items.filter((item) => item.templateId !== templateId));
  };

  return {
    authToken, authReady, role, page, creating, configuring, selectedExamId, exams, selectedExam,
    submissions, reviews, templates, apiError, loading, revision, user,
    login, logout, createExam, exportResults, createTemplate, deleteTemplate,
    registerGrader: authApi.registerGrader,
    openExam: (examId) => { setSelectedExamId(examId); setPage("exam"); },
    setPage, setCreating, setConfiguring,
    refreshExam: () => setRevision((value) => value + 1),
  };
}
