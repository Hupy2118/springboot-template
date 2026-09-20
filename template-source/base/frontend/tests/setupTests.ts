import '@testing-library/jest-dom';

// 补齐 Ant Design 组件在 jsdom 中常用的浏览器 API。
Object.defineProperty(window, 'matchMedia', {
  configurable: true,
  value: jest.fn().mockImplementation((query: string) => ({
    addEventListener: jest.fn(),
    addListener: jest.fn(),
    dispatchEvent: jest.fn(),
    matches: false,
    media: query,
    onchange: null,
    removeEventListener: jest.fn(),
    removeListener: jest.fn(),
  })),
  writable: true,
});

// 使用最小 ResizeObserver mock，避免组件布局观察在 jsdom 中报错。
class ResizeObserverMock implements ResizeObserver {
  disconnect(): void {}

  observe(): void {}

  unobserve(): void {}
}

Object.defineProperty(globalThis, 'ResizeObserver', {
  configurable: true,
  value: ResizeObserverMock,
  writable: true,
});

Object.defineProperty(window, 'scrollTo', {
  configurable: true,
  value: jest.fn(),
  writable: true,
});
