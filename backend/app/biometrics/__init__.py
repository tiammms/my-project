"""Biometric & call-risk module: face (detection, liveness, matching), voice (quality, speech-to-text),
scam-call content analysis and fusion with the transaction Risk Engine.

Privacy rules enforced here:
- raw images and audio are processed in memory only and never written to disk or the database;
- a face template is stored only after explicit consent, encrypted at rest, and can be deleted at any time;
- history keeps results (levels, reasons), never images, audio, embeddings or full transcripts (unless opted in).
"""
