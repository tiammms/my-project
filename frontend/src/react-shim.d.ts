// Minimal React type declarations.
// @types/react could not be installed in the build environment (no npm registry access),
// so these declare only the APIs this app uses. Component props are still type-checked.
// If you install @types/react locally, delete this file.
declare module "react" {
  export type ReactNode = any;
  export type SetState<T> = (v: T | ((prev: T) => T)) => void;
  export function useState<T>(init: T | (() => T)): [T, SetState<T>];
  export function useEffect(fn: () => void | (() => void), deps?: unknown[]): void;
  export function useMemo<T>(fn: () => T, deps: unknown[]): T;
  export function useRef<T>(init: T): { current: T };
  export function useCallback<T extends (...a: any[]) => any>(fn: T, deps: unknown[]): T;
  export const Fragment: any;
  export const StrictMode: any;
}

declare module "react/jsx-runtime" {
  export const jsx: any;
  export const jsxs: any;
  export const Fragment: any;
  export namespace JSX {
    type Element = any;
    interface IntrinsicElements { [tag: string]: any }
    interface IntrinsicAttributes { key?: string | number | null }
    interface ElementChildrenAttribute { children: {} }
  }
}

declare module "react-dom/client" {
  export function createRoot(el: Element): { render(node: any): void };
}
declare module "*.css";
