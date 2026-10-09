import axios from "axios";

/** Same-origin in production (/shell/ under Spring); Vite proxies /api in dev. */
export const api = axios.create({
  baseURL: "",
  headers: { "Content-Type": "application/json" },
  timeout: 120_000,
});

api.interceptors.response.use(
  (res) => res,
  (err) => {
    const data = err.response?.data;
    const msg =
      (typeof data === "object" && data && typeof data.error === "string" && data.error) ||
      (typeof data === "object" && data && "message" in data && String(data.message)) ||
      (typeof data === "string" && data) ||
      err.message ||
      "请求失败";
    return Promise.reject(new Error(msg));
  },
);

export const USER_ID = "user-1";
