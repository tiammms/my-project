// Camera, microphone and file helpers for the biometric pages.
// Media is captured in the browser and sent only to this local backend; nothing goes to third parties.
import { useEffect, useRef, useState } from "react";

export function mediaError(e: any, what: "камера" | "микрофон"): string {
  const n = e?.name || "";
  if (!window.isSecureContext) return `Браузер разрешает ${what === "камера" ? "камеру" : "микрофон"} только на https или http://127.0.0.1 / localhost. Откройте приложение по адресу http://127.0.0.1:8000.`;
  if (!navigator.mediaDevices?.getUserMedia) return "Этот браузер не поддерживает доступ к камере и микрофону. Используйте Edge, Chrome или Firefox.";
  if (n === "NotAllowedError" || n === "SecurityError") return `Доступ к ${what === "камера" ? "камере" : "микрофону"} запрещён. Нажмите на значок замка слева от адреса, разрешите доступ и обновите страницу.`;
  if (n === "NotFoundError" || n === "OverconstrainedError") return `${what === "камера" ? "Камера" : "Микрофон"} не найден${what === "камера" ? "а" : ""}. Подключите устройство или загрузите файл.`;
  if (n === "NotReadableError" || n === "AbortError") return `${what === "камера" ? "Камера" : "Микрофон"} занят${what === "камера" ? "а" : ""} другой программой (Zoom, Teams, Skype…). Закройте её и попробуйте снова.`;
  return `Не удалось включить ${what === "камера" ? "камеру" : "микрофон"}: ${e?.message || n || e}`;
}

export function useCamera() {
  const videoRef = useRef<any>(null);
  const streamRef = useRef<any>(null);
  const [on, setOn] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const stop = () => {
    streamRef.current?.getTracks().forEach((t: any) => t.stop());
    streamRef.current = null;
    setOn(false);
  };
  const start = async () => {
    setError(null);
    try {
      if (!navigator.mediaDevices?.getUserMedia) throw { name: "Unsupported" };
      const s = await navigator.mediaDevices.getUserMedia({ video: { width: { ideal: 640 }, height: { ideal: 480 }, facingMode: "user" }, audio: false });
      streamRef.current = s;
      if (videoRef.current) { videoRef.current.srcObject = s; await videoRef.current.play().catch(() => undefined); }
      setOn(true);
    } catch (e) { setError(mediaError(e, "камера")); stop(); }
  };
  useEffect(() => stop, []);
  // un-mirrored frame (the preview is mirrored with CSS only), downscaled for upload
  const capture = (maxW = 640, quality = 0.85): string | null => {
    const v = videoRef.current;
    if (!v || !v.videoWidth) return null;
    const s = Math.min(1, maxW / v.videoWidth);
    const c = document.createElement("canvas");
    c.width = Math.round(v.videoWidth * s); c.height = Math.round(v.videoHeight * s);
    c.getContext("2d")!.drawImage(v, 0, 0, c.width, c.height);
    return c.toDataURL("image/jpeg", quality);
  };
  return { videoRef, on, error, start, stop, capture };
}

export const fileToDataURL = (f: File) => new Promise<string>((res, rej) => {
  const r = new FileReader();
  r.onload = () => res(String(r.result));
  r.onerror = () => rej(new Error("Файл не удалось прочитать"));
  r.readAsDataURL(f);
});

