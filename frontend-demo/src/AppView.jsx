import { useEffect, useRef, useState } from "react";
import * as pdfjsLib from "pdfjs-dist";
import pdfWorker from "pdfjs-dist/build/pdf.worker.min.mjs?url";
import "./App.css";
import "./template.css";
import "./rubric.css";
import "./grading-workspace.css";
import { authApi, examApi, gradingApi } from "./api/index.js";
import Button from "./components/Button";
import Modal from "./components/Modal";
import Table from "./components/Table";
import Status from "./components/Status";
import Login from "./pages/Login";
import OfficePage from "./pages/office/OfficePage";
import GraderPage from "./pages/grader/GraderPage";
import CouncilPage from "./pages/council/CouncilPage";
import useExamWorkspace from "./hooks/useExamWorkspace";

const submissionStatusLabel = {
  GRADING: 'Đang chấm',
  READY_FOR_GRADING: 'Sẵn sàng chấm',
  REVIEW_REQUIRED: 'Cần xử lý',
  COMPLETED: 'Đã chấm',
};
const paperRows = (submissions) => submissions.map((submission) => [submission.pseudonym ?? submission.pseudonymValue ?? '—', submission.totalScore?.toFixed?.(2) ?? '—', submissionStatusLabel[submission.status] ?? submission.status ?? 'Chưa chấm']);
const reviewRows = (reviews) => reviews.map((review) => [review.pseudonym ?? review.pseudonymValue ?? '—', review.graderOneScore?.toFixed?.(2) ?? '—', review.graderTwoScore?.toFixed?.(2) ?? '—', review.finalScore?.toFixed?.(2) ?? '—', review.reason ?? review.status ?? '—']);
pdfjsLib.GlobalWorkerOptions.workerSrc = pdfWorker;
function Header({ crumb, title, children, compact = false }) {
  return (
    <header className={`topbar${compact ? " topbar-compact" : ""}`}>
      <div>
        <p className="crumb">{crumb}</p>
        <h1>{title}</h1>
      </div>
      <div className="top-actions">
        {children}
        <button className="bell">
          ♧<i />
        </button>
        <span className="avatar">NT</span>
      </div>
    </header>
  );
}
function Info({ exam }) {
  return (
    <div className="info">
      <div>
        <span>Môn thi</span>
        <b>{exam.subject}</b>
      </div>
      <div>
        <span>Thời gian thi</span>
        <b>{exam.time}</b>
      </div>
      <div>
        <span>Khối / Lớp</span>
        <b>{exam.classes}</b>
      </div>
      <div>
        <span>Chấm chéo</span>
        <b>{exam.crossGradingEnabled ? `${exam.graderCount} giám khảo · ngưỡng ${Number(exam.differenceThreshold ?? 0).toFixed(2)}` : "Không chấm chéo"}</b>
      </div>
    </div>
  );
}
const accessTypes = {
  office: { apiRole: "EXAM_OFFICE", icon: "▦", title: "Phòng khảo thí", detail: "Quản lý kỳ thi, danh sách thí sinh và kết quả." },
  council: { apiRole: "COUNCIL", icon: "♙", title: "Hội đồng chấm thi", detail: "Rà soát chênh lệch và quyết định điểm chốt." },
  grader: { apiRole: "GRADER", icon: "✎", title: "Giám khảo", detail: "Nhận phân công và chấm bài độc lập." },
};
function RubricStructureEditor({ examId, action }) {
  const [items, setItems] = useState([]);
  const [loaded, setLoaded] = useState(false);
  const nodeId = () => crypto.randomUUID();
  const normalize = (nodes) => nodes.map((node) => ({ id: nodeId(), content: node.content ?? "", maxScore: node.maxScore ?? "", children: normalize(node.children ?? []) }));
  useEffect(() => { let cancelled = false; examApi.listQuestions(examId).then((nodes) => { if (!cancelled) setItems(normalize(Array.isArray(nodes) ? nodes : [])); }).catch(() => { if (!cancelled) setItems([]); }).finally(() => { if (!cancelled) setLoaded(true); }); return () => { cancelled = true; }; }, [examId]);
  const change = (id, field, value, nodes = items) => nodes.map((node) => node.id === id ? { ...node, [field]: value } : { ...node, children: change(id, field, value, node.children) });
  const appendChild = (id, nodes = items) => nodes.map((node) => node.id === id ? { ...node, children: [...node.children, { id: nodeId(), content: "", maxScore: "", children: [] }] } : { ...node, children: appendChild(id, node.children) });
  const remove = (id, nodes = items) => nodes.filter((node) => node.id !== id).map((node) => ({ ...node, children: remove(id, node.children) }));
  const maximum = (node) => node.children.length ? node.children.reduce((sum, child) => sum + maximum(child), 0) : Number(node.maxScore || 0);
  const toPayload = (nodes, prefix = "") => nodes.map((node, index) => {
    const suffix = prefix ? String.fromCharCode(97 + index) : String(index + 1);
    const code = `${prefix}${suffix}`;
    const children = toPayload(node.children, code);
    return children.length ? { code, content: node.content.trim(), children } : { code, content: node.content.trim(), maxScore: Number(node.maxScore) };
  });
  const save = (event) => {
    event.preventDefault();
    const payload = toPayload(items);
    const invalid = (nodes) => nodes.some((node) => !node.content || (!node.children && (!Number.isFinite(node.maxScore) || node.maxScore < 0)) || (node.children && invalid(node.children)));
    if (!payload.length || invalid(payload)) { action(() => Promise.reject(new Error("Mỗi câu/ý cần có nội dung; các ý cuối cần điểm tối đa không âm.")), ""); return; }
    action(() => examApi.replaceQuestionTree(examId, payload), "Đã lưu cấu trúc rubric");
  };
  const rows = (nodes, depth = 0) => nodes.map((node, index) => <div className="rubric-editor-row" key={node.id} style={{ marginLeft: `${depth * 20}px` }}><b>{depth ? "Ý" : "Câu"} {depth ? String.fromCharCode(97 + index) : index + 1}</b><input value={node.content} placeholder={depth ? "Nội dung ý nhỏ" : "Nội dung câu hỏi"} onChange={(event) => setItems(change(node.id, "content", event.target.value))} /><input type="number" min="0" step=".25" disabled={node.children.length > 0} value={node.children.length ? maximum(node) : node.maxScore} placeholder="Điểm" onChange={(event) => setItems(change(node.id, "maxScore", event.target.value))} /><small>{node.children.length ? "Tự tính từ ý con" : "Điểm tối đa"}</small><button type="button" onClick={() => setItems(appendChild(node.id))}>+ Ý</button><button type="button" className="danger" onClick={() => setItems(remove(node.id))}>Xóa</button>{node.children.length > 0 && <div className="rubric-editor-children">{rows(node.children, depth + 1)}</div>}</div>);
  return <section><b>Cấu trúc câu hỏi và ý nhỏ</b><small>Thêm câu và các ý con trực tiếp. Chỉ ý cuối mới nhập điểm; điểm câu cha tự cộng.</small><form className="rubric-editor" onSubmit={save}>{!loaded ? <p>Đang tải cấu trúc rubric…</p> : <>{rows(items)}<Button type="button" className="secondary" onClick={() => setItems((current) => [...current, { id: nodeId(), content: "", maxScore: "", children: [] }])}>＋ Thêm câu</Button><div className="actions"><Button type="submit">Lưu cấu trúc rubric</Button></div></>}</form></section>;
}
function SetupExam({ examId, initialTemplateId, templates, close, refresh }) {
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");
  const [rubrics, setRubrics] = useState([]);
  const [rubricUrl, setRubricUrl] = useState("");
  const [selectedRubricId, setSelectedRubricId] = useState("");
  const [viewingRubric, setViewingRubric] = useState("");
  const [uploadingRubric, setUploadingRubric] = useState(false);
  const [activatingRubric, setActivatingRubric] = useState("");
  const action = async (work, ok) => { setError(""); try { await work(); setMessage(ok); refresh(); } catch (cause) { setError(cause.message); } };
  const loadRubrics = async () => {
    const items = await examApi.listRubrics(examId);
    setRubrics(Array.isArray(items) ? items : []);
    return Array.isArray(items) ? items : [];
  };
  const showRubric = async (rubricId) => {
    setViewingRubric(rubricId); setSelectedRubricId(rubricId); setError("");
    try {
      const source = await examApi.rubricPdf(examId, rubricId);
      setRubricUrl(source.url);
    } catch (cause) { setError(cause.message || "Không thể tải PDF hướng dẫn chấm."); }
    finally { setViewingRubric(""); }
  };
  useEffect(() => { let cancelled = false; examApi.listRubrics(examId).then((items) => { if (!cancelled) setRubrics(Array.isArray(items) ? items : []); }).catch((cause) => { if (!cancelled) setError(cause.message || "Không thể tải danh sách rubric."); }); return () => { cancelled = true; }; }, [examId]);
  const uploadRubric = async (event) => {
    event.preventDefault();
    const file = new FormData(event.currentTarget).get("rubricPdf");
    if (!(file instanceof File) || file.size === 0) { setError("Hãy chọn tệp PDF hướng dẫn chấm."); return; }
    setUploadingRubric(true); setError("");
    try {
      const uploaded = await examApi.uploadRubricPdf(examId, file);
      await loadRubrics(); await showRubric(uploaded.rubricId);
      setMessage("Đã tải rubric PDF lên MinIO"); refresh();
    } catch (cause) { setError(cause.message || "Không thể tải rubric PDF lên MinIO."); }
    finally { setUploadingRubric(false); }
  };
  const activateRubric = async (rubric) => {
    setActivatingRubric(rubric.rubricId); setError("");
    try {
      await examApi.activateRubric(examId, rubric.rubricId);
      await loadRubrics(); await showRubric(rubric.rubricId);
      setMessage(`Đã kích hoạt ${rubric.fileName}. PDF này sẽ được dùng khi chấm bài.`); refresh();
    } catch (cause) { setError(cause.message || "Không thể kích hoạt rubric PDF."); }
    finally { setActivatingRubric(""); }
  };
  return <Modal title="Thiết lập kỳ thi" close={close}><div className="form">
    <section><b>Mẫu giấy thi *</b><small>Phải có mẫu trước khi tải bài thi để hệ thống che đúng vùng thông tin.</small><form className="compact" onSubmit={(e)=>{e.preventDefault();const templateId=new FormData(e.currentTarget).get("templateId");if(!templateId){setError("Hãy chọn mẫu giấy thi.");return;}action(()=>examApi.setTemplate(examId,templateId),"Đã cập nhật mẫu giấy thi");}}><label>Mẫu áp dụng<select name="templateId" required defaultValue={initialTemplateId ?? ""}><option value="" disabled>Chọn mẫu giấy thi</option>{templates.map((template)=><option key={template.templateId} value={template.templateId}>{template.name}</option>)}</select></label><div className="actions"><Button type="submit" disabled={!templates.length}>Lưu mẫu giấy thi</Button></div></form></section>
    <section><b>Hướng dẫn chấm PDF</b><small>PDF gốc được tải qua Gateway vào bucket MinIO riêng. Hãy kích hoạt một PDF để giám khảo dùng nó khi chấm.</small><form onSubmit={uploadRubric}><label className="upload">⇧ <b>Chọn tệp PDF</b><input name="rubricPdf" type="file" accept="application/pdf,.pdf" required /></label><div className="actions"><Button type="submit" disabled={uploadingRubric}>{uploadingRubric ? "Đang tải lên…" : "Tải rubric PDF"}</Button></div></form>{rubrics.filter((rubric) => rubric.fileName).length > 0 && <div className="rubric-pdf-picker"><b>PDF đã tải</b>{rubrics.filter((rubric) => rubric.fileName).map((rubric) => <div className="rubric-pdf-item" key={rubric.rubricId}><Button type="button" className={rubric.rubricId === selectedRubricId ? "" : "secondary"} disabled={viewingRubric === rubric.rubricId} onClick={() => showRubric(rubric.rubricId)}>{viewingRubric === rubric.rubricId ? "Đang mở…" : rubric.fileName}</Button><span className={rubric.status === "ACTIVE" ? "status da-cham" : "status"}>{rubric.status === "ACTIVE" ? "Đang dùng" : rubric.status === "UPLOADING" ? "Đang tải" : "Bản nháp"}</span>{rubric.status === "DRAFT" && <Button type="button" disabled={activatingRubric === rubric.rubricId} onClick={() => activateRubric(rubric)}>{activatingRubric === rubric.rubricId ? "Đang kích hoạt…" : "Kích hoạt"}</Button>}</div>)}</div>}{rubricUrl && <div className="rubric-pdf-preview"><div><b>Xem trước hướng dẫn chấm</b><Button type="button" className="secondary" onClick={() => window.open(rubricUrl, "_blank", "noopener,noreferrer")}>Mở tab mới</Button></div><iframe title="PDF hướng dẫn chấm" src={rubricUrl} /></div>}</section>
    <RubricStructureEditor examId={examId} action={action} />
    <section><b>Tạo rubric thủ công</b><form onSubmit={(e)=>{e.preventDefault();const f=new FormData(e.currentTarget);action(async()=>{const created=await examApi.createRubric(examId,JSON.parse(f.get("content")));await examApi.activateRubric(examId,created.rubricId);},"Đã tạo và kích hoạt rubric");}}><label className="feedback">Nội dung JSON<textarea name="content" defaultValue={'{"instructions":"Chấm theo đáp án đã phê duyệt"}'} /></label><div className="actions"><Button type="submit">Lưu rubric</Button></div></form></section>
    <section><b>Import danh sách thí sinh (CSV)</b><form onSubmit={(e)=>{e.preventDefault();const file=new FormData(e.currentTarget).get("file");if(!(file instanceof File)||file.size===0){setError("Hãy chọn tệp CSV trước khi import.");return;}action(()=>examApi.importRoster(examId,file),"Đã import roster");}}><label className="upload">⇧ <b>Chọn tệp CSV</b><input name="file" type="file" accept=".csv,text/csv" required /></label><small>Header bắt buộc: candidateNumber, fullName; tùy chọn: className.</small><div className="actions"><Button type="submit">Import danh sách</Button></div></form></section>
    {message&&<p className="success">{message}</p>}{error&&<p className="form-error">{error}</p>}
  </div></Modal>;
}
function CreateModal({ close, onCreated, templates }) {
  const [cross, setCross] = useState(true);
  const [done, setDone] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [rosterResult, setRosterResult] = useState(null);
  if (done)
    return (
      <Modal title="Tạo kỳ thi mới" close={close}>
        <div className="done">
          <b>✓</b>
          <h3>Đã tạo kỳ thi</h3>
          <p>
            Kỳ thi đang ở trạng thái nháp. {rosterResult ? `Đã import ${rosterResult.inserted} thí sinh${rosterResult.rejected ? `, ${rosterResult.rejected} dòng chưa hợp lệ` : ""}.` : "Bạn có thể bổ sung danh sách thí sinh và cấu hình trước khi kích hoạt."}
          </p>
          <Button onClick={close}>Hoàn tất</Button>
        </div>
      </Modal>
    );
  return (
    <Modal title="Tạo kỳ thi mới" close={close}>
      <form
        className="form"
        onSubmit={async (e) => {
          e.preventDefault();
          const form = new FormData(e.currentTarget);
          setSaving(true); setError("");
          try {
            const rosterFile = form.get("rosterFile");
            const result = await onCreated({ name: form.get("name"), subject: form.get("subject"), examTime: form.get("examTime"), gradeLevel: form.get("gradeLevel"), templateId: form.get("templateId"), crossGradingEnabled: cross, graderCount: cross ? Number(form.get("graderCount")) : 1, differenceThreshold: cross ? Number(form.get("differenceThreshold")) : 0 }, rosterFile instanceof File && rosterFile.size > 0 ? rosterFile : null);
            setRosterResult(result);
            setDone(true);
          } catch (cause) { setError(cause.message); } finally { setSaving(false); }
        }}
      >
        <div className="form-grid">
          <label>
            Tên kỳ thi *
            <input name="name" required placeholder="Ví dụ: Thi kết thúc học phần" />
          </label>
          <label>
            Môn thi *<input name="subject" required placeholder="Chọn hoặc nhập môn thi" />
          </label>
          <label>
            Thời gian thi *<input name="examTime" required type="datetime-local" />
          </label>
          <label>
            Khối / Lớp *<input name="gradeLevel" required placeholder="Ví dụ: K66 · Lớp 01–08" />
          </label>
        </div>
        <section>
          <div>
            <b>Đáp án & hướng dẫn chấm</b>
            <small>Nhập file DOCX để tạo rubric</small>
          </div>
          <label className="upload">
            ⇧ <b>Tải tệp DOCX</b>
            <input type="file" accept=".doc,.docx" />
          </label>
        </section>
        <section>
          <div className="switch-line">
            <div>
              <b>Cấu hình chấm chéo</b>
              <small>Mỗi bài được chấm độc lập bởi nhiều giám khảo</small>
            </div>
            <button
              className={cross ? "switch on" : "switch"}
              type="button"
              onClick={() => setCross(!cross)}
            >
              <i />
            </button>
          </div>
          {cross && (
            <div className="form-grid compact">
              <label>
                Số lượng giám khảo
                <select name="graderCount" defaultValue="2">
                  <option>2</option>
                  <option>3</option>
                </select>
              </label>
              <label>
                Ngưỡng chênh lệch điểm
                <input name="differenceThreshold" type="number" defaultValue="1.00" step=".25" />
              </label>
            </div>
          )}
        </section>
        <section className="form-grid">
          <label>
            Mẫu giấy thi *
            <select name="templateId" defaultValue="" required>
              <option value="" disabled>
                Chọn mẫu giấy thi
              </option>
              {templates.map((template) => <option key={template.templateId} value={template.templateId}>{template.name}</option>)}
            </select>
          </label>
          <div style={{ fontSize: "11px", fontWeight: 750 }}>
            Danh sách thí sinh (CSV)
            <label className="file" htmlFor="rosterFile">⇧ Import roster</label>
            <input id="rosterFile" name="rosterFile" type="file" accept=".csv,text/csv" />
            <small>Header: candidateNumber, fullName, className (tùy chọn)</small>
          </div>
        </section>
        <div className="actions">
          {error && <p className="form-error">{error}</p>}
          <Button className="secondary" onClick={close}>
            Hủy
          </Button>
          <Button type="submit" disabled={saving}>{saving ? "Đang tạo…" : "Tạo kỳ thi"}</Button>
        </div>
      </form>
    </Modal>
  );
}
function ExamCard({ item, open, council = false }) {
  return (
    <button className="exam-card" onClick={() => open(item.examId)}>
      <div className="card-top">
        <span className="cal">
          {item.day}<small>{item.month}</small>
        </span>
        <div>
          <p className="exam-id">{item.id}</p>
          <h3>{item.name}</h3>
          <small>{item.subject}</small>
        </div>
        <i>→</i>
      </div>
      <div className={`stats ${council ? "council" : ""}`}>
        {council ? (
          <>
            <div>
              <b>{item.graded}</b>
              <span>đã chấm</span>
            </div>
            <div className="warn">
              <b>{item.issues}</b>
              <span>cần xử lý</span>
            </div>
          </>
        ) : (
          <>
            {[
              ["thí sinh", item.candidates],
              ["đã nộp", item.submitted],
              ["đã chấm", item.graded],
              ["vấn đề mở", item.issues],
            ].map(([a, b]) => (
              <div className={a === "vấn đề mở" && b ? "warn" : ""} key={a}>
                <b>{b}</b>
                <span>{a}</span>
              </div>
            ))}
          </>
        )}
      </div>
    </button>
  );
}
function OfficeHome({ open, create, exams }) {
  return (
    <>
      <Header crumb="PHÒNG KHẢO THÍ · TỔNG QUAN" title="Kỳ thi của bạn">
        <Button onClick={create}>＋ Tạo kỳ thi mới</Button>
      </Header>
      <main className="content">
        <div className="intro">
          <div>
            <h2>Quản lý kỳ thi</h2>
            <p>Theo dõi tiến độ tiếp nhận, chấm bài và các vấn đề cần xử lý.</p>
          </div>
          <p>
            ● Hoạt động　<span>● Cần xử lý</span>
          </p>
        </div>
        <div className="exam-grid">
          {exams.map((x) => (
            <ExamCard item={x} open={open} key={x.id} />
          ))}
        </div>
      </main>
    </>
  );
}
function TemplateEditor({ close, onCreated }) {
  const [file, setFile] = useState(null);
  const [step, setStep] = useState("form");
  const [cuts, setCuts] = useState([]);
  const [crops, setCrops] = useState([]);
  const [tool, setTool] = useState("cut");
  const [previewPage, setPreviewPage] = useState(1);
  const [pageCount, setPageCount] = useState(1);
  const [draft, setDraft] = useState(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const canvasRef = useRef(null);
  const activeMask = useRef(null);
  const [rendering, setRendering] = useState(false);
  useEffect(() => {
    if (step !== "mask" || !file || !canvasRef.current) return undefined;
    let cancelled = false; let documentTask;
    const render = async () => {
      setRendering(true);
      try {
        const data = new Uint8Array(await file.arrayBuffer());
        documentTask = pdfjsLib.getDocument({ data });
        const pdf = await documentTask.promise;
        setPageCount(pdf.numPages);
        const page = await pdf.getPage(Math.min(previewPage, pdf.numPages));
        const raw = page.getViewport({ scale: 1 });
        const scale = Math.min(1.25, 700 / raw.width);
        const viewport = page.getViewport({ scale });
        const canvas = canvasRef.current;
        if (cancelled || !canvas) return;
        canvas.width = Math.ceil(viewport.width); canvas.height = Math.ceil(viewport.height);
        await page.render({ canvasContext: canvas.getContext("2d"), viewport }).promise;
      } catch (cause) { if (!cancelled) setError(`Không thể xem trước PDF: ${cause.message || "lỗi không xác định"}`); }
      finally { if (!cancelled) setRendering(false); }
    };
    render();
    return () => { cancelled = true; documentTask?.destroy(); };
  }, [step, file, previewPage]);
  const startMarking = (event) => {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    if (!file) { setError("Hãy tải lên tệp PDF mẫu trước khi tiếp tục."); return; }
    setDraft({ name: data.get("name"), description: data.get("description") });
    setError(""); setStep("mask");
  };
  const position = (event) => {
    const canvasBounds = canvasRef.current?.getBoundingClientRect();
    const bounds = canvasBounds?.width && canvasBounds?.height
      ? canvasBounds
      : event.currentTarget.getBoundingClientRect();
    return { x: Math.max(0, Math.min(1, (event.clientX - bounds.left) / bounds.width)), y: Math.max(0, Math.min(1, (event.clientY - bounds.top) / bounds.height)) };
  };
  const boundedRegion = ({ x, y, width, height }) => {
    const left = Math.max(0, Math.min(1, x));
    const top = Math.max(0, Math.min(1, y));
    return {
      x: left,
      y: top,
      width: Math.max(0, Math.min(1 - left, width)),
      height: Math.max(0, Math.min(1 - top, height)),
    };
  };
  // A cut must remove an edge strip so the remaining page is still rectangular.
  // Always snap it to the nearest horizontal page boundary before persisting.
  const snapCutToPageEdge = ({ x, y, width, height }) => {
    const region = boundedRegion({ x, y, width, height });
    const right = region.x + region.width;
    if (region.x <= 1 - right) return { x: 0, y: 0, width: right, height: 1 };
    return { x: region.x, y: 0, width: 1 - region.x, height: 1 };
  };
  const beginMask = (event) => {
    if (rendering) return;
    const { x, y } = position(event);
    if (tool === "split") {
      setCrops((current) => [...current.filter((split) => split.page !== previewPage), { id: crypto.randomUUID(), page: previewPage, x }]);
      return;
    }
    const id = crypto.randomUUID(); activeMask.current = { id, startX: x, startY: y, tool };
    event.currentTarget.setPointerCapture(event.pointerId);
    setCuts((current) => [...current, { id, page: previewPage, x, y, width: 0, height: 0 }]);
  };
  const resizeMask = (event) => {
    const active = activeMask.current;
    if (!active || !event.currentTarget.hasPointerCapture(event.pointerId)) return;
    const { x, y } = position(event);
    const resize = (region) => region.id === active.id ? {
      ...region,
      ...snapCutToPageEdge({
        x: Math.min(active.startX, x), y: Math.min(active.startY, y),
        width: Math.abs(x - active.startX), height: Math.abs(y - active.startY),
      }),
    } : region;
    setCuts((current) => current.map(resize));
  };
  const endMarking = (event) => {
    const active = activeMask.current;
    activeMask.current = null;
    if (active) setCuts((current) => current.filter((cut) => cut.id !== active.id || cut.width > 0.005));
    event.currentTarget.releasePointerCapture?.(event.pointerId);
  };
  const save = async () => {
    if (!crops.length) { setError("Hãy bấm để đặt ít nhất một đường chia trang."); return; }
    const savedCuts = cuts.filter((cut) => cut.width > 0.005);
    if (!savedCuts.length) { setError("Hãy kéo chuột để đánh dấu ít nhất một vùng cần cắt bỏ."); return; }
    setSaving(true); setError("");
    try {
      await onCreated({ ...draft, pageWidth: 595, pageHeight: 842,
        cuts: savedCuts.map(({ page, ...region }) => ({ page, ...snapCutToPageEdge(region) })),
        crops: crops.flatMap(({ page, x }) => {
          const splitX = Math.max(0, Math.min(1, x));
          return [{ page, order: page === 1 ? 4 : page * 2 - 2, x: 0, y: 0, width: splitX, height: 1 }, { page, order: page === 1 ? 1 : page * 2 - 1, x: splitX, y: 0, width: 1 - splitX, height: 1 }];
        }) }, file);
      close();
    } catch (cause) { setError(cause.message || "Không thể lưu mẫu giấy thi."); } finally { setSaving(false); }
  };
  return <Modal title={step === "form" ? "Thêm mẫu giấy thi" : "Cắt vùng thông tin trên mẫu"} close={close}>
    {step === "form" ? <form className="form" onSubmit={startMarking}>
      <div className="form-grid">
        <label>Tên mẫu giấy thi *<input name="name" required placeholder="Ví dụ: Tự luận A4 - học kỳ 1" /></label>
        <label>Mô tả<input name="description" placeholder="Ví dụ: 2 trang, dùng cho môn tự luận" /></label>
      </div>
      <section><div><b>Tệp PDF mẫu *</b><small>Tệp chỉ được giữ tạm trên trình duyệt cho đến khi bạn lưu mẫu.</small></div>
        <label className="upload">⇧ <b>{file ? file.name : "Tải lên PDF mẫu"}</b><input type="file" accept="application/pdf,.pdf" onChange={(event) => setFile(event.target.files?.[0] ?? null)} /></label>
      </section>
      {error && <p className="form-error">{error}</p>}<div className="actions"><Button className="secondary" onClick={close}>Hủy</Button><Button type="submit">Tiếp tục đánh dấu</Button></div>
    </form> : <div className="template-editor">
      <div className="actions"><Button type="button" className={tool === "cut" ? "" : "secondary"} onClick={() => setTool("cut")}>Cắt bỏ vùng đánh dấu</Button><Button type="button" className={tool === "split" ? "" : "secondary"} onClick={() => setTool("split")}>Đường chia trang</Button>{pageCount > 1 && <><Button type="button" className="secondary" disabled={previewPage === 1} onClick={() => setPreviewPage((page) => page - 1)}>← Mặt trước</Button><span>Mặt {previewPage}/{pageCount}</span><Button type="button" className="secondary" disabled={previewPage === pageCount} onClick={() => setPreviewPage((page) => page + 1)}>Mặt sau →</Button></>}</div>
      <p>{tool === "cut" ? "Kéo chọn dải thông tin cần bỏ. Khi kéo, vùng sẽ tự kéo dài hết chiều cao và bám vào biên gần nhất." : "Bấm vào vị trí cần chia dọc. Mỗi mặt chỉ có một đường chia."}</p>
      <div className="pdf-preview" onPointerDown={beginMask} onPointerMove={resizeMask} onPointerUp={endMarking}>
        <canvas ref={canvasRef} aria-label="Trang đầu PDF mẫu" />
        <span className="pdf-page-boundary" aria-hidden="true" />
        {rendering && <span className="pdf-rendering">Đang dựng trang PDF…</span>}
        {cuts.filter((cut) => cut.page === previewPage).map((cut) => <span key={cut.id} className="mask-region" style={{ left: `${cut.x * 100}%`, top: "0", width: `${cut.width * 100}%`, height: "100%" }} />)}
        {crops.filter((split) => split.page === previewPage).map((split) => <span key={split.id} className="mask-region" style={{ left: `${split.x * 100}%`, top: "0", width: "0", height: "100%" }} />)}
      </div>
      <div className="mask-list"><b>{tool === "cut" ? `${cuts.length} vùng cắt bỏ` : `${crops.length} đường chia`}</b>{tool === "cut" ? cuts.map((cut, index) => <button type="button" key={cut.id} onClick={() => setCuts((current) => current.filter((item) => item.id !== cut.id))}>Xóa vùng {index + 1}</button>) : crops.map((split) => <button type="button" key={split.id} onClick={() => setCrops((current) => current.filter((item) => item.id !== split.id))}>Xóa đường chia mặt {split.page}</button>)}</div>
      {error && <p className="form-error">{error}</p>}<div className="actions"><Button className="secondary" onClick={() => setStep("form")}>← Quay lại</Button><Button onClick={save} disabled={saving}>{saving ? "Đang tải lên…" : "Lưu và tải lên backend"}</Button></div>
    </div>}
  </Modal>;
}
function TemplateList({ templates, create, remove }) {
  const [deleting, setDeleting] = useState(null);
  const [error, setError] = useState("");
  const destroy = async (template) => {
    if (!window.confirm(`Xóa mẫu “${template.name}”? Thao tác này không thể hoàn tác.`)) return;
    setDeleting(template.templateId); setError("");
    try { await remove(template.templateId); } catch (cause) { setError(cause.message || "Không thể xóa mẫu giấy thi."); } finally { setDeleting(null); }
  };
  return <><Header crumb="PHÒNG KHẢO THÍ / MẪU GIẤY THI" title="Quản lý mẫu giấy thi"><Button onClick={create}>＋ Thêm mẫu giấy thi</Button></Header><main className="content">
    <div className="intro"><div><h2>Mẫu giấy thi</h2><p>Tạo mẫu PDF và đánh dấu các dải thông tin cần cắt bỏ để ẩn danh bài làm trước khi chấm.</p></div></div>
    {error && <p className="form-error">{error}</p>}
    <section className="table-box"><Table headers={["Tên mẫu", "Mô tả", "Kích thước", "Vùng cắt", "Ngày tạo", ""]} rows={templates.map((template) => [template.name, template.description || "—", `${template.pageWidth} × ${template.pageHeight} pt`, `${template.maskCount ?? template.masks?.length ?? 0} vùng`, template.createdAt ? new Date(template.createdAt).toLocaleDateString("vi-VN") : "—"])} action={(row, index) => { const template = templates[index]; return <Button className="danger" disabled={deleting === template.templateId} onClick={() => destroy(template)}>{deleting === template.templateId ? "Đang xóa…" : "Xóa"}</Button>; }} />
      {!templates.length && <div className="empty-state">Chưa có mẫu giấy thi. Hãy thêm mẫu PDF đầu tiên của bạn.</div>}
    </section>
  </main></>;
}
function AssignmentModal({ exam, submissions, close, refresh }) {
  const [graders, setGraders] = useState([]); const [graderIds, setGraderIds] = useState([]); const [submissionIds, setSubmissionIds] = useState([]); const [error, setError] = useState(""); const [saving, setSaving] = useState(false);
  const ready = submissions.filter((item) => item.status === "READY_FOR_GRADING");
  const required = exam.crossGradingEnabled ? Number(exam.graderCount ?? 2) : 1;
  useEffect(() => { const aborter = new AbortController(); authApi.listGraders({ signal: aborter.signal }).then((items) => setGraders(Array.isArray(items) ? items : items.items ?? [])).catch((cause) => { if (cause.name !== "AbortError") setError(cause.message || "Không thể tải danh sách giám khảo."); }); return () => aborter.abort(); }, []);
  const toggle = (setter, value) => setter((items) => items.includes(value) ? items.filter((item) => item !== value) : [...items, value]);
  const submit = async () => { if (!submissionIds.length) { setError("Hãy chọn ít nhất một bài đã sẵn sàng chấm."); return; } if (graderIds.length !== required) { setError(`Kỳ thi này yêu cầu chọn đúng ${required} giám khảo.`); return; } setSaving(true); setError(""); try { await gradingApi.assign(exam.examId, { submissionIds, graderIds }); refresh(); close(); } catch (cause) { setError(cause.message || "Không thể tạo phân công."); } finally { setSaving(false); } };
  return <Modal title="Phân công chấm bài" close={close}><div className="form assignment-form"><p>Chọn bài đã ẩn danh và đúng số giám khảo theo cấu hình kỳ thi.</p><section><b>Bài sẵn sàng chấm ({ready.length})</b>{!ready.length ? <p className="empty-state">Chưa có bài nào đạt trạng thái “Sẵn sàng chấm”.</p> : <div className="choice-list">{ready.map((item) => <label key={item.submissionId}><input type="checkbox" checked={submissionIds.includes(item.submissionId)} onChange={() => toggle(setSubmissionIds, item.submissionId)} /> {item.pseudonym ?? item.submissionId}</label>)}</div>}</section><section><b>Giám khảo ({graderIds.length}/{required})</b><div className="choice-list">{graders.map((grader) => <label key={grader.userId}><input type="checkbox" checked={graderIds.includes(grader.userId)} onChange={() => toggle(setGraderIds, grader.userId)} disabled={!graderIds.includes(grader.userId) && graderIds.length >= required} /> {grader.fullName} <small>({grader.username})</small></label>)}</div>{!graders.length && !error && <p className="empty-state">Chưa có tài khoản Giám khảo hoạt động.</p>}</section>{error && <p className="form-error">{error}</p>}<div className="actions"><Button className="secondary" onClick={close}>Hủy</Button><Button onClick={submit} disabled={saving || !ready.length}>{saving ? "Đang phân công…" : "Xác nhận phân công"}</Button></div></div></Modal>;
}
function OfficeExam({ exam, back, finalize, exportResults, configure, revision, submissions, refresh }) {
  const [assigning, setAssigning] = useState(false);
  const [uploadedCandidates, setUploadedCandidates] = useState({});
  const [uploadingCandidateId, setUploadingCandidateId] = useState(null);
  const [retryingSubmissionId, setRetryingSubmissionId] = useState(null);
  const [candidates, setCandidates] = useState([]);
  const [bindings, setBindings] = useState([]);
  const [candidateError, setCandidateError] = useState("");
  const [candidatesLoading, setCandidatesLoading] = useState(true);
  useEffect(() => {
    const aborter = new AbortController();
    setCandidatesLoading(true); setCandidateError("");
    Promise.all([
      examApi.listCandidates(exam.examId, { signal: aborter.signal }),
      examApi.listSubmissionBindings(exam.examId, { signal: aborter.signal }),
    ])
      .then(([candidatePayload, bindingPayload]) => {
        setCandidates(Array.isArray(candidatePayload) ? candidatePayload : candidatePayload.items ?? []);
        setBindings(Array.isArray(bindingPayload) ? bindingPayload : bindingPayload.items ?? []);
      })
      .catch((cause) => { if (cause.name !== "AbortError") { setCandidates([]); setCandidateError(cause.message); } })
      .finally(() => { if (!aborter.signal.aborted) setCandidatesLoading(false); });
    return () => aborter.abort();
  }, [exam.examId, revision]);
  const statusText = { UPLOADING: "Đang tải lên", UPLOADED: "Đã tải lên · chờ kiểm tra", VALIDATING: "Đang kiểm tra PDF", ANONYMIZING: "Đang ẩn danh", READY_FOR_GRADING: "Sẵn sàng chấm", REVIEW_REQUIRED: "Cần xử lý", FAILED: "Xử lý thất bại", COMPLETED: "Đã chấm" };
  const rows = candidates.map((candidate) => {
    const binding = bindings.find((item) => item.candidateId === candidate.candidateId);
    const submission = binding ? submissions.find((item) => item.submissionId === binding.submissionId) : null;
    return [submission?.pseudonym ?? '—', candidate.fullName, candidate.candidateNumber, submission?.updatedAt ? new Date(submission.updatedAt).toLocaleString("vi-VN") : uploadedCandidates[candidate.candidateNumber] ? "Vừa tải lên" : "—", statusText[submission?.status] ?? (uploadedCandidates[candidate.candidateNumber] ? "Đã nộp" : "Chưa upload bài"), submission?.failureCode ?? '—', '—'];
  });
  return (
    <>
      <Header crumb="PHÒNG KHẢO THÍ / KỲ THI" title={exam.name}>
        <Button className="secondary" onClick={back}>
          ← Danh sách kỳ thi
        </Button>
        <Button onClick={finalize}>Chốt điểm</Button>
        <Button className="secondary" onClick={() => setAssigning(true)}>Phân công chấm</Button>
        <Button className="secondary" onClick={configure}>Thiết lập</Button>
      </Header>
      <main className="content">
        <div className="detail">
          <div>
            <p className="exam-id">{exam.id} · ĐANG DIỄN RA</p>
            <h2>{exam.subject}</h2>
          </div>
          <div className="progress">
            <b>
              {exam.graded} / {exam.candidates}
            </b>
            <span>bài đã chấm</span>
            <i>
              <em />
            </i>
          </div>
        </div>
        <Info exam={exam} />
        <section className="table-box">
          <div className="section-head">
            <div>
              <h2>Danh sách thí sinh</h2>
              <p>
                {candidatesLoading ? 'Đang tải danh sách thí sinh…' : `${candidates.length} thí sinh · ${exam.submitted} bài đã nộp`} · {!exam.templateId ? 'Hãy chọn mẫu giấy thi trong Thiết lập trước khi tải bài.' : 'Chọn tệp PDF ở đúng dòng thí sinh'}
              </p>
            </div>
          </div>
          {candidateError && <p className="form-error">Không thể tải danh sách thí sinh: {candidateError}</p>}
          <Table
            headers={[
              "Số phách",
              "Thí sinh",
              "Mã SV",
              "Thời điểm nộp",
              "Trạng thái xử lý",
              "Vấn đề",
              "Điểm",
              "",
            ]}
            rows={rows}
            statusAt={4}
            issueAt={5}
            action={(row) => {
              const candidate = candidates.find((item) => item.candidateNumber === row[2]);
              const binding = candidate ? bindings.find((item) => item.candidateId === candidate.candidateId) : null;
              const submission = binding ? submissions.find((item) => item.submissionId === binding.submissionId) : null;
              const isUploading = uploadingCandidateId && uploadingCandidateId === candidate?.candidateId;
              const canRetry = submission && ["FAILED", "REVIEW_REQUIRED", "VALIDATING"].includes(submission.status);

              if (canRetry) return <div className="actions"><Button disabled={retryingSubmissionId === submission.submissionId} onClick={async () => { if (submission.status === "VALIDATING" && !window.confirm("Chỉ dùng khi job đã treo. Hệ thống sẽ gửi lại job validate hiện có. Tiếp tục?")) return; setRetryingSubmissionId(submission.submissionId); try { await examApi.retrySubmissionValidation(submission.submissionId, { recoverStalled: submission.status === "VALIDATING" }); setCandidateError(""); refresh(); } catch (cause) { setCandidateError(cause.message || "Không thể thử lại xử lý bài thi."); } finally { setRetryingSubmissionId(null); } }}>{retryingSubmissionId === submission.submissionId ? "Đang thử lại…" : submission.status === "VALIDATING" ? "Khôi phục job" : "Thử lại"}</Button>{submission.status === "REVIEW_REQUIRED" && <Button className="secondary" disabled={retryingSubmissionId === submission.submissionId} onClick={async () => { if (!window.confirm("Chỉ dùng khi phòng khảo thí xác nhận bài đúng mẫu nhưng kích thước scan lệch. Tiếp tục?")) return; setRetryingSubmissionId(submission.submissionId); try { await examApi.retrySubmissionValidation(submission.submissionId, { forceTemplateSize: true }); setCandidateError(""); refresh(); } catch (cause) { setCandidateError(cause.message || "Không thể thử lại có ngoại lệ."); } finally { setRetryingSubmissionId(null); } }}>Bỏ qua lệch mẫu</Button>}</div>;

              return (
                <label className="upload-btn">
                  ⇧ {isUploading ? "Đang tải…" : uploadedCandidates[row[2]] ? "Đã tải" : !exam.templateId ? "Chọn mẫu trước" : "Tải bài thi"}
                  <input
                    type="file"
                    accept=".pdf,application/pdf"
                    disabled={!candidate || isUploading || !exam.templateId}
                    onChange={async (event) => {
                      const file = event.target.files?.[0];
                      if (!file || !candidate) return;

                      setUploadingCandidateId(candidate.candidateId);
                      try {
                        await examApi.uploadSubmission(exam, candidate, file, { templateId: exam.templateId });
                        setUploadedCandidates((current) => ({ ...current, [row[2]]: true }));
                        setCandidateError("");
                      } catch (cause) {
                        setCandidateError(cause.message || "Không thể tải lên bài thi.");
                      } finally {
                        setUploadingCandidateId(null);
                        event.target.value = "";
                      }
                    }}
                  />
                </label>
              );
            }}
          />
        </section>
        <div className="actions bottom">
          <Button
            className="secondary"
            onClick={() => exportResults(exam.examId)}
          >
            ⇩ Xuất kết quả kỳ thi
          </Button>
          <Button onClick={finalize}>Chốt điểm</Button>
        </div>
      </main>
      {assigning && <AssignmentModal exam={exam} submissions={submissions} close={() => setAssigning(false)} refresh={refresh} />}
    </>
  );
}
function Finalize({ back }) {
  return (
    <>
      <Header
        crumb="PHÒNG KHẢO THÍ / KỲ THI / CHỐT ĐIỂM"
        title="Chốt điểm kỳ thi"
      >
        <Button className="secondary" onClick={back}>
          ← Quay lại kỳ thi
        </Button>
      </Header>
      <main className="content">
        <div className="notice">
          <b>!</b>
          <div>
            <strong>Kiểm tra kết quả trước khi chốt</strong>
            <p>
              Các bài có chênh lệch điểm vượt ngưỡng cần được Hội đồng chấm thi
              quyết định trước khi xuất kết quả.
            </p>
          </div>
        </div>
        <section className="table-box"><h2>Chưa có dữ liệu tổng hợp</h2><p>Dữ liệu chốt điểm sẽ chỉ hiển thị sau khi API tổng hợp kết quả được triển khai.</p></section>
      </main>
    </>
  );
}
function GraderHome({ exam, submissions, open, user }) {
  const assignedCount = paperRows(submissions).length;
  return (
    <>
      <Header crumb="GIÁM KHẢO · BÀI ĐƯỢC PHÂN CÔNG" title="Kỳ thi cần chấm">
        <span className="user">
          {user?.fullName ?? "Giám khảo"}
          <br />
          <small>Giám khảo</small>
        </span>
      </Header>
      <main className="content">
        <div className="welcome">
          <div>
            <h2>Chào giám khảo {user?.fullName ?? ""}</h2>
            <p>
              Bạn có <b>{assignedCount} bài làm</b> được phân công trong kỳ thi này.
            </p>
          </div>
          <small>
            Thời gian thi
            <br />
            <b>{exam.time}</b>
          </small>
        </div>
        <button className="assigned" onClick={open}>
          <div>
            <p className="exam-id">{exam.id}</p>
            <h2>{exam.name}</h2>
            <p>{exam.subject} · Kỳ thi lúc {exam.time}</p>
          </div>
          <div>
            <b>{assignedCount.toString().padStart(2, '0')}</b>
            <span>bài được giao</span>　→
          </div>
        </button>
      </main>
    </>
  );
}
function PaperList({ exam, submissions, back, grade }) {
  const papers = paperRows(submissions);
  const completedCount = papers.filter((row) => row[2] === 'Đã chấm').length;
  return (
    <>
      <Header crumb="GIÁM KHẢO / KỲ THI" title="Bài làm được phân công">
        <Button className="secondary" onClick={back}>
          ← Kỳ thi của tôi
        </Button>
      </Header>
      <main className="content">
        <div className="detail">
          <div>
            <p className="exam-id">{exam.id}</p>
            <h2>{exam.name}</h2>
            <p>{exam.subject} · Điểm tối đa 10</p>
          </div>
          <div className="badge">{completedCount.toString().padStart(2, '0')} / {papers.length.toString().padStart(2, '0')} hoàn thành</div>
        </div>
        <section className="table-box">
          <div className="section-head">
            <div>
              <h2>Danh sách bài làm</h2>
              <p>
                Chỉ hiển thị số phách. Điểm của giám khảo khác được bảo mật.
              </p>
            </div>
            <Status>Chấm độc lập</Status>
          </div>
          <Table
            headers={[
              "Số phách",
              "Tổng",
              "Trạng thái",
              "",
            ]}
            rows={papers}
            statusAt={2}
            action={(r, index) => (
              <Button className="link" onClick={() => grade(submissions[index])}>
                {r[2] === "Đã chấm" ? "Xem lượt chấm" : "Bắt đầu chấm →"}
              </Button>
            )}
          />
        </section>
      </main>
    </>
  );
}
function Scan({ pseudonym, url }) {
  return <div className="scan"><div><strong>PHÁCH: {pseudonym}</strong></div>{url ? <iframe title={`Bài làm ${pseudonym}`} src={url} className="scan-pdf" /> : <article><h3>Không có bản bài làm</h3><p>Không thể lấy URL bản ẩn danh từ máy chủ.</p></article>}</div>;
}
function RubricPdf({ url }) {
  return <div className="scan rubric-pdf-document"><div><strong>HƯỚNG DẪN CHẤM</strong></div>{url ? <iframe title="PDF hướng dẫn chấm" src={url} className="scan-pdf" /> : <article><h3>Chưa có rubric PDF</h3><p>Phòng khảo thí cần tải lên và kích hoạt PDF hướng dẫn chấm cho kỳ thi này.</p></article>}</div>;
}
function Grading({ back, assignment, onCompleted, actionRef }) {
  const [attemptId, setAttemptId] = useState(null);
  const [questions, setQuestions] = useState([]);
  const [fileUrl, setFileUrl] = useState("");
  const [rubricUrl, setRubricUrl] = useState("");
  const [scores, setScores] = useState({});
  const [status, setStatus] = useState("loading");
  const [error, setError] = useState("");
  const [saving, setSaving] = useState(false);
  const leaves = (items) => items.flatMap((item) => item.children?.length ? leaves(item.children) : [item]);
  const leafQuestions = leaves(questions);
  const itemTotal = (item) => item.children?.length ? leaves(item.children).reduce((sum, child) => sum + Number(scores[child.questionId] ?? 0), 0) : Number(scores[item.questionId] ?? 0);
  useEffect(() => {
    if (!assignment?.assignmentId) return undefined;
    const aborter = new AbortController();
    setStatus("loading"); setError("");
    gradingApi.startAttempt(assignment.assignmentId, { signal: aborter.signal }).then(async (attempt) => {
      const [detail, context] = await Promise.all([
        gradingApi.getAttempt(attempt.attemptId, { signal: aborter.signal }),
        gradingApi.getAttemptContext(attempt.attemptId, { signal: aborter.signal }),
      ]);
      if (aborter.signal.aborted) return;
      setAttemptId(attempt.attemptId);
      setQuestions(context.questions ?? []);
      setFileUrl(context.anonymizedFileAccessUrl ?? "");
      setRubricUrl(context.rubricPdfAccessUrl ?? "");
      setScores(Object.fromEntries((detail.scores ?? []).map((item) => [item.questionId, item.score])));
      setStatus(detail.attempt?.status ?? attempt.status);
    }).catch((cause) => {
      if (cause.name !== "AbortError") setError(cause.message || "Không thể tải lượt chấm.");
    });
    return () => aborter.abort();
  }, [assignment?.assignmentId, assignment?.examId]);
  const total = leafQuestions.reduce((sum, question) => sum + Number(scores[question.questionId] ?? 0), 0);
  const maximum = leafQuestions.reduce((sum, question) => sum + Number(question.maxScore ?? 0), 0);
  const save = async (confirm) => {
    if (!attemptId || status === "CONFIRMED") return;
    setSaving(true); setError("");
    try {
      await gradingApi.saveScores(attemptId, leafQuestions.map((question) => ({
        questionId: question.questionId,
        score: scores[question.questionId] === undefined || scores[question.questionId] === "" ? null : Number(scores[question.questionId]),
        needsReview: false,
      })));
      if (confirm) {
        const result = await gradingApi.confirmAttempt(attemptId);
        setStatus(result.status);
        onCompleted?.();
      }
    } catch (cause) { setError(cause.message || "Không thể lưu điểm."); }
    finally { setSaving(false); }
  };
  if (actionRef) actionRef.current = { back, saveDraft: () => save(false), complete: () => save(true), disabled: saving || status === "CONFIRMED", completed: status === "CONFIRMED" };
  if (!assignment) return <main className="content"><p className="empty-state">Chưa chọn bài làm để chấm.</p></main>;
  return (
    <>
      <main className="content grading">
        {status === "CONFIRMED" && <div className="success">✓ Đã hoàn thành lượt chấm.</div>}
        {error && <div className="form-error">{error}</div>}
        <section className="score-entry-panel"><div><h3>Nhập điểm</h3><p>Nhập điểm cho từng câu hoặc ý nhỏ. Nội dung chi tiết nằm trong PDF hướng dẫn chấm.</p></div><strong className="total">{total.toFixed(2)}<small>/{maximum.toFixed(2)}</small></strong><div className="score-entry-list">{leafQuestions.map((question) => <label key={question.questionId}><b>{question.itemCode ?? `Câu ${question.orderNo}`}</b><input aria-label={`Điểm ${question.itemCode ?? question.orderNo}`} type="number" min="0" max={question.maxScore} step=".25" disabled={status === "CONFIRMED"} value={scores[question.questionId] ?? ""} onChange={(event) => setScores((current) => ({ ...current, [question.questionId]: event.target.value }))} /></label>)}</div></section>
        <div className="grading-documents">
          <section>
            <div className="panel-head">
              <div>
                <h3>Bài làm đã ẩn danh</h3>
                <p>Chỉ hiển thị số phách, không hiển thị thông tin thí sinh.</p>
              </div>
              <Status>{status === "CONFIRMED" ? "Đã chấm" : "Đang chấm"}</Status>
            </div>
            <Scan pseudonym={assignment.pseudonym ?? assignment.submissionId} url={fileUrl} />
          </section>
          <section>
            <div className="panel-head">
              <div><h3>Hướng dẫn chấm</h3><p>Đối chiếu đáp án và tiêu chí trong PDF rubric.</p></div>
            </div>
            <RubricPdf url={rubricUrl} />
          </section>
        </div>
        {!questions.length && !error && <p className="empty-state">Đang tải cấu trúc câu hỏi của kỳ thi…</p>}<p className="protect">◉ Điểm của giám khảo khác được ẩn đến khi bạn hoàn thành.</p>
      </main>
    </>
  );
}
function CouncilHome({ open, exams }) {
  const pendingReviewCount = exams.reduce((total, item) => total + item.issues, 0);
  return (
    <>
      <Header crumb="HỘI ĐỒNG CHẤM THI" title="Kỳ thi cần rà soát">
        <span className="user">
          Phạm Minh
          <br />
          <small>Chủ tịch Hội đồng</small>
        </span>
      </Header>
      <main className="content">
        <div className="intro">
          <div>
            <h2>Rà soát kết quả chấm</h2>
            <p>
              Phát hiện và xử lý các bài có chênh lệch điểm hoặc báo cáo vấn đề.
            </p>
          </div>
          <div className="review-count">
            <b>{pendingReviewCount.toString().padStart(2, '0')}</b> bài cần xử lý
          </div>
        </div>
        <div className="exam-grid">
          {exams.map((x) => (
            <ExamCard council item={x} open={open} key={x.id} />
          ))}
        </div>
      </main>
    </>
  );
}
function CouncilExam({ exam, reviews, back, grade }) {
  const pendingReviewCount = reviews.length;
  return (
    <>
      <Header crumb="HỘI ĐỒNG / KỲ THI" title="Kết quả chấm & rà soát">
        <Button className="secondary" onClick={back}>
          ← Danh sách kỳ thi
        </Button>
      </Header>
      <main className="content">
        <div className="detail">
          <div>
            <p className="exam-id">{exam.id}</p>
            <h2>{exam.name}</h2>
            <p>{exam.subject} · Kết quả từ 02 giám khảo độc lập</p>
          </div>
          <div className="review-count">
            <b>{pendingReviewCount.toString().padStart(2, '0')}</b> cần xử lý
          </div>
        </div>
        <section className="table-box">
          <div className="section-head">
            <div>
              <h2>Danh sách bài làm</h2>
              <p>
                Chọn bài có vấn đề để xem bài làm, rubric và nhập điểm chốt.
              </p>
            </div>
            <span className="badge warning">⚠ Chênh lệch & báo cáo</span>
          </div>
          <Table
            headers={[
              "Số phách",
              "Điểm GK1",
              "Điểm GK2",
              "Điểm chốt",
              "Kết quả / vấn đề",
              "",
            ]}
            rows={reviewRows(reviews)}
            issueAt={4}
            action={(r) => (
              <Button className="link" onClick={grade}>
                {r[3] === "—" ? "Xử lý →" : "Xem chi tiết →"}
              </Button>
            )}
          />
        </section>
      </main>
    </>
  );
}
export default function AppView() {
  const workspace = useExamWorkspace();
  const [creatingTemplate, setCreatingTemplate] = useState(false);
  const [gradingAssignment, setGradingAssignment] = useState(null);
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);
  const gradingActionRef = useRef({});
  const {
    authToken, authReady, role, page, creating, configuring, selectedExamId, exams, selectedExam,
    submissions, reviews, templates, apiError, loading, revision, user,
    login, logout, createExam, exportResults, createTemplate, deleteTemplate, registerGrader, openExam,
    setPage, setCreating, setConfiguring, refreshExam,
  } = workspace;
  const noExamView = <><Header crumb="DỮ LIỆU KỲ THI" title="Chưa có kỳ thi" /><main className="content"><section className="table-box"><h2>Chưa có dữ liệu để hiển thị</h2><p>Danh sách kỳ thi sẽ xuất hiện tại đây khi máy chủ có dữ liệu phù hợp với quyền của bạn.</p></section></main></>;
  let view = !selectedExam && role !== "office" ? noExamView :
    role === "office" ? (
      <OfficePage page={page}
        home={<OfficeHome exams={exams}
          open={openExam}
          create={() => setCreating(true)}
        />}
        final={<Finalize back={() => setPage("exam")} />}
        templates={<TemplateList templates={templates} create={() => setCreatingTemplate(true)} remove={deleteTemplate} />}
        exam={<OfficeExam
          exam={selectedExam}
          back={() => setPage("home")}
          finalize={() => setPage("final")}
          exportResults={exportResults}
          configure={() => setConfiguring(true)}
          revision={revision}
          submissions={submissions}
          refresh={refreshExam}
        />}
      />
    ) : role === "grader" ? (
      <GraderPage page={page}
        home={<GraderHome exam={selectedExam} submissions={submissions} user={user} open={() => setPage("papers")} />}
        papers={<PaperList
          exam={selectedExam}
          submissions={submissions}
          back={() => setPage("home")}
          grade={(assignment) => { setGradingAssignment(assignment); setPage("grade"); }}
        />}
        grading={<Grading assignment={gradingAssignment} back={() => setPage("papers")} onCompleted={refreshExam} actionRef={gradingActionRef} />}
      />
    ) : (
      <CouncilPage page={page}
        home={<CouncilHome exams={exams} open={openExam} />}
        exam={<CouncilExam
        exam={selectedExam}
        reviews={reviews}
        back={() => setPage("home")}
        grade={() => setPage("grade")}
        />}
        grading={<Grading back={() => setPage("exam")} actionRef={gradingActionRef} />}
      />
    );
  if (!authReady) return <div className="api-state">Đang xác thực phiên đăng nhập…</div>;
  if (!authToken || !role) return <Login onLogin={login} onRegister={registerGrader} />;
  return (
    <div className={`app ${sidebarCollapsed ? "sidebar-collapsed" : ""}`}>
      <aside>
        <button className="sidebar-toggle" onClick={() => setSidebarCollapsed((collapsed) => !collapsed)} title={sidebarCollapsed ? "Mở rộng thanh bên" : "Thu gọn thanh bên"}>
          {sidebarCollapsed ? "›" : "‹"}
        </button>
        <div className="brand">
          <b>EG</b>
          <div>
            <strong>ExamGrader</strong>
            <small>Chấm thi tự luận</small>
          </div>
        </div>
        <p className="side-label">KHÔNG GIAN LÀM VIỆC</p>
        <nav>{role === "office" ? <><button className={page === "templates" ? "" : "active"} onClick={() => setPage("home")}>▦　Kỳ thi</button><button className={page === "templates" ? "active" : ""} onClick={() => setPage("templates")}>▤　Quản lý mẫu giấy thi</button></> : <button className="active">{accessTypes[role].icon}　{accessTypes[role].title}</button>}</nav>
        {role === "grader" && page === "grade" && <div className="grading-sidebar-actions"><b>THAO TÁC CHẤM</b><button onClick={() => gradingActionRef.current.back?.()}>← Quay lại danh sách</button><button onClick={() => gradingActionRef.current.saveDraft?.()} disabled={gradingActionRef.current.disabled}>▣ Lưu nháp</button><button className="complete" onClick={() => gradingActionRef.current.complete?.()} disabled={gradingActionRef.current.disabled}>{gradingActionRef.current.completed ? "✓ Đã hoàn thành" : "✓ Hoàn thành lượt chấm"}</button><button className="report" onClick={() => window.alert("Báo cáo vấn đề sẽ được chuyển tới Hội đồng chấm sau khi có API tiếp nhận báo cáo.")}>⚑ Báo cáo vấn đề</button></div>}
        <div className="help">
          ?{" "}
          <span>
            <b>Cần hỗ trợ?</b>
            <small>Hướng dẫn sử dụng hệ thống</small>
          </span>
        </div>
        <div className="profile">
          <span className="avatar">
            {role === "grader" ? "LH" : role === "council" ? "PM" : "NT"}
          </span>
          <div>
            <b>
              {user?.fullName ?? (role === "grader" ? "Giám khảo" : role === "council" ? "Hội đồng chấm thi" : "Phòng khảo thí")}
            </b>
            <small>
              {role === "grader"
                ? "Giám khảo"
                : role === "council"
                  ? "Chủ tịch Hội đồng"
                  : "Phòng khảo thí"}
            </small>
          </div>
          <button className="logout" onClick={logout}>Đăng xuất</button>
        </div>
      </aside>
      <div className="main">{view}</div>
      {loading && <div className="api-state">Đang kết nối API…</div>}
      {apiError && <div className="api-state warning">{apiError}</div>}
      {creating && <CreateModal templates={templates} onCreated={createExam} close={() => setCreating(false)} />}
      {creatingTemplate && <TemplateEditor onCreated={createTemplate} close={() => setCreatingTemplate(false)} />}
      {configuring && <SetupExam examId={selectedExamId} initialTemplateId={selectedExam?.templateId} templates={templates} close={() => setConfiguring(false)} refresh={refreshExam} />}
    </div>
  );
}
