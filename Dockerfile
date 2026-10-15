# Built and smoke-tested on every push by GitHub Actions (.github/workflows/ci.yml, job docker-e2e).
# Stage 1: build the dashboard (frontend/dist is also shipped prebuilt, so this stage is optional).
FROM node:22-slim AS web
WORKDIR /web
COPY frontend/package.json ./
RUN npm install --no-audit --no-fund
COPY frontend/ ./
RUN rm -rf node_modules/.cache && node build.mjs

# Stage 2: API + dashboard on one port.
FROM python:3.13-slim
WORKDIR /app
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 OMP_NUM_THREADS=1
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY backend ./backend
COPY ml ./ml
COPY experiments/results ./experiments/results
COPY scripts ./scripts
COPY --from=web /web/dist ./frontend/dist
RUN useradd -m appuser && mkdir -p backend/data && chown -R appuser /app/backend/data
USER appuser
EXPOSE 8000
CMD ["uvicorn", "backend.app.main:app", "--host", "0.0.0.0", "--port", "8000"]
