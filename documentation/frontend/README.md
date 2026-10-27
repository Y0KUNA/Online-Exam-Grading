# Frontend demo

`frontend-demo` now calls the Gateway API directly. Configure `VITE_API_BASE_URL` (default `http://localhost:8080`) and `VITE_GATEWAY_URL` in `frontend-demo/.env`. The Vite development proxy forwards `/api` and `/.well-known` to that Gateway.

For manual verification, sign in with an Identity account. The access token is held only in `sessionStorage`. EXAM_OFFICE users can create an exam and import its candidate roster from a CSV file during creation or later in **Thiết lập**. The CSV header must include `candidateNumber` and `fullName`; `className` is optional. This temporary test panel deliberately does not expose candidate data to GRADER or COUNCIL roles.

# React + Vite

This template provides a minimal setup to get React working in Vite with HMR and some Oxlint rules.

Currently, two official plugins are available:

- [@vitejs/plugin-react](https://github.com/vitejs/vite-plugin-react/blob/main/packages/plugin-react) uses [Oxc](https://oxc.rs)
- [@vitejs/plugin-react-swc](https://github.com/vitejs/vite-plugin-react/blob/main/packages/plugin-react-swc) uses [SWC](https://swc.rs/)

## React Compiler

The React Compiler is not enabled on this template because of its impact on dev & build performances. To add it, see [this documentation](https://react.dev/learn/react-compiler/installation).

## Expanding the Oxlint configuration

If you are developing a production application, we recommend using TypeScript with type-aware lint rules enabled. Check out the [TS template](https://github.com/vitejs/vite/tree/main/packages/create-vite/template-react-ts) for information on how to integrate TypeScript and Oxlint's TypeScript related rules in your project.
