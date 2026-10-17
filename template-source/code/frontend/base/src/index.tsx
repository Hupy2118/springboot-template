import ReactDOM from 'react-dom/client';
import App from './App';
import './index.css';
import { runAppInitializers } from './bootstrap/appInitializers';
import { applyTheme } from './config/applyTheme';

// 开发预览按需安装跨域元素审查运行时，生产构建不会加载该模块。rsbuild和vite都支持
if (import.meta.env.DEV) {
  void import('../devtools/elementInspector').then(
    ({ installElementInspector }) => {
      installElementInspector();
    },
  );
}

async function bootstrap() {
  applyTheme();
  await runAppInitializers();
  const root = ReactDOM.createRoot(
    document.getElementById('root') as HTMLElement,
  );
  root.render(<App />);
}

void bootstrap();
