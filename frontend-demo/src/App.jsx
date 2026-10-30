import { useEffect, useMemo, useState } from "react";
import "./App.css";
import "./rubric.css";
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
const getExams = (items) => items.map((item) => ({
  ...item,
  id: item.examCode,
  time: formatDateTime(item.examTime),
  day: new Intl.DateTimeFormat('vi-VN', { day: '2-digit', timeZone: 'Asia/Ho_Chi_Minh' }).format(new Date(item.examTime)),
  month: `THG ${new Intl.DateTimeFormat('vi-VN', { month: '2-digit', timeZone: 'Asia/Ho_Chi_Minh' }).format(new Date(item.examTime))}`,
  classes: item.gradeLevel,
  candidates: item.candidateCount ?? 0,
  submitted: item.submittedCount ?? 0,
  graded: item.gradedCount ?? 0,
  issues: item.reviewCount ?? 0,
}));
const paperRows = (submissions) => submissions.map((submission) => [submission.pseudonym ?? submission.pseudonymValue ?? '—', '—', '—', '—', submission.totalScore?.toFixed?.(2) ?? '—', submissionStatusLabel[submission.status] ?? submission.status ?? 'Chưa chấm']);
const reviewRows = (reviews) => reviews.map((review) => [review.pseudonym ?? review.pseudonymValue ?? '—', review.graderOneScore?.toFixed?.(2) ?? '—', review.graderTwoScore?.toFixed?.(2) ?? '—', review.finalScore?.toFixed?.(2) ?? '—', review.reason ?? review.status ?? '—']);
const rubric = [];
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
        <b>{exam.crossGradingEnabled ? `${exam.graderCount} giám khảo · ngưỡng ${Number(exam.differenceThreshold ?? 0).toFixed(2)}` : "Không chấm chéo"}</b>
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
const accessTypes = {
  office: { apiRole: "EXAM_OFFICE", icon: "▦", title: "Phòng khảo thí", detail: "Quản lý kỳ thi, danh sách thí sinh và kết quả." },
  council: { apiRole: "COUNCIL", icon: "♙", title: "Hội đồng chấm thi", detail: "Rà soát chênh lệch và quyết định điểm chốt." },
  grader: { apiRole: "GRADER", icon: "✎", title: "Giám khảo", detail: "Nhận phân công và chấm bài độc lập." },
};
function Login({ onLogin, onRegister }) {
  const [mode, setMode] = useState("login"), [error, setError] = useState(""), [notice, setNotice] = useState(""), [loading, setLoading] = useState(false);
  const submit = async (event) => {
    event.preventDefault(); const form = new FormData(event.currentTarget); setLoading(true); setError(""); setNotice("");
    try {
      if (mode === "register") {
        if (form.get("password") !== form.get("confirmPassword")) throw new Error("Mật khẩu xác nhận chưa khớp.");
        await onRegister({ username: form.get("username"), fullName: form.get("fullName"), email: form.get("email"), password: form.get("password") });
        setNotice("Đăng ký thành công. Hãy đăng nhập bằng tài khoản Giám khảo của bạn."); setMode("login"); event.currentTarget.reset();
      } else await onLogin({ username: form.get("username"), password: form.get("password") });
    } catch (cause) { setError(cause.message); } finally { setLoading(false); }
  };
  return <div className="auth-page"><section className="auth-intro"><div className="auth-brand"><b>EG</b><span>ExamGrader</span></div><p className="crumb">HỆ THỐNG SỐ HÓA & CHẤM THI TỰ LUẬN</p><h1>{mode === "register" ? "Đăng ký tài khoản Giám khảo" : "Một cổng đăng nhập cho toàn hệ thống"}</h1><p>{mode === "register" ? "Tài khoản Giám khảo được dùng để nhận phân công và chấm bài độc lập." : "Đăng nhập bằng tài khoản được cấp. Hệ thống tự nhận diện vai trò và đưa bạn đến không gian làm việc phù hợp."}</p></section><section className="auth-panel"><div className="auth-panel-inner"><p className="crumb">{mode === "register" ? "TẠO TÀI KHOẢN" : "ĐĂNG NHẬP HỆ THỐNG"}</p><h2>{mode === "register" ? "Thông tin Giám khảo" : "Chào mừng trở lại"}</h2><p className="auth-caption">{mode === "register" ? "Chỉ Giám khảo có thể tự đăng ký. Phòng khảo thí và Hội đồng dùng tài khoản do hệ thống cấp." : "Sau khi xác thực, bạn sẽ tự động được chuyển đến giao diện theo đúng quyền của mình."}</p><form className="auth-form" onSubmit={submit}>{mode === "register" && <><label>Họ và tên<input name="fullName" required autoFocus placeholder="Nguyễn Văn A" /></label><label>Email<input name="email" type="email" placeholder="giaokhao@truong.edu.vn" /></label></>}<label>Tên đăng nhập<input name="username" required autoFocus={mode === "login"} placeholder="Nhập tên đăng nhập" /></label><label>Mật khẩu<input name="password" required type="password" minLength="8" placeholder="Tối thiểu 8 ký tự" /></label>{mode === "register" && <label>Xác nhận mật khẩu<input name="confirmPassword" required type="password" minLength="8" placeholder="Nhập lại mật khẩu" /></label>}{notice && <p className="success">✓ {notice}</p>}{error && <p className="form-error">{error}</p>}<Button type="submit" disabled={loading}>{loading ? "Đang xử lý…" : mode === "register" ? "Tạo tài khoản Giám khảo" : "Đăng nhập"}</Button></form>{mode === "login" ? <button type="button" className="auth-link" onClick={() => { setMode("register"); setError(""); }}>Chưa có tài khoản? <b>Đăng ký Giám khảo</b></button> : <button type="button" className="auth-link" onClick={() => { setMode("login"); setError(""); }}>← Quay lại đăng nhập</button>}</div></section></div>;
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
  const rows = candidates.map((candidate) =>
    ['—', candidate.fullName, candidate.candidateNumber, '—', 'Chưa upload bài', '—', '—']
  ).map((row) =>
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
function GraderHome({ exam, submissions, open }) {
  const assignedCount = paperRows(submissions).length;
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
function PaperList({ exam, submissions, back, grade }) {
  const papers = paperRows(submissions);
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
            rows={papers}
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
function App() {
  const [role, setRole] = useState(() => {
      try {
        const token = sessionStorage.getItem("exam-grading.access-token");
        const apiRole = token && JSON.parse(atob(token.split(".")[1].replace(/-/g, "+").replace(/_/g, "/"))).role;
        return apiRole === "GRADER" ? "grader" : apiRole === "COUNCIL" ? "council" : "office";
      } catch { return "office"; }
    }),
    [page, setPage] = useState("home"),
    [creating, setCreating] = useState(false),
    [selectedExamId, setSelectedExamId] = useState(null),
    [examItems, setExamItems] = useState([]),
    [submissions, setSubmissions] = useState([]),
    [reviews, setReviews] = useState([]),
    [apiError, setApiError] = useState(""),
    [loading, setLoading] = useState(true),
    [revision, setRevision] = useState(0),
    [authToken, setAuthToken] = useState(() => sessionStorage.getItem("exam-grading.access-token")),
    [user, setUser] = useState(null),
    [configuring, setConfiguring] = useState(false);
  const exams = useMemo(() => getExams(examItems), [examItems]);
  const selectedExam = exams.find((item) => item.examId === selectedExamId) ?? exams[0];
  useEffect(() => {
    const aborter = new AbortController();
    gradingApi.listExams({ signal: aborter.signal })
      .then(async (payload) => {
        const items = Array.isArray(payload) ? payload : payload.items ?? payload.content ?? [];
        const enriched = await Promise.all(items.map(async (exam) => {
          const calls = [gradingApi.listSubmissions(exam.examId), gradingApi.listReviews(exam.examId)];
          if (role === "office") calls.unshift(gradingApi.listCandidates(exam.examId));
          const results = await Promise.all(calls);
          const candidates = role === "office" ? (Array.isArray(results[0]) ? results[0] : results[0].items ?? []) : null;
          const submissions = results[role === "office" ? 1 : 0];
          const reviews = results[role === "office" ? 2 : 1];
          const submissionItems = Array.isArray(submissions) ? submissions : submissions.items ?? [];
          const reviewItems = Array.isArray(reviews) ? reviews : reviews.items ?? [];
          return { ...exam, candidateCount: candidates?.length ?? exam.candidateCount ?? 0, submittedCount: submissionItems.length, gradedCount: submissionItems.filter((item) => item.status === "COMPLETED").length, reviewCount: reviewItems.filter((item) => item.status !== "RESOLVED").length };
        }));
        setExamItems(enriched); setSelectedExamId((current) => current ?? enriched[0]?.examId ?? null);
        setApiError("");
      })
      .catch((cause) => { if (cause.name !== "AbortError") { setExamItems([]); setApiError("Không thể tải dữ liệu từ máy chủ."); } })
      .finally(() => setLoading(false));
    return () => aborter.abort();
  }, [authToken, role]);
  const login = async (credentials) => {
    const response = await gradingApi.login(credentials);
    const tokenRole = JSON.parse(atob(response.accessToken.split(".")[1].replace(/-/g,"+").replace(/_/g,"/"))).role;
    const workspace = tokenRole === "GRADER" ? "grader" : tokenRole === "COUNCIL" ? "council" : tokenRole === "EXAM_OFFICE" ? "office" : null;
    if (!workspace) throw new Error("Tài khoản không có vai trò hợp lệ trong hệ thống.");
    sessionStorage.setItem("exam-grading.access-token", response.accessToken); setAuthToken(response.accessToken); setRole(workspace);
    try { setUser(await gradingApi.me({ token: response.accessToken })); } catch { setUser(null); }
  };
  const registerGrader = (registration) => gradingApi.registerGrader(registration);
  const logout = () => { sessionStorage.removeItem("exam-grading.access-token"); setAuthToken(null); setUser(null); setPage("home"); };
  const createExam = async (form, rosterFile) => {
    const created = await gradingApi.createExam(form);
    setExamItems((items) => [created, ...items]);
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
        setSubmissions(Array.isArray(submissions) ? submissions : submissions.items ?? []);
        setReviews(Array.isArray(reviews) ? reviews : reviews.items ?? []);
      })
      .catch((cause) => { if (cause.name !== "AbortError") setApiError("Một số dữ liệu chi tiết chưa tải được từ API."); });
    return () => aborter.abort();
  }, [selectedExamId]);
  const openExam = (examId) => {
    setSelectedExamId(examId);
    setPage("exam");
  };
  const noExamView = <><Header crumb="DỮ LIỆU KỲ THI" title="Chưa có kỳ thi" /><main className="content"><section className="table-box"><h2>Chưa có dữ liệu để hiển thị</h2><p>Danh sách kỳ thi sẽ xuất hiện tại đây khi máy chủ có dữ liệu phù hợp với quyền của bạn.</p></section></main></>;
  let view = !selectedExam && role !== "office" ? noExamView :
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
          <GraderHome exam={selectedExam} submissions={submissions} open={() => setPage("papers")} />
      ) : page === "papers" ? (
        <PaperList
          exam={selectedExam}
          submissions={submissions}
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
        reviews={reviews}
        back={() => setPage("home")}
        grade={() => setPage("grade")}
      />
    ) : (
      <Grading council back={() => setPage("exam")} />
    );
  if (!authToken) return <Login onLogin={login} onRegister={registerGrader} />;
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
        <nav><button className="active">{accessTypes[role].icon}　{accessTypes[role].title}</button></nav>
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
      {creating && <CreateModal onCreated={createExam} close={() => setCreating(false)} />}
      {configuring && <SetupExam examId={selectedExamId} close={() => setConfiguring(false)} refresh={() => setRevision((value) => value + 1)} />}
    </div>
  );
}
export default App;
