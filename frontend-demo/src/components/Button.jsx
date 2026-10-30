export default function Button({ children, className = "", ...props }) {
  return (
    <button type="button" className={`button ${className}`} {...props}>
      {children}
    </button>
  );
}
