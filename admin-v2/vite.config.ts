import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  build: { target: "es2022", sourcemap: false },
  server: {
    proxy: {
      "/api/v2": "http://127.0.0.1:8080",
      "/health": "http://127.0.0.1:8080",
    },
  },
  test: {
    environment: "jsdom",
    setupFiles: ["./src/test-setup.ts"],
    restoreMocks: true,
  },
});
