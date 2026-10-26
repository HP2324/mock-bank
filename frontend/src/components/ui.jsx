import { useEffect } from 'react';

export function Panel({ title, children }) {
  return (
    <section className="panel">
      <h1>{title}</h1>
      <div className="panel-body">{children}</div>
    </section>
  );
}

/** type: "success" | "warning" | "error". Automation matches on `.banner.<type>`. */
export function Banner({ type, children }) {
  return (
    <div className={`banner ${type}`} role="alert">
      {children}
    </div>
  );
}

/** Two-column label/value table; automation reads values by their <th> label. */
export function FieldTable({ rows }) {
  return (
    <table className="field-table">
      <tbody>
        {rows.map(([label, value, cellProps]) => (
          <tr key={label}>
            <th>{label}</th>
            <td {...cellProps}>{value}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

export function usePageTitle(title) {
  useEffect(() => {
    document.title = title;
  }, [title]);
}

/** 404 member lookups are a business outcome (warning); everything else is an error. */
export function bannerTypeFor(error) {
  return error?.status === 404 ? 'warning' : 'error';
}
