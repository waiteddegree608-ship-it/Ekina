import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
import tailwindcss from "@tailwindcss/vite";
import { fileURLToPath, URL } from "node:url";

// Build into Spring Boot static so companion /shell/ serves the real app.
const outDir = fileURLToPath(
  new URL("../ekina-bootstrap/src/main/resources/static/shell", import.meta.url),
);

export default defineConfig({
  plugins: [vue(), tailwindcss()],
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },
  base: "/shell/",
  server: {
    port: 5173,
    proxy: {
      "/api": {
        target: "http://127.0.0.1:8081",
        changeOrigin: true,
      },
      "/assets": {
        target: "http://127.0.0.1:8081",
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir,
    emptyOutDir: true,
    assetsDir: "assets-built",
  },
});
