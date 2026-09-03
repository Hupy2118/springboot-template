import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'path';
import { sourcePathPlugin } from './scripts/sourcePathPlugin.mjs';

export default defineConfig({
  plugins: [sourcePathPlugin({ rootDir: __dirname }), react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src'),
      '@constants': path.resolve(__dirname, 'src/constants'),
      '@typings': path.resolve(__dirname, 'src/typings'),
      '@utils': path.resolve(__dirname, 'src/utils'),
    },
  },
  css: {
    preprocessorOptions: {
      less: {
        javascriptEnabled: true,
        math: 'always',
        additionalData: `@import "${path.resolve(__dirname, 'src/styles/variable.less')}";`,
      },
    },
    postcss: './postcss.config.js',
  },
  server: {
    port: 3000,
    proxy: {
      '/api': {
        changeOrigin: true,
        target: 'http://99.17.200.156:8081',
      },
    },
  },
  define: {
    'process.env.WORKS_ENV': JSON.stringify('local'),
    'process.env.TRACKING_UPLOAD_ID': JSON.stringify('MNYZJDGEDCFIDA@ST'),
    'process.env.TRACKING_API_HOST': JSON.stringify('https://lf12-32-gateway-st.paasst.cmbchina.cn/front-rest-log/v2/encrypt_upload'),
    'process.env.TRACKING_API_TRACK_HOST': JSON.stringify('http://lf12-32-gateway-st.paasst.cmbchina.cn/front-rest-log/v3/trace'),
    'process.env.BUSINESS_ID': JSON.stringify('LF1232intelligentlog'),
    'process.env.TRACE_BAGGAGE': JSON.stringify('a=1'),
    'process.env.AAA': JSON.stringify('2'),
    'process.env.BBB': JSON.stringify(''),
  },
  build: {
    outDir: 'build',
  },
});
