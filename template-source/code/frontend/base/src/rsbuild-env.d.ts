/** Rsbuild/Rspack 的静态页面模块上下文类型。 */
interface RspackModuleContext<T> {
  (request: string): Promise<T>;
  keys(): string[];
  resolve(request: string): string;
  id: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
  webpackContext<T>(
    directory: string,
    options: {
      recursive: boolean;
      regExp: RegExp;
      mode: 'lazy';
    },
  ): RspackModuleContext<T>;
}

interface ImportMetaEnv {
  readonly DEV: boolean;
  readonly PROD: boolean;
  readonly MODE: string;
}
