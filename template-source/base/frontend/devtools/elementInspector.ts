const INSPECTOR_CHANNEL = 'xcode-agent:element-inspector';
const INSPECTOR_VERSION = 1;
const SOURCE_PATTERN = /^(\/src\/[^:]+):(\d+):(\d+)$/;
const OVERLAY_CLASS = '__xcode-agent-element-inspector-overlay';
const ACTIVE_CLASS = '__xcode-agent-element-inspector-active';
const STYLE_ID = '__xcode-agent-element-inspector-style';

type InspectorCommand = {
  channel: typeof INSPECTOR_CHANNEL;
  version: typeof INSPECTOR_VERSION;
  type: 'set-active';
  active: boolean;
};

type SourceLocation = {
  sourcePath: string;
  line: number;
  column: number;
};

type InspectorWindow = Window & {
  __xcodeAgentElementInspectorCleanup__?: () => void;
};

/** 判断父窗口消息是否为受支持的审查启停命令。 */
function isInspectorCommand(value: unknown): value is InspectorCommand {
  if (!value || typeof value !== 'object') return false;
  const message = value as Partial<InspectorCommand>;
  return (
    message.channel === INSPECTOR_CHANNEL &&
    message.version === INSPECTOR_VERSION &&
    message.type === 'set-active' &&
    typeof message.active === 'boolean'
  );
}

/** 解析编译期注入的 data-path，并拒绝业务自定义或格式异常的属性值。 */
function parseSourceLocation(value: string | null): SourceLocation | null {
  const match = String(value || '').match(SOURCE_PATTERN);
  if (!match) return null;
  const line = Number(match[2]);
  const column = Number(match[3]);
  if (!Number.isSafeInteger(line) || !Number.isSafeInteger(column) || line <= 0 || column <= 0) {
    return null;
  }
  return { sourcePath: match[1], line, column };
}

/** 从实际点击元素向上寻找第一个带有效源码位置的祖先。 */
function findSourceLocation(element: Element): SourceLocation | null {
  let current: Element | null = element;
  while (current) {
    const location = parseSourceLocation(current.getAttribute('data-path'));
    if (location) return location;
    current = current.parentElement;
  }
  return null;
}

/** 创建高亮层所需样式，标签通过伪元素渲染以保持单一遮罩节点。 */
function ensureOverlayStyle(): HTMLStyleElement {
  const existing = document.getElementById(STYLE_ID);
  if (existing instanceof HTMLStyleElement) return existing;
  const style = document.createElement('style');
  style.id = STYLE_ID;
  style.textContent = `
    html.${ACTIVE_CLASS},
    html.${ACTIVE_CLASS} * {
      cursor: crosshair !important;
    }
    .${OVERLAY_CLASS} {
      position: fixed;
      z-index: 2147483647;
      display: none;
      box-sizing: border-box;
      pointer-events: none;
      background:
        linear-gradient(90deg, #ff2d2d 50%, transparent 50%) 0 0 / 10px 2px repeat-x,
        linear-gradient(90deg, #ff2d2d 50%, transparent 50%) 0 100% / 10px 2px repeat-x,
        linear-gradient(0deg, #ff2d2d 50%, transparent 50%) 0 0 / 2px 10px repeat-y,
        linear-gradient(0deg, #ff2d2d 50%, transparent 50%) 100% 0 / 2px 10px repeat-y;
      animation:
        xcode-agent-inspector-border-flow 0.65s linear infinite,
        xcode-agent-inspector-glow 1.6s ease-in-out infinite;
      will-change: background-position, box-shadow;
    }
    .${OVERLAY_CLASS}::before {
      position: absolute;
      left: -2px;
      bottom: 100%;
      max-width: 240px;
      padding: 2px 6px;
      overflow: hidden;
      color: #ffffff;
      font: 12px/18px ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
      text-overflow: ellipsis;
      white-space: nowrap;
      content: attr(data-inspector-tag);
      background: #ff2d2d;
      border-radius: 3px 3px 0 0;
    }
    .${OVERLAY_CLASS}[data-label-inside='true']::before {
      top: -2px;
      bottom: auto;
      border-radius: 0 0 3px 0;
    }
    @keyframes xcode-agent-inspector-border-flow {
      to {
        background-position: 10px 0, -10px 100%, 0 -10px, 100% 10px;
      }
    }
    @keyframes xcode-agent-inspector-glow {
      0%, 100% {
        box-shadow: 0 0 0 1px rgba(255, 45, 45, 0.18), 0 0 8px rgba(255, 45, 45, 0.2);
      }
      50% {
        box-shadow: 0 0 0 3px rgba(255, 45, 45, 0.1), 0 0 16px rgba(255, 45, 45, 0.42);
      }
    }
    @media (prefers-reduced-motion: reduce) {
      .${OVERLAY_CLASS} {
        border: 2px solid #ff2d2d;
        background: none;
        animation: none;
        box-shadow: 0 0 8px rgba(255, 45, 45, 0.28);
      }
    }
  `;
  document.head.appendChild(style);
  return style;
}

