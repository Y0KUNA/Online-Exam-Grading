export default function Modal({ title, close, children }) {
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
