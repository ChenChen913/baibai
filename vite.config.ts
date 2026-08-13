import { defineConfig } from 'vitest/config';

export default defineConfig({
  base: './',
  server: {
    host: true,
    watch: {
      // 忽略编辑器/工具的原子写入临时目录，避免 Windows 下 EBUSY 崩溃
      ignored: ['**/.*.tmpdir/**'],
    },
  },
  test: {
    environment: 'node',
    include: ['tests/**/*.test.ts'],
  },
});