/** 安装预览 iframe 内元素审查器，并复用全局清理句柄避免热更新重复监听。 */
export function installElementInspector(): () => void {
  const inspectorWindow = window as InspectorWindow;
  inspectorWindow.__xcodeAgentElementInspectorCleanup__?.();

  const style = ensureOverlayStyle();
  const overlay = document.createElement('div');
  overlay.className = OVERLAY_CLASS;
  overlay.setAttribute('aria-hidden', 'true');
  document.documentElement.appendChild(overlay);

  let active = false;
  let hoveredElement: Element | null = null;
  let animationFrame = 0;

  /** 根据当前元素的视口矩形刷新红框和标签位置。 */
  const renderOverlay = (): void => {
    animationFrame = 0;
    if (!active || !hoveredElement || !hoveredElement.isConnected) {
      overlay.style.display = 'none';
      return;
    }
    const rect = hoveredElement.getBoundingClientRect();
    if (rect.width <= 0 || rect.height <= 0) {
      overlay.style.display = 'none';
      return;
    }
    overlay.dataset.inspectorTag = hoveredElement.tagName.toLowerCase();
    overlay.dataset.labelInside = String(rect.top < 22);
    overlay.style.display = 'block';
    overlay.style.left = `${Math.max(0, rect.left)}px`;
    overlay.style.top = `${Math.max(0, rect.top)}px`;
    overlay.style.width = `${Math.max(0, Math.min(rect.right, window.innerWidth) - Math.max(0, rect.left))}px`;
    overlay.style.height = `${Math.max(0, Math.min(rect.bottom, window.innerHeight) - Math.max(0, rect.top))}px`;
  };

  /** 合并同一帧内的多次鼠标移动和布局变化，降低预览页面抖动。 */
  const scheduleOverlayRender = (): void => {
    if (animationFrame) return;
    animationFrame = window.requestAnimationFrame(renderOverlay);
  };

  /** 记录真实 hover 元素，不让高亮层自身干扰命中测试。 */
  const handlePointerMove = (event: PointerEvent): void => {
    if (!active) return;
    const target = event.composedPath().find(
      (item): item is Element =>
        item instanceof Element && item !== overlay && item !== document.documentElement,
    );
    hoveredElement = target || null;
    scheduleOverlayRender();
  };

  /** 鼠标离开 iframe 文档时隐藏最后一次 hover，避免红框滞留在旧元素上。 */
  const handlePointerLeave = (): void => {
    if (!active) return;
    hoveredElement = null;
    overlay.style.display = 'none';
  };

  /** 拦截审查点击并把元素标签及最近源码位置发送给父窗口。 */
  const handleClick = (event: MouseEvent): void => {
    if (!active) return;
    event.preventDefault();
    event.stopPropagation();
    event.stopImmediatePropagation();
    const target = event.composedPath().find(
      (item): item is Element =>
        item instanceof Element && item !== overlay && item !== document.documentElement,
    );
    if (!target) return;
    window.parent.postMessage(
      {
        channel: INSPECTOR_CHANNEL,
        version: INSPECTOR_VERSION,
        type: 'element-selected',
        tagName: target.tagName.toLowerCase(),
        sourceLocation: findSourceLocation(target),
      },
      '*',
    );
  };

  /** 接收父窗口的启停指令并同步清理当前高亮。 */
  const handleMessage = (event: MessageEvent): void => {
    if (event.source !== window.parent || !isInspectorCommand(event.data)) return;
    active = event.data.active;
    document.documentElement.classList.toggle(ACTIVE_CLASS, active);
    if (!active) {
      hoveredElement = null;
      overlay.style.display = 'none';
    }
  };

  window.addEventListener('message', handleMessage);
  document.addEventListener('pointermove', handlePointerMove, true);
  document.documentElement.addEventListener('pointerleave', handlePointerLeave);
  document.addEventListener('click', handleClick, true);
  document.addEventListener('scroll', scheduleOverlayRender, true);
  window.addEventListener('resize', scheduleOverlayRender);
  window.parent.postMessage(
    { channel: INSPECTOR_CHANNEL, version: INSPECTOR_VERSION, type: 'ready' },
    '*',
  );

  /** 移除审查器创建的全部监听器和 DOM，供热更新或页面卸载复用。 */
  const cleanup = (): void => {
    window.removeEventListener('message', handleMessage);
    document.removeEventListener('pointermove', handlePointerMove, true);
    document.documentElement.removeEventListener('pointerleave', handlePointerLeave);
    document.removeEventListener('click', handleClick, true);
    document.removeEventListener('scroll', scheduleOverlayRender, true);
    window.removeEventListener('resize', scheduleOverlayRender);
    if (animationFrame) window.cancelAnimationFrame(animationFrame);
    document.documentElement.classList.remove(ACTIVE_CLASS);
    overlay.remove();
    style.remove();
    if (inspectorWindow.__xcodeAgentElementInspectorCleanup__ === cleanup) {
      delete inspectorWindow.__xcodeAgentElementInspectorCleanup__;
    }
  };

  inspectorWindow.__xcodeAgentElementInspectorCleanup__ = cleanup;
  return cleanup;
}
