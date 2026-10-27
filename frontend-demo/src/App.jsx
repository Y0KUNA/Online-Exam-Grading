import { useEffect, useMemo, useState } from "react";
import "./App.css";
import "./rubric.css";
import { mockData } from "./mockData";
import { gradingApi } from "./api";

const formatDateTime = (value) =>
  new Intl.DateTimeFormat('vi-VN', {
    hour: '2-digit', minute: '2-digit', day: '2-digit', month: '2-digit',
    timeZone: 'Asia/Ho_Chi_Minh',
  }).format(new Date(value)).replace(',', ' ·');

const submissionStatusLabel = {
  GRADING: 'Đang chấm',
  READY_FOR_GRADING: 'Sẵn sàng chấm',
  REVIEW_REQUIRED: 'Cần xử lý',
  COMPLETED: 'Đã chấm',
};
const getExamSummary = (examId) => {
  const submissions = mockData.submissions.filter((item) => item.examId === examId);
  return {
    candidates: mockData.candidates.filter((item) => item.examId === examId).length,
    submitted: submissions.length,
    graded: submissions.filter((item) => item.status === 'COMPLETED').length,
    issues: mockData.reviews.filter((item) => item.examId === examId && item.status !== 'RESOLVED').length,
  };
};
const getExams = () => mockData.exams.map((item) => ({
  ...item,
  id: item.examCode,
  time: formatDateTime(item.examTime),
  day: new Intl.DateTimeFormat('vi-VN', { day: '2-digit', timeZone: 'Asia/Ho_Chi_Minh' }).format(new Date(item.examTime)),
  month: `THG ${new Intl.DateTimeFormat('vi-VN', { month: '2-digit', timeZone: 'Asia/Ho_Chi_Minh' }).format(new Date(item.examTime))}`,
  classes: item.gradeLevel,
  ...getExamSummary(item.examId),
}));
const scoreBySubmission = { 'c08bc820-66a5-4bd3-8e1a-000000000001': '8.50', 'c08bc820-66a5-4bd3-8e1a-000000000004': '7.75' };
const studentRows = (examId) => mockData.candidates.filter((candidate) => candidate.examId === examId).map((candidate) => {
  const pseudonym = mockData.pseudonyms.find((item) => item.candidateId === candidate.candidateId);
  const submission = pseudonym && mockData.submissions.find((item) => item.submissionId === pseudonym.submissionId);
  if (!submission) return ['—', candidate.fullName, candidate.candidateNumber, '—', 'Chưa upload bài', '—', '—'];
  const review = mockData.reviews.find((item) => item.submissionId === submission.submissionId);
  return [pseudonym.value, candidate.fullName, candidate.candidateNumber, formatDateTime(submission.updatedAt), submissionStatusLabel[submission.status], review?.reason ?? '—', scoreBySubmission[submission.submissionId] ?? '—'];
});
const paperRows = (examId) => mockData.submissions.filter((submission) => submission.examId === examId).map((submission) => {
  const pseudonym = mockData.pseudonyms.find((item) => item.submissionId === submission.submissionId);
  const assignment = mockData.assignments.find((item) => item.submissionId === submission.submissionId);
  const attempt = assignment && mockData.attempts.find((item) => item.assignmentId === assignment.assignmentId);
  const scores = attempt ? mockData.questionScores.filter((item) => item.attemptId === attempt.attemptId) : [];
  const byQuestion = (questionId) => scores.find((item) => item.questionId === questionId)?.score?.toFixed(2) ?? '—';
  const total = scores.length ? scores.reduce((sum, item) => sum + item.score, 0).toFixed(2) : '—';
  return [pseudonym?.value ?? '—', byQuestion(mockData.questions[0].questionId), byQuestion(mockData.questions[1].questionId), byQuestion(mockData.questions[2].questionId), total, assignment?.status === 'IN_PROGRESS' ? 'Đang chấm' : submissionStatusLabel[submission.status]];
});
const reviewRows = (examId) => mockData.reviews.filter((review) => review.examId === examId).map((review) => {
  const pseudonym = mockData.pseudonyms.find((item) => item.submissionId === review.submissionId);
  const decision = mockData.councilDecisions.find((item) => item.reviewId === review.reviewId);
  return [pseudonym?.value ?? '—', '7.00', '9.00', decision ? decision.finalScore.toFixed(2) : '—', review.status === 'RESOLVED' ? `Đã xử lý · ${review.reason}` : review.reason];
});
const rubric = [
  {
    question: mockData.questions[0].content,
    points: mockData.criteria[0].maxScore,
    criterion: mockData.criteria[0].label,
    detail: mockData.criteria[0].description,
    scores: [
      "Nêu đúng vấn đề, triển khai rõ ràng và có lập luận thuyết phục.",
      "Nêu đúng vấn đề nhưng triển khai chưa đầy đủ.",
      "Nêu được ý chính nhưng lập luận còn hạn chế.",
      "Không xác định được hoặc xác định sai vấn đề.",
    ],
  },
  {
    question: mockData.questions[0].content,
    points: mockData.criteria[1].maxScore,
    criterion: mockData.criteria[1].label,
    detail: mockData.criteria[1].description,
    scores: [
      "Phân tích sâu, dẫn chứng phù hợp và diễn đạt mạch lạc.",
      "Phân tích được các ý chính, có dẫn chứng.",
      "Có ý phân tích nhưng chưa rõ hoặc thiếu dẫn chứng.",
      "Không có phân tích hoặc lập luận không phù hợp.",
    ],
  },
  {
    question: mockData.questions[1].content,
    points: mockData.criteria[2].maxScore,
    criterion: mockData.criteria[2].label,
    detail: mockData.criteria[2].description,
    scores: [
      "Nêu đúng, đầy đủ các yếu tố; ví dụ phù hợp.",
      "Nêu được phần lớn yếu tố, còn một vài thiếu sót.",
      "Nêu được một số yếu tố nhưng chưa đầy đủ.",
      "Không nêu được các yếu tố cần thiết.",
    ],
  },
  {
    question: mockData.questions[2].content,
    points: mockData.criteria[3].maxScore,
    criterion: mockData.criteria[3].label,
    detail: mockData.criteria[3].description,
    scores: [
      "Bố cục rõ ràng, diễn đạt chính xác, không lỗi chính tả.",
      "Trình bày rõ, có ít lỗi nhỏ.",
      "Diễn đạt còn rời rạc hoặc có lỗi.",
      "Trình bày thiếu rõ ràng, nhiều lỗi.",
    ],
  },
];
const statusClass = (s) =>
  s
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase()
    .replaceAll(" ", "-");
