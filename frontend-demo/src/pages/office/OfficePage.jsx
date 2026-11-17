/** Selects the active exam-office workspace view. Individual screens stay injectable
 * so data loading and navigation state remain owned by the application shell. */
export default function OfficePage({ page, home, exam, final }) {
  if (page === "home") return home;
  if (page === "final") return final;
  return exam;
}
