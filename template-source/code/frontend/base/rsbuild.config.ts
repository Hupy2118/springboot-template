import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { defineConfig } from '@rsbuild/core';
import { pluginReact } from '@rsbuild/plugin-react';

const projectRoot = fileURLToPath(new URL('.', import.meta.url));
const sourcePathLoader = fileURLToPath(
  new URL('./scripts/sourcePathLoader.cjs', import.meta.url),
);

const clientDefines = {
  'process.env.WORKS_ENV': JSON.stringify('local'),
  'process.env.TRACKING_UPLOAD_ID': JSON.stringify('MNYZJDGEDCFIDA@ST'),
  'process.env.TRACKING_API_HOST': JSON.stringify('https://lf12-32-gateway-st.paasst.cmbchina.cn/front-rest-log/v2/encrypt_upload'),
  'process.env.TRACKING_API_TRACK_HOST': JSON.stringify('http://lf12-32-gateway-st.paasst.cmbchina.cn/front-rest-log/v3/trace'),
  'process.env.BUSINESS_ID': JSON.stringify('LF1232intelligentlog'),
  'process.env.TRACE_BAGGAGE': JSON.stringify('a=1'),
  'process.env.AAA': JSON.stringify('2'),
  'process.env.BBB': JSON.stringify(''),
};

export default defineConfig({
  plugins: [pluginReact()],
  source: {
    entry: { index: './src/index.tsx' },
    alias: {
      '@': path.resolve(projectRoot, 'src'),
      '@constants': path.resolve(projectRoot, 'src/constants'),
      '@typings': path.resolve(projectRoot, 'src/typings'),
      '@utils': path.resolve(projectRoot, 'src/utils'),
    },
    define: clientDefines,
  },
  html: { template: './index.html' },
  server: {
    host: '127.0.0.1',
    port: 3000,
    proxy: {
      '/api': {
        changeOrigin: true,
        target: 'http://127.0.0.1:8080',
      },
    },
  },
  output: { distPath: { root: 'build' } },
  tools: {
    postcss: { postcssOptions: { config: path.resolve(projectRoot, 'postcss.config.js') } },
    // 在默认 JSX 转换前复用既有 Babel 转换；生产构建不注入调试元数据。
    rspack: (_config, { addRules, isDev }) => {
      if (!isDev) return;
      addRules({
        test: /\.[jt]sx$/i,
        include: path.resolve(projectRoot, 'src'),
        enforce: 'pre',
        use: [{ loader: sourcePathLoader, options: { rootDir: projectRoot } }],
      });
    },
  },
});
