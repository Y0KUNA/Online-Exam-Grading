/** Selects the active council workspace view. */
export default function CouncilPage({ page, home, exam, grading }) {
  if (page === "home") return home;
  if (page === "exam") return exam;
  return grading;
}
