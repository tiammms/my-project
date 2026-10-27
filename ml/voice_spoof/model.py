"""Small real-vs-synthetic speech classifier that runs on a phone.

Input: 4.04 s of 16 kHz mono audio (64600 samples, float = int16 / 32768), exactly what the app collects.
The spectrogram is computed INSIDE the network (an STFT written as a fixed 1-D convolution), so the app only passes
raw samples and there is no feature code to keep in sync between Python and Kotlin.

  STFT (512-point Hann window, 10 ms hop) -> log power -> per-clip mean removed per frequency (removes the
  microphone/codec colouring, which differs between calls) -> 4 conv blocks -> mean+max pooling -> P(synthetic)

About 0.1 M parameters; one 4-second piece takes a few milliseconds on a phone CPU.
"""
from __future__ import annotations

import math

import torch
import torch.nn as nn
import torch.nn.functional as F

N_FFT, HOP = 512, 160


class LogSpec(nn.Module):
    def __init__(self):
        super().__init__()
        k = torch.arange(N_FFT, dtype=torch.float32)
        win = 0.5 - 0.5 * torch.cos(2 * math.pi * k / N_FFT)
        f = torch.arange(N_FFT // 2 + 1, dtype=torch.float32)[:, None]
        ang = 2 * math.pi * f * k[None, :] / N_FFT
        w = torch.cat([torch.cos(ang) * win, -torch.sin(ang) * win])[:, None, :]      # (2F, 1, N_FFT)
        self.register_buffer("w", w)

    def forward(self, x):                                   # x: (B, N)
        y = F.conv1d(x[:, None, :], self.w, stride=HOP)     # (B, 2F, T)
        nf = N_FFT // 2 + 1
        p = y[:, :nf] ** 2 + y[:, nf:] ** 2
        s = torch.log(p + 1e-7)
        return s - s.mean(dim=2, keepdim=True)              # (B, F, T), channel colouring removed


def block(cin, cout):
    return nn.Sequential(nn.Conv2d(cin, cout, 3, padding=1, bias=False), nn.BatchNorm2d(cout), nn.ReLU(inplace=True),
                         nn.Conv2d(cout, cout, 3, padding=1, bias=False), nn.BatchNorm2d(cout), nn.ReLU(inplace=True),
                         nn.MaxPool2d(2))


class SpoofNet(nn.Module):
    def __init__(self):
        super().__init__()
        self.spec = LogSpec()
        self.body = nn.Sequential(block(1, 16), block(16, 24), block(24, 32), block(32, 48))
        self.drop = nn.Dropout(0.3)
        self.out = nn.Linear(96, 2)

    def features(self, x):
        return self.spec(x)

    def forward(self, x, specaug: bool = False):
        s = self.spec(x)
        if specaug and self.training:                       # SpecAugment: hide random frequency bands and time spans
            b, nf, nt = s.shape
            for _ in range(2):
                f0 = torch.randint(0, nf - 30, (1,)).item(); fw = torch.randint(0, 30, (1,)).item()
                s[:, f0:f0 + fw, :] = 0
                t0 = torch.randint(0, nt - 40, (1,)).item(); tw = torch.randint(0, 40, (1,)).item()
                s[:, :, t0:t0 + tw] = 0
        h = self.body(s[:, None])                           # (B, C, F', T')
        h = torch.cat([h.mean(dim=(2, 3)), h.amax(dim=(2, 3))], dim=1)
        return self.out(self.drop(h))                       # logits: index 0 = synthetic, 1 = real


class Exported(nn.Module):
    """App-facing graph: raw samples in, P(synthetic) out."""
    def __init__(self, m: SpoofNet):
        super().__init__()
        self.m = m

    def forward(self, audio):
        return F.softmax(self.m(audio), dim=1)[:, 0:1]
