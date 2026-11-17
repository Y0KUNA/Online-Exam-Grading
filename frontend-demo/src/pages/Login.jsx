import { useState } from "react";
import Button from "../components/Button";

export default function Login({ onLogin, onRegister }) {
  const [mode, setMode] = useState("login");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [loading, setLoading] = useState(false);
  const submit = async (event) => {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    setLoading(true); setError(""); setNotice("");
    try {
      if (mode === "register") {
        if (form.get("password") !== form.get("confirmPassword")) throw new Error("Mật khẩu xác nhận chưa khớp.");
        await onRegister({ username: form.get("username"), fullName: form.get("fullName"), email: form.get("email"), password: form.get("password") });
        setNotice("Đăng ký thành công. Hãy đăng nhập bằng tài khoản Giám khảo của bạn."); setMode("login"); event.currentTarget.reset();
      } else await onLogin({ username: form.get("username"), password: form.get("password") });
    } catch (cause) { setError(cause.message); } finally { setLoading(false); }
  };
  return <div className="auth-page"><section className="auth-intro"><div className="auth-brand"><b>EG</b><span>ExamGrader</span></div><p className="crumb">HỆ THỐNG SỐ HÓA & CHẤM THI TỰ LUẬN</p><h1>{mode === "register" ? "Đăng ký tài khoản Giám khảo" : "Một cổng đăng nhập cho toàn hệ thống"}</h1><p>{mode === "register" ? "Tài khoản Giám khảo được dùng để nhận phân công và chấm bài độc lập." : "Đăng nhập bằng tài khoản được cấp. Hệ thống tự nhận diện vai trò và đưa bạn đến không gian làm việc phù hợp."}</p></section><section className="auth-panel"><div className="auth-panel-inner"><p className="crumb">{mode === "register" ? "TẠO TÀI KHOẢN" : "ĐĂNG NHẬP HỆ THỐNG"}</p><h2>{mode === "register" ? "Thông tin Giám khảo" : "Chào mừng trở lại"}</h2><p className="auth-caption">{mode === "register" ? "Chỉ Giám khảo có thể tự đăng ký. Phòng khảo thí và Hội đồng dùng tài khoản do hệ thống cấp." : "Sau khi xác thực, bạn sẽ tự động được chuyển đến giao diện theo đúng quyền của mình."}</p><form className="auth-form" onSubmit={submit}>{mode === "register" && <><label>Họ và tên<input name="fullName" required autoFocus placeholder="Nguyễn Văn A" /></label><label>Email<input name="email" type="email" placeholder="giaokhao@truong.edu.vn" /></label></>}<label>Tên đăng nhập<input name="username" required autoFocus={mode === "login"} placeholder="Nhập tên đăng nhập" /></label><label>Mật khẩu<input name="password" required type="password" minLength="8" placeholder="Tối thiểu 8 ký tự" /></label>{mode === "register" && <label>Xác nhận mật khẩu<input name="confirmPassword" required type="password" minLength="8" placeholder="Nhập lại mật khẩu" /></label>}{notice && <p className="success">✓ {notice}</p>}{error && <p className="form-error">{error}</p>}<Button type="submit" disabled={loading}>{loading ? "Đang xử lý…" : mode === "register" ? "Tạo tài khoản Giám khảo" : "Đăng nhập"}</Button></form>{mode === "login" ? <button type="button" className="auth-link" onClick={() => { setMode("register"); setError(""); }}>Chưa có tài khoản? <b>Đăng ký Giám khảo</b></button> : <button type="button" className="auth-link" onClick={() => { setMode("login"); setError(""); }}>← Quay lại đăng nhập</button>}</div></section></div>;
}
