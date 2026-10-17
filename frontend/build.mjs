// Build the dashboard into frontend/dist (served by the backend at "/").
//   node build.mjs           production build (minified)
//   node build.mjs --watch   rebuild on change (reload the browser page served by the backend)
import * as esbuild from "esbuild";
import { createHash } from "node:crypto";
import { mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";

const watch = process.argv.includes("--watch");
rmSync("dist", { recursive: true, force: true });
mkdirSync("dist/assets", { recursive: true });

const icon = "data:image/svg+xml," + encodeURIComponent(
  '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32"><path d="M16 2 4 7v8c0 7.5 5.1 13.4 12 15 6.9-1.6 12-7.5 12-15V7z" fill="#2cc5b0"/><path d="M10 16.5l4 4 8-9" stroke="#0b1016" stroke-width="3" fill="none" stroke-linecap="round" stroke-linejoin="round"/></svg>');

// asset URLs carry a content hash so browsers never keep an old app.js / app.css after an update
const page = (v) => writeFileSync("dist/index.html", `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Saqtan</title><link rel="icon" href="${icon}"><link rel="stylesheet" href="/assets/app.css${v}">
</head><body><div id="root"></div><script src="/assets/app.js${v}"></script></body></html>
`);
page("");

const ctx = await esbuild.context({
  entryPoints: ["src/main.tsx"], bundle: true, outdir: "dist/assets", entryNames: "app",
  minify: !watch, sourcemap: watch, target: "es2020", jsx: "automatic", format: "iife",
  define: { "process.env.NODE_ENV": watch ? '"development"' : '"production"' }, logLevel: "info",
});
if (watch) { await ctx.watch(); console.log("watching src/ ..."); }
else {
  await ctx.rebuild(); await ctx.dispose();
  const h = createHash("sha256").update(readFileSync("dist/assets/app.js")).update(readFileSync("dist/assets/app.css")).digest("hex").slice(0, 10);
  page("?v=" + h);
}
