/** Selects the active grader workspace view. */
export default function GraderPage({ page, home, papers, grading }) {
  if (page === "home") return home;
  if (page === "papers") return papers;
  return grading;
}