// ---- microphone -> 16 kHz mono 16-bit WAV, encoded in the browser ----
function toWav(chunks: Float32Array[], inRate: number, outRate = 16000): Blob {
  const total = chunks.reduce((a, c) => a + c.length, 0);
  const all = new Float32Array(total);
  let o = 0;
  for (const c of chunks) { all.set(c, o); o += c.length; }
  const ratio = inRate / outRate;
  const n = Math.floor(total / ratio);
  const pcm = new Int16Array(n);
  for (let i = 0; i < n; i++) {               // box-filter downsampling (adequate for speech)
    const a = Math.floor(i * ratio), b = Math.min(total, Math.floor((i + 1) * ratio));
    let s = 0; for (let j = a; j < b; j++) s += all[j];
    const v = Math.max(-1, Math.min(1, s / Math.max(1, b - a)));
    pcm[i] = v < 0 ? v * 32768 : v * 32767;
  }
  const buf = new ArrayBuffer(44 + pcm.length * 2);
  const d = new DataView(buf);
  const w = (p: number, s: string) => { for (let i = 0; i < s.length; i++) d.setUint8(p + i, s.charCodeAt(i)); };
  w(0, "RIFF"); d.setUint32(4, 36 + pcm.length * 2, true); w(8, "WAVE"); w(12, "fmt ");
  d.setUint32(16, 16, true); d.setUint16(20, 1, true); d.setUint16(22, 1, true); d.setUint32(24, outRate, true);
  d.setUint32(28, outRate * 2, true); d.setUint16(32, 2, true); d.setUint16(34, 16, true); w(36, "data");
  d.setUint32(40, pcm.length * 2, true);
  new Int16Array(buf, 44).set(pcm);
  return new Blob([buf], { type: "audio/wav" });
}

export function useRecorder(maxSeconds = 60) {
  const [state, setState] = useState<"idle" | "recording">("idle");
  const [level, setLevel] = useState(0);
  const [seconds, setSeconds] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const ref = useRef<any>({});
  const stop = (): Blob | null => {
    const r = ref.current;
    if (!r.ctx) return null;
    r.proc.disconnect(); r.src.disconnect(); r.stream.getTracks().forEach((t: any) => t.stop());
    clearInterval(r.timer);
    const blob = toWav(r.chunks, r.ctx.sampleRate);
    r.ctx.close();
    ref.current = {};
    setState("idle"); setLevel(0);
    return blob;
  };
  const start = async (onAutoStop: (b: Blob) => void) => {
    setError(null); setSeconds(0);
    try {
      if (!navigator.mediaDevices?.getUserMedia) throw { name: "Unsupported" };
      const stream = await navigator.mediaDevices.getUserMedia({ audio: { channelCount: 1, echoCancellation: false, noiseSuppression: false, autoGainControl: true } });
      const Ctx = (window as any).AudioContext || (window as any).webkitAudioContext;
      const ctx = new Ctx();
      const src = ctx.createMediaStreamSource(stream);
      const proc = ctx.createScriptProcessor(4096, 1, 1);
      const chunks: Float32Array[] = [];
      proc.onaudioprocess = (ev: any) => {
        const x = ev.inputBuffer.getChannelData(0);
        chunks.push(new Float32Array(x));
        let s = 0; for (let i = 0; i < x.length; i++) s += x[i] * x[i];
        setLevel(Math.min(1, Math.sqrt(s / x.length) * 6));
      };
      src.connect(proc); proc.connect(ctx.destination);
      const t0 = Date.now();
      const timer = setInterval(() => {
        const sec = (Date.now() - t0) / 1000;
        setSeconds(sec);
        if (sec >= maxSeconds) { const b = stop(); if (b) onAutoStop(b); }
      }, 200);
      ref.current = { stream, ctx, src, proc, chunks, timer };
      setState("recording");
    } catch (e) { setError(mediaError(e, "микрофон")); }
  };
  useEffect(() => () => { if (ref.current.ctx) stop(); }, []);
  return { state, level, seconds, error, start, stop };
}

export const blobToDataURL = (b: Blob) => new Promise<string>((res) => {
  const r = new FileReader(); r.onload = () => res(String(r.result)); r.readAsDataURL(b);
});

export const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

// last check ids of this browser tab, so the combined check can pick them up
export const lastChecks = {
  get(kind: "face" | "voice"): { id: string; summary: string; level: string | null } | null {
    try { return JSON.parse(sessionStorage.getItem("ns-last-" + kind) || "null"); } catch { return null; }
  },
  set(kind: "face" | "voice", v: { id: string; summary: string; level: string | null }) {
    try { sessionStorage.setItem("ns-last-" + kind, JSON.stringify(v)); } catch { /* storage unavailable */ }
  },
};
