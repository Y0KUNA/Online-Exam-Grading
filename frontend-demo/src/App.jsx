import { useState } from "react";
import "./App.css";
import "./rubric.css";

const exam = {
  id: "KTCT-HK1-2026",
  name: "Thi kết thúc học phần Kinh tế chính trị",
  subject: "Kinh tế chính trị Mác – Lênin",
  time: "08:00 · 15/12/2026",
  classes: "K66 · Lớp 01–08",
  candidates: 184,
  submitted: 176,
  graded: 128,
  issues: 7,
};
const exams = [
  exam,
  {
    id: "PLDC-HK1-2026",
    name: "Thi kết thúc học phần Pháp luật đại cương",
    subject: "Pháp luật đại cương",
    time: "13:30 · 17/12/2026",
    classes: "K66 · Lớp 01–06",
    candidates: 156,
    submitted: 149,
    graded: 62,
    issues: 2,
  },
  {
    id: "THML-HK1-2026",
    name: "Kiểm tra Triết học Mác – Lênin",
    subject: "Triết học Mác – Lênin",
    time: "08:00 · 21/12/2026",
    classes: "K66 · Lớp 01–10",
    candidates: 221,
    submitted: 0,
    graded: 0,
    issues: 0,
  },
];
const students = [
  [
    "P-9K4M",
    "Nguyễn Minh An",
    "SV2200248",
    "15/12 · 09:18",
    "Đang chấm",
    "—",
    "8.50",
  ],
  ["P-2H8Q", "Trần Gia Bảo", "SV2200312", "15/12 · 09:24", "Đã nộp", "—", "—"],
  [
    "P-7R1X",
    "Lê Khánh Linh",
    "SV2200417",
    "15/12 · 09:31",
    "Cần xử lý",
    "Chênh lệch điểm",
    "—",
  ],
  [
    "P-5D6N",
    "Hoàng Quốc Việt",
    "SV2200556",
    "15/12 · 09:42",
    "Đã chấm",
    "Báo cáo từ GK",
    "7.75",
  ],
];
const papers = [
  ["P-9K4M", "1.50", "3.25", "3.75", "8.50", "Đang chấm"],
  ["P-6F2A", "1.25", "—", "—", "—", "Chưa chấm"],
  ["P-3L8V", "1.75", "3.50", "3.25", "8.50", "Đã chấm"],
  ["P-7R1X", "1.50", "3.00", "2.50", "7.00", "Cần xử lý"],
];
const reviews = [
  ["P-7R1X", "7.00", "9.00", "—", "Lệch 2.00 điểm, vượt ngưỡng 1.00"],
  ["P-5D6N", "7.75", "7.50", "—", "Giám khảo báo cáo bài scan mờ"],
  ["P-1A6K", "8.25", "8.50", "8.50", "Đã chốt bởi Hội đồng"],
];
const rubric = [
  {
    question: "Câu 1",
    points: 2,
    criterion: "Nội dung kiến thức",
    detail: "Xác định đúng vấn đề nghị luận",
    scores: [
      "Nêu đúng vấn đề, triển khai rõ ràng và có lập luận thuyết phục.",
      "Nêu đúng vấn đề nhưng triển khai chưa đầy đủ.",
      "Nêu được ý chính nhưng lập luận còn hạn chế.",
      "Không xác định được hoặc xác định sai vấn đề.",
    ],
  },
  {
    question: "Câu 1",
    points: 2,
    criterion: "Kỹ năng phân tích và lập luận",
    detail: "Lập luận, dẫn chứng",
    scores: [
      "Phân tích sâu, dẫn chứng phù hợp và diễn đạt mạch lạc.",
      "Phân tích được các ý chính, có dẫn chứng.",
      "Có ý phân tích nhưng chưa rõ hoặc thiếu dẫn chứng.",
      "Không có phân tích hoặc lập luận không phù hợp.",
    ],
  },
  {
    question: "Câu 2",
    points: 4,
    criterion: "Nội dung kiến thức",
    detail: "Các yếu tố hình thức và đặc trưng",
    scores: [
      "Nêu đúng, đầy đủ các yếu tố; ví dụ phù hợp.",
      "Nêu được phần lớn yếu tố, còn một vài thiếu sót.",
      "Nêu được một số yếu tố nhưng chưa đầy đủ.",
      "Không nêu được các yếu tố cần thiết.",
    ],
  },
  {
    question: "Câu 3",
    points: 4,
    criterion: "Hình thức và ngôn ngữ",
    detail: "Trình bày, diễn đạt",
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
function Info() {
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
function CreateModal({ close }) {
  const [cross, setCross] = useState(true);
  const [done, setDone] = useState(false);
  if (done)
    return (
      <Modal title="Tạo kỳ thi mới" close={close}>
        <div className="done">
          <b>✓</b>
          <h3>Đã tạo kỳ thi</h3>
          <p>
            Kỳ thi đang ở trạng thái nháp. Bạn có thể bổ sung danh sách thí sinh
            và cấu hình trước khi kích hoạt.
          </p>
          <Button onClick={close}>Hoàn tất</Button>
        </div>
      </Modal>
    );
  return (
    <Modal title="Tạo kỳ thi mới" close={close}>
      <form
        className="form"
        onSubmit={(e) => {
          e.preventDefault();
          setDone(true);
        }}
      >
        <div className="form-grid">
          <label>
            Tên kỳ thi *
            <input required placeholder="Ví dụ: Thi kết thúc học phần" />
          </label>
          <label>
            Môn thi *<input required placeholder="Chọn hoặc nhập môn thi" />
          </label>
          <label>
            Thời gian thi *<input required type="datetime-local" />
          </label>
          <label>
            Khối / Lớp *<input required placeholder="Ví dụ: K66 · Lớp 01–08" />
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
                <select defaultValue="2">
                  <option>2</option>
                  <option>3</option>
                </select>
              </label>
              <label>
                Ngưỡng chênh lệch điểm
                <input type="number" defaultValue="1.00" step=".25" />
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
          <label>
            Danh sách thí sinh (CSV, XLSX)
            <span className="file">⇧ Import roster</span>
            <input type="file" accept=".csv,.xlsx" />
          </label>
        </section>
        <div className="actions">
          <Button className="secondary" onClick={close}>
            Hủy
          </Button>
          <Button type="submit">Tạo kỳ thi</Button>
        </div>
      </form>
    </Modal>
  );
}
function ExamCard({ item, open, council = false }) {
  return (
    <button className="exam-card" onClick={open}>
      <div className="card-top">
        <span className="cal">
          15<small>THG 12</small>
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
function OfficeHome({ open, create }) {
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
function OfficeExam({ back, finalize }) {
  const [uploaded, setUploaded] = useState(false);
  return (
    <>
      <Header crumb="PHÒNG KHẢO THÍ / KỲ THI" title={exam.name}>
        <Button className="secondary" onClick={back}>
          ← Danh sách kỳ thi
        </Button>
        <Button onClick={finalize}>Chốt điểm</Button>
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
        <Info />
        <section className="table-box">
          <div className="section-head">
            <div>
              <h2>Danh sách thí sinh</h2>
              <p>
                {exam.candidates} thí sinh · {exam.submitted} bài đã nộp
              </p>
            </div>
            <label className="upload-btn">
              ⇧ {uploaded ? "Đã tải bài thi" : "Tải bài thi lên"}
              <input
                type="file"
                accept=".pdf"
                onChange={() => setUploaded(true)}
              />
            </label>
          </div>
          <Table
            headers={[
              "Số phách",
              "Thí sinh",
              "Mã SV",
              "Thời điểm nộp",
              "Trạng thái xử lý",
              "Vấn đề",
              "Điểm",
            ]}
            rows={students}
            statusAt={4}
            issueAt={5}
          />
        </section>
        <div className="actions bottom">
          <Button
            className="secondary"
            onClick={() => alert("Đã tạo tệp kết quả mẫu (.xlsx)")}
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
function GraderHome({ open }) {
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
              Bạn có <b>04 bài làm</b> cần hoàn thành trong hôm nay.
            </p>
          </div>
          <small>
            Hạn gần nhất
            <br />
            <b>16:30 · 15/12/2026</b>
          </small>
        </div>
        <button className="assigned" onClick={open}>
          <div>
            <p className="exam-id">{exam.id}</p>
            <h2>{exam.name}</h2>
            <p>{exam.subject} · Hạn chấm 16:30, 15/12/2026</p>
          </div>
          <div>
            <b>04</b>
            <span>bài được giao</span>　→
          </div>
        </button>
      </main>
    </>
  );
}
function PaperList({ back, grade }) {
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
          <div className="badge">01 / 04 hoàn thành</div>
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
                    <th>Tiêu chí</th>
                    <th>Thành phần nội dung</th>
                    <th>Mức 4</th>
                    <th>Mức 3</th>
                    <th>Mức 2</th>
                    <th>Mức 1</th>
                    <th>Điểm</th>
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
                        <b>{item.criterion}</b>
                      </td>
                      <td>{item.detail}</td>
                      {item.scores.map((text, index) => (
                        <td key={text}>
                          <b>{["Xuất sắc", "Tốt", "Đạt", "Chưa đạt"][index]}</b>
                          <span>{text}</span>
                        </td>
                      ))}
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
function CouncilHome({ open }) {
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
            <b>09</b> bài cần xử lý
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
function CouncilExam({ back, grade }) {
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
            <b>07</b> cần xử lý
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
            rows={reviews}
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
    [creating, setCreating] = useState(false);
  const change = (r) => {
    setRole(r);
    setPage("home");
    setCreating(false);
  };
  let view =
    role === "office" ? (
      page === "home" ? (
        <OfficeHome
          open={() => setPage("exam")}
          create={() => setCreating(true)}
        />
      ) : page === "final" ? (
        <Finalize back={() => setPage("exam")} />
      ) : (
        <OfficeExam
          back={() => setPage("home")}
          finalize={() => setPage("final")}
        />
      )
    ) : role === "grader" ? (
      page === "home" ? (
        <GraderHome open={() => setPage("papers")} />
      ) : page === "papers" ? (
        <PaperList
          back={() => setPage("home")}
          grade={() => setPage("grade")}
        />
      ) : (
        <Grading back={() => setPage("papers")} />
      )
    ) : page === "home" ? (
      <CouncilHome open={() => setPage("exam")} />
    ) : page === "exam" ? (
      <CouncilExam
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
      {creating && <CreateModal close={() => setCreating(false)} />}
    </div>
  );
}
export default App;
