const statusClass = (value) => value.normalize("NFD").replace(/[\u0300-\u036f]/g, "").toLowerCase().replaceAll(" ", "-");

export default function Table({ headers, rows, statusAt, issueAt, action }) {
  return (
    <div className="table-wrap">
      <table>
        <thead><tr>{headers.map((header) => <th key={header}>{header}</th>)}</tr></thead>
        <tbody>
          {rows.map((row, index) => (
            <tr key={row[0]}>
              {row.map((value, column) => (
                <td key={column} className={column === issueAt && value !== "—" ? "issue" : ""}>
                  {column === 0 || (column === row.length - 1 && column !== statusAt) ? <b>{value}</b> : column === statusAt ? <span className={`status ${statusClass(value)}`}>{value}</span> : value}
                </td>
              ))}
              {action && <td>{action(row, index)}</td>}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
