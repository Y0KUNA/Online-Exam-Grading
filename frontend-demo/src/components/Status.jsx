const statusClass = (value) => value.normalize("NFD").replace(/[\u0300-\u036f]/g, "").toLowerCase().replaceAll(" ", "-");

export default function Status({ children }) {
  return <span className={`status ${statusClass(children)}`}>{children}</span>;
}
