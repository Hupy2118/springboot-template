// 把下面tools加到行内的模板的rsbuild.config.ts中即可

import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { defineConfig } from '@rsbuild/core';

const projectRoot = fileURLToPath(new URL('.', import.meta.url));
const sourcePathLoader = fileURLToPath(
  new URL('./scripts/sourcePathLoader.cjs', import.meta.url),
);

export default defineConfig({
  tools: {
    /** 仅在 Rsbuild 开发模式中前置注入元素源码位置，生产构建保持原始输出。 */
    rspack: (_config, { addRules, isDev }) => {
      if (!isDev) return;

      addRules({
        test: /\.[jt]sx$/i,
        include: path.resolve(projectRoot, 'src'),
        enforce: 'pre',
        use: [
          {
            loader: sourcePathLoader,
            options: { rootDir: projectRoot },
          },
        ],
      });
    },
  },
});