function Status({ children }) {
  return <span className={`status ${statusClass(children)}`}>{children}</span>;
}
function Button({ children, className = "", ...props }) {
  return (
    <button type="button" className={`button ${className}`} {...props}>
      {children}
    </button>
  );
}
function Header({ crumb, title, children }) {
  return (
    <header className="topbar">
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
        <b>02 giám khảo · ngưỡng 1.00</b>
      </div>
    </div>
  );
}
function Modal({ title, close, children }) {
  return (
    <div className="overlay">
      <div className="modal">
        <div className="modal-head">
          <div>
            <p className="crumb">HỆ THỐNG CHẤM THI</p>
            <h2>{title}</h2>
          </div>
          <button onClick={close}>×</button>
        </div>
        {children}
      </div>
    </div>
  );
}
function Login({ onLogin }) {
  const [error, setError] = useState(""); const [loading, setLoading] = useState(false);
  const submit = async (event) => { event.preventDefault(); const form = new FormData(event.currentTarget); setLoading(true); setError(""); try { await onLogin({ username: form.get("username"), password: form.get("password") }); } catch (cause) { setError(cause.message); } finally { setLoading(false); } };
  return <div className="overlay"><div className="modal"><div className="modal-head"><div><p className="crumb">EXAMGRADER</p><h2>Đăng nhập để kiểm thử API</h2></div></div><form className="form" onSubmit={submit}><div className="form-grid"><label>Tên đăng nhập<input name="username" required autoFocus /></label><label>Mật khẩu<input name="password" required type="password" /></label></div>{error && <p className="form-error">{error}</p>}<div className="actions"><Button type="submit" disabled={loading}>{loading ? "Đang đăng nhập…" : "Đăng nhập"}</Button></div></form></div></div>;
}
function SetupExam({ examId, close, refresh }) {
  const [message, setMessage] = useState(""); const [error, setError] = useState("");
  const action = async (work, ok) => { setError(""); try { await work(); setMessage(ok); refresh(); } catch (cause) { setError(cause.message); } };
  return <Modal title="Thiết lập kỳ thi" close={close}><div className="form"><section><b>Thêm câu hỏi</b><form className="form-grid compact" onSubmit={(e)=>{e.preventDefault();const f=new FormData(e.currentTarget);action(()=>gradingApi.addQuestion(examId,{orderNo:Number(f.get("orderNo")),content:f.get("content"),maxScore:Number(f.get("maxScore")),required:true}),"Đã thêm câu hỏi");}}><label>Thứ tự<input name="orderNo" type="number" min="1" required /></label><label>Nội dung<input name="content" required /></label><label>Điểm tối đa<input name="maxScore" type="number" min="0" step=".25" required /></label><div className="actions"><Button type="submit">Lưu câu hỏi</Button></div></form></section><section><b>Tạo rubric</b><form onSubmit={(e)=>{e.preventDefault();const f=new FormData(e.currentTarget);action(async()=>{const created=await gradingApi.createRubric(examId,JSON.parse(f.get("content")));await gradingApi.activateRubric(examId,created.rubricId);},"Đã tạo và kích hoạt rubric");}}><label className="feedback">Nội dung JSON<textarea name="content" defaultValue={'{"instructions":"Chấm theo đáp án đã phê duyệt"}'} /></label><div className="actions"><Button type="submit">Lưu rubric</Button></div></form></section><section><b>Import danh sách thí sinh (CSV)</b><form onSubmit={(e)=>{e.preventDefault();const file=new FormData(e.currentTarget).get("file");if(!(file instanceof File)||file.size===0){setError("Hãy chọn tệp CSV trước khi import.");return;}action(async()=>{const result=await gradingApi.importRoster(examId,file);return result;},"Đã import roster");}}><label className="upload">⇧ <b>Chọn tệp CSV</b><input name="file" type="file" accept=".csv,text/csv" required /></label><small>Header bắt buộc: candidateNumber, fullName; tùy chọn: className.</small><div className="actions"><Button type="submit">Import danh sách</Button></div></form></section>{message&&<p className="success">{message}</p>}{error&&<p className="form-error">{error}</p>}</div></Modal>;
}
function CreateModal({ close, onCreated }) {
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
            const result = await onCreated({ name: form.get("name"), subject: form.get("subject"), examTime: form.get("examTime"), gradeLevel: form.get("gradeLevel"), crossGradingEnabled: cross, graderCount: cross ? Number(form.get("graderCount")) : 1, differenceThreshold: cross ? Number(form.get("differenceThreshold")) : 0 }, rosterFile instanceof File && rosterFile.size > 0 ? rosterFile : null);
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
            Mẫu giấy thi
            <select defaultValue="">
              <option value="" disabled>
                Chọn mẫu giấy thi
              </option>
              <option>KTCT-A4-v2 · 2 trang</option>
              <option>Template-TL-2026 · 4 trang</option>
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
function OfficeExam({ exam, back, finalize, exportResults, configure, revision }) {
  const [uploadedCandidates, setUploadedCandidates] = useState({});
  const [candidates, setCandidates] = useState([]);
  const [candidateError, setCandidateError] = useState("");
  const [candidatesLoading, setCandidatesLoading] = useState(true);
  useEffect(() => {
    const aborter = new AbortController();
    setCandidatesLoading(true); setCandidateError("");
    gradingApi.listCandidates(exam.examId, { signal: aborter.signal })
      .then((payload) => setCandidates(Array.isArray(payload) ? payload : payload.items ?? []))
      .catch((cause) => { if (cause.name !== "AbortError") { setCandidates([]); setCandidateError(cause.message); } })
      .finally(() => { if (!aborter.signal.aborted) setCandidatesLoading(false); });
    return () => aborter.abort();
  }, [exam.examId, revision]);
  const rows = candidates.map((candidate) => {
    const mockRow = studentRows(exam.examId).find((row) => row[2] === candidate.candidateNumber);
    return mockRow ?? ['—', candidate.fullName, candidate.candidateNumber, '—', 'Chưa upload bài', '—', '—'];
  }).map((row) =>
    uploadedCandidates[row[2]]
      ? [row[0], row[1], row[2], 'Vừa tải lên', 'Đã nộp', row[5], row[6]]
      : row,
  );
  return (
    <>
      <Header crumb="PHÒNG KHẢO THÍ / KỲ THI" title={exam.name}>
        <Button className="secondary" onClick={back}>
          ← Danh sách kỳ thi
        </Button>
        <Button onClick={finalize}>Chốt điểm</Button>
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
                {candidatesLoading ? 'Đang tải danh sách thí sinh…' : `${candidates.length} thí sinh · ${exam.submitted} bài đã nộp`} · Chọn tệp PDF ở đúng dòng thí sinh
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
            action={(row) => (
              <label className="upload-btn">
                ⇧ {uploadedCandidates[row[2]] ? "Đã tải" : "Tải bài thi"}
                <input
                  type="file"
                  accept=".pdf,application/pdf"
                  onChange={(event) => {
                    if (event.target.files?.[0]) {
                      setUploadedCandidates((current) => ({ ...current, [row[2]]: true }));
                    }
                  }}
                />
              </label>
            )}
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
    </>
  );
}
function Table({ headers, rows, statusAt, issueAt, action }) {
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            {headers.map((h) => (
              <th key={h}>{h}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((r, i) => (
            <tr key={r[0]}>
              {r.map((v, j) => (
                <td
                  key={j}
                  className={j === issueAt && v !== "—" ? "issue" : ""}
                >
                  {j === 0 || (j === r.length - 1 && j !== statusAt) ? (
                    <b>{v}</b>
                  ) : j === statusAt ? (
                    <Status>{v}</Status>
                  ) : (
                    v
                  )}
                </td>
              ))}
              {action && <td>{action(r, i)}</td>}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
function Finalize({ back }) {
  const rows = [
    [
      "P-9K4M",
      "Nguyễn Minh An",
      "1.50",
      "1.50",
      "3.25",
      "3.50",
      "3.75",
      "3.75",
      "8.50",
      "8.75",
      "Đã đối soát",
    ],
    [
      "P-7R1X",
      "Lê Khánh Linh",
      "1.50",
      "2.00",
      "3.00",
      "3.50",
      "2.50",
      "3.50",
      "7.00",
      "9.00",
      "Cần xử lý",
    ],
    [
      "P-5D6N",
      "Hoàng Quốc Việt",
      "1.50",
      "1.50",
      "3.25",
      "3.00",
      "3.00",
      "3.00",
      "7.75",
      "7.50",
      "Đã đối soát",
    ],
  ];
  return (
    <>
      <Header
        crumb="PHÒNG KHẢO THÍ / KỲ THI / CHỐT ĐIỂM"
        title="Chốt điểm kỳ thi"
      >
        <Button className="secondary" onClick={back}>
          ← Quay lại kỳ thi
        </Button>
        <Button onClick={() => alert("Điểm đã được chốt trong bản demo.")}>
          Xác nhận chốt điểm
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
        <section className="table-box">
          <div className="section-head">
            <div>
              <h2>Bảng điểm theo câu</h2>
              <p>
                Hiển thị đồng thời điểm của hai giám khảo vì kỳ thi có cấu hình
                chấm chéo.
              </p>
            </div>
            <span className="badge">02 giám khảo độc lập</span>
          </div>
          <Table
            headers={[
              "Số phách",
              "Thí sinh",
              "C1 GK1",
              "C1 GK2",
              "C2 GK1",
              "C2 GK2",
              "C3 GK1",
              "C3 GK2",
              "Tổng GK1",
              "Tổng GK2",
              "Trạng thái",
            ]}
            rows={rows}
            statusAt={10}
          />
        </section>
      </main>
    </>
  );
}
function GraderHome({ exam, open }) {
  const assignedCount = paperRows(exam.examId).length;
  return (
    <>
      <Header crumb="GIÁM KHẢO · BÀI ĐƯỢC PHÂN CÔNG" title="Kỳ thi cần chấm">
        <span className="user">
          Lê Hoàng
          <br />
          <small>Giám khảo</small>
        </span>
      </Header>
      <main className="content">
        <div className="welcome">
          <div>
            <h2>Chào giám khảo Lê Hoàng</h2>
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
function PaperList({ exam, back, grade }) {
  const papers = paperRows(exam.examId);
  const completedCount = papers.filter((row) => row[5] === 'Đã chấm').length;
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
              "Câu 1 / 2đ",
              "Câu 2 / 4đ",
              "Câu 3 / 4đ",
              "Tổng",
              "Trạng thái",
              "",
            ]}
            rows={paperRows(exam.examId)}
            statusAt={5}
            action={(r) => (
              <Button className="link" onClick={grade}>
                {r[5] === "Đã chấm" ? "Xem lượt chấm" : "Bắt đầu chấm →"}
              </Button>
            )}
          />
        </section>
      </main>
    </>
  );
}
function Scan() {
  return (
    <div className="scan">
      <div>
        ‹　<strong>Trang 2 / 4</strong>　›　　−　100%　+
      </div>
      <article>
        <span>PHÁCH: P-9K4M</span>
        <h3>BÀI THI TỰ LUẬN</h3>
        <p>
          <b>Câu 2.</b> Phân tích tác động của chuyển đổi số đến năng lực cạnh
          tranh của doanh nghiệp Việt Nam.
        </p>
        <p>
          Trong bối cảnh kinh tế số phát triển nhanh, chuyển đổi số không chỉ là
          ứng dụng công nghệ mà còn là quá trình thay đổi mô hình vận hành của
          doanh nghiệp...
        </p>
        <p>
          Dữ liệu giúp doanh nghiệp hiểu khách hàng và tự động hóa quy trình, từ
          đó giảm chi phí vận hành và cải thiện chất lượng dịch vụ.
        </p>
        <em>
          Doanh nghiệp cần đầu tư vào năng lực số, quản trị dữ liệu và đào tạo
          nhân sự để tạo lợi thế bền vững.
        </em>
      </article>
    </div>
  );
}
function Grading({ back, council = false }) {
  const [scores, setScores] = useState([1.25, 0.5, 2.5, 1.5]);
  const [report, setReport] = useState(false);
  const [saved, setSaved] = useState(false);
  const total = scores.reduce((a, b) => a + Number(b || 0), 0);
  return (
    <>
      <Header
        crumb={council ? "HỘI ĐỒNG / XỬ LÝ BÀI THI" : "GIÁM KHẢO / CHẤM BÀI"}
        title={`${council ? "Đánh giá & quyết định" : "Chấm bài"} · P-9K4M`}
      >
        <Button className="secondary" onClick={back}>
          ← Quay lại
        </Button>
        {!council && (
          <Button className="danger" onClick={() => setReport(true)}>
            ⚑ Báo cáo vấn đề
          </Button>
        )}
        <Button onClick={() => setSaved(true)}>
          {council ? "Chốt điểm bài làm" : "Hoàn thành lượt chấm"}
        </Button>
      </Header>
      <main className="content grading">
        {saved && (
          <div className="success">
            ✓{" "}
            {council
              ? "Đã lưu điểm chốt của Hội đồng."
              : "Đã hoàn thành lượt chấm P-9K4M."}
          </div>
        )}
        <div className="grading-grid">
          <section>
            <div className="panel-head">
              <div>
                <h3>Bài làm đã ẩn danh</h3>
                <p>Chỉ hiển thị số phách, không hiển thị thông tin thí sinh.</p>
              </div>
              <Status>Đang chấm</Status>
            </div>
            <Scan />
          </section>
          <section>
            <div className="panel-head">
              <div>
                <h3>
                  {council ? "Kết quả chấm & điểm chốt" : "Hướng dẫn chấm"}
                </h3>
                <p>
                  Ma trận tiêu chí và mức độ đạt được theo rubric của kỳ thi.
                </p>
              </div>
              <strong className="total">
                {total.toFixed(2)}
                <small>/10</small>
              </strong>
            </div>
            {council && (
              <div className="compare">
                <div>
                  Giám khảo 1<b>7.00</b>
                </div>
                <div>
                  Giám khảo 2<b>9.00</b>
                </div>
                <div>
                  Chênh lệch<b>2.00</b>
                </div>
              </div>
            )}
            <div className="rubric-scroll">
              <table className="rubric-matrix">
                <thead>
                  <tr>
                    <th>Câu</th>
                    <th>Điểm</th>
                    <th>Tiêu chí</th>
                    <th>Thành phần nội dung</th>
                    <th>Mức 4</th>
                    <th>Mức 3</th>
                    <th>Mức 2</th>
                    <th>Mức 1</th>
                  </tr>
                </thead>
                <tbody>
                  {rubric.map((item, i) => (
                    <tr key={`${item.question}-${item.detail}`}>
                      <td>
                        <b>{item.question}</b>
                        <small>/ {item.points}đ</small>
                      </td>
                      <td>
                        <input
                          aria-label={`Điểm ${item.detail}`}
                          type="number"
                          min="0"
                          max={item.points}
                          step=".25"
                          value={scores[i]}
                          onChange={(e) =>
                            setScores(
                              scores.map((x, k) =>
                                k === i ? e.target.value : x,
                              ),
                            )
                          }
                        />
                        <small>/ {item.points}</small>
                      </td>
                      <td>
                        <b>{item.criterion}</b>
                      </td>
                      <td>{item.detail}</td>
                      {item.scores.map((text, index) => (
                        <td key={text}>
                          <b>{["Xuất sắc", "Tốt", "Đạt", "Chưa đạt"][index]}</b>
                          <span>{text}</span>
                        </td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <label className="feedback">
              {council ? "Căn cứ quyết định điểm chốt *" : "Nhận xét chấm"}
              <textarea
                defaultValue={
                  council
                    ? "Đối chiếu bài làm và rubric, Hội đồng thống nhất điều chỉnh điểm câu 3."
                    : "Lập luận rõ ràng; cần liên hệ thêm ví dụ thực tế ở câu 3."
                }
              />
            </label>
            <p className="protect">
              ◉{" "}
              {council
                ? "Điểm chốt sẽ được ghi nhận là kết quả cuối cùng."
                : "Điểm và nhận xét của giám khảo khác được ẩn đến khi bạn hoàn thành."}
            </p>
          </section>
        </div>
      </main>
      {report && (
        <Modal title="Báo cáo vấn đề" close={() => setReport(false)}>
          <div className="report">
            <p>
              Gửi báo cáo đến Phòng khảo thí và Hội đồng chấm thi để được xử lý.
            </p>
            <label>
              Nội dung vấn đề *
              <textarea
                autoFocus
                placeholder="Mô tả vấn đề phát hiện trong bài làm..."
              />
            </label>
            <div className="actions">
              <Button className="secondary" onClick={() => setReport(false)}>
                Hủy
              </Button>
              <Button onClick={() => setReport(false)}>Báo cáo vấn đề</Button>
            </div>
          </div>
        </Modal>
      )}
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
function CouncilExam({ exam, back, grade }) {
  const pendingReviewCount = exam.issues;
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
            rows={reviewRows(exam.examId)}
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
function App() {
  const [role, setRole] = useState("office"),
    [page, setPage] = useState("home"),
    [creating, setCreating] = useState(false),
    [selectedExamId, setSelectedExamId] = useState(mockData.exams[0]?.examId),
    [apiError, setApiError] = useState(""),
    [loading, setLoading] = useState(true),
    [revision, setRevision] = useState(0),
    [authToken, setAuthToken] = useState(() => sessionStorage.getItem("exam-grading.access-token")),
    [configuring, setConfiguring] = useState(false);
  const exams = useMemo(getExams, [revision]);
  const selectedExam = exams.find((item) => item.examId === selectedExamId) ?? exams[0];
  useEffect(() => {
    const aborter = new AbortController();
    gradingApi.listExams({ signal: aborter.signal })
      .then((payload) => {
        const items = Array.isArray(payload) ? payload : payload.items ?? payload.content ?? [];
        if (items.length) { mockData.exams = items; setSelectedExamId((current) => current ?? items[0].examId); setRevision((value) => value + 1); }
        setApiError("");
      })
      .catch((cause) => { if (cause.name !== "AbortError") setApiError("Không thể tải dữ liệu máy chủ; đang hiển thị dữ liệu minh họa."); })
      .finally(() => setLoading(false));
    return () => aborter.abort();
  }, [authToken]);
  const login = async (credentials) => { const response = await gradingApi.login(credentials); sessionStorage.setItem("exam-grading.access-token", response.accessToken); setAuthToken(response.accessToken); const role = JSON.parse(atob(response.accessToken.split(".")[1].replace(/-/g,"+").replace(/_/g,"/"))).role; setRole(role === "EXAM_OFFICE" ? "office" : role === "GRADER" ? "grader" : "council"); };
  const createExam = async (form, rosterFile) => {
    const created = await gradingApi.createExam(form);
    mockData.exams = [created, ...mockData.exams];
    setSelectedExamId(created.examId); setRevision((value) => value + 1);
    return rosterFile ? gradingApi.importRoster(created.examId, rosterFile) : null;
  };
  const exportResults = async (examId) => {
    try {
      const file = await gradingApi.exportResults(examId);
      const url = URL.createObjectURL(file); const link = document.createElement("a");
      link.href = url; link.download = `ket-qua-${examId}.csv`; link.click(); URL.revokeObjectURL(url);
    } catch (cause) { setApiError(cause.message); }
  };
  useEffect(() => {
    if (!selectedExamId) return undefined;
    const aborter = new AbortController();
    Promise.all([gradingApi.listSubmissions(selectedExamId, { signal: aborter.signal }), gradingApi.listReviews(selectedExamId, { signal: aborter.signal })])
      .then(([submissions, reviews]) => {
        mockData.submissions = Array.isArray(submissions) ? submissions : submissions.items ?? [];
        mockData.reviews = Array.isArray(reviews) ? reviews : reviews.items ?? [];
        setRevision((value) => value + 1);
      })
      .catch((cause) => { if (cause.name !== "AbortError") setApiError("Một số dữ liệu chi tiết chưa tải được từ API."); });
    return () => aborter.abort();
  }, [selectedExamId]);
  const openExam = (examId) => {
    setSelectedExamId(examId);
    setPage("exam");
  };
  const change = (r) => {
    setRole(r);
    setPage("home");
    setCreating(false);
  };
  let view =
    role === "office" ? (
      page === "home" ? (
        <OfficeHome exams={exams}
          open={openExam}
          create={() => setCreating(true)}
        />
      ) : page === "final" ? (
        <Finalize back={() => setPage("exam")} />
      ) : (
        <OfficeExam
          exam={selectedExam}
          back={() => setPage("home")}
          finalize={() => setPage("final")}
          exportResults={exportResults}
          configure={() => setConfiguring(true)}
          revision={revision}
        />
      )
    ) : role === "grader" ? (
      page === "home" ? (
        <GraderHome exam={selectedExam} open={() => setPage("papers")} />
      ) : page === "papers" ? (
        <PaperList
          exam={selectedExam}
          back={() => setPage("home")}
          grade={() => setPage("grade")}
        />
      ) : (
        <Grading back={() => setPage("papers")} />
      )
    ) : page === "home" ? (
      <CouncilHome exams={exams} open={openExam} />
    ) : page === "exam" ? (
      <CouncilExam
        exam={selectedExam}
        back={() => setPage("home")}
        grade={() => setPage("grade")}
      />
    ) : (
      <Grading council back={() => setPage("exam")} />
    );
  return (
    <div className="app">
      <aside>
        <div className="brand">
          <b>EG</b>
          <div>
            <strong>ExamGrader</strong>
            <small>Chấm thi tự luận</small>
          </div>
        </div>
        <p className="side-label">KHÔNG GIAN LÀM VIỆC</p>
        <nav>
          <button
            className={role === "office" ? "active" : ""}
            onClick={() => change("office")}
          >
            ▦　Phòng khảo thí
          </button>
          <button
            className={role === "grader" ? "active" : ""}
            onClick={() => change("grader")}
          >
            ✎　Giám khảo
          </button>
          <button
            className={role === "council" ? "active" : ""}
            onClick={() => change("council")}
          >
            ♙　Hội đồng chấm thi
          </button>
        </nav>
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
              {role === "grader"
                ? "Lê Hoàng"
                : role === "council"
                  ? "Phạm Minh"
                  : "Nguyễn Thảo"}
            </b>
            <small>
              {role === "grader"
                ? "Giám khảo"
                : role === "council"
                  ? "Chủ tịch Hội đồng"
                  : "Phòng khảo thí"}
            </small>
          </div>
        </div>
      </aside>
      <div className="main">{view}</div>
      {loading && <div className="api-state">Đang kết nối API…</div>}
      {apiError && <div className="api-state warning">{apiError}</div>}
      {creating && <CreateModal onCreated={createExam} close={() => setCreating(false)} />}
      {configuring && <SetupExam examId={selectedExamId} close={() => setConfiguring(false)} refresh={() => setRevision((value) => value + 1)} />}
      {!authToken && <Login onLogin={login} />}
    </div>
  );
}
export default App;
