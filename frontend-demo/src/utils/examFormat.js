export const formatDateTime = (value) => new Intl.DateTimeFormat("vi-VN", {
  hour: "2-digit", minute: "2-digit", day: "2-digit", month: "2-digit", timeZone: "Asia/Ho_Chi_Minh",
}).format(new Date(value)).replace(",", " ·");

export const mapExams = (items) => items.map((item) => ({
  ...item,
  id: item.examCode,
  time: formatDateTime(item.examTime),
  day: new Intl.DateTimeFormat("vi-VN", { day: "2-digit", timeZone: "Asia/Ho_Chi_Minh" }).format(new Date(item.examTime)),
  month: `THG ${new Intl.DateTimeFormat("vi-VN", { month: "2-digit", timeZone: "Asia/Ho_Chi_Minh" }).format(new Date(item.examTime))}`,
  classes: item.gradeLevel,
  candidates: item.candidateCount ?? 0,
  submitted: item.submittedCount ?? 0,
  graded: item.gradedCount ?? 0,
  issues: item.reviewCount ?? 0,
}));
