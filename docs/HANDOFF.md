# HANDOFF — как продолжить проект в новом чате

**Все 5 частей завершены**: ML-ядро и API, дашборд, материалы для защиты, исправления и стресс-тесты, доработка сервера, эксперимент по устойчивости, GitHub.

## Состояние после части 1

**Работает и проверено:**
- `ml/data/generate.py` — синтетический генератор с seed 42 и вариантами `default/hard/scarce/low_reports`. Сюжеты: ATO, APP-скамы, мул-кольца, честные необычные клиенты.
- `ml/training/train.py` — обучение и сравнение 6 систем, выбор по validation, пороги политики, сегменты, абляции, калибровка, латентность, deletion-тест объяснений → `experiments/results/evaluation_default.json`. Артефакты: `ml/artifacts/model.joblib`, `policy.json`, `demo_transactions.csv.gz`.
- `ml/evaluation/run_variants.py` — устойчивость (seed 43/44, hard, scarce, low_reports) → `sensitivity_summary.json`.
- `scripts/make_results_md.py` → `experiments/results/RESULTS.md`. Все цифры для презентации брать **только отсюда**.
- Backend: `backend/app/` (Starlette + Pydantic). Эндпоинты: `/health`, `/api/v1/model/info`, `/risk/score`, `/risk/batch`, `/transactions` (+`/{id}`, `/{id}/explanation`), `/graph/alerts`, `/graph/view`, `/metrics/summary`, `/metrics/evaluation`, `/demo/scenarios`, `/demo/scenario`, `/audit`, `/settings` (GET/PUT). Swagger: `/docs`.
- 8 демо-сценариев в `backend/app/services/scenarios.py`.
- Тесты: `python -m unittest discover -s backend/tests -t .` — 45 тестов, все проходят.
- Backend умеет раздавать собранный фронтенд из `frontend/dist` (маршрут `/` и `/assets`).

**Ограничения среды, где делалась часть 1:** нет доступа к PyPI/npm и нет Docker daemon. См. `docs/DECISIONS.md`. Docker с тех пор проверен в GitHub Actions (часть 5).

## Часть 2 — веб-дашборд: ГОТОВА и соединена с backend

- `frontend/` — React 19 + TypeScript, сборка esbuild → `frontend/dist`. Собранный `dist` лежит в архиве, поэтому Node.js для запуска не нужен.
- Backend раздаёт дашборд на `/`, API — на `/api/v1/...`. Один процесс, один порт: `python -m backend.app.main` → http://127.0.0.1:8000.
- 8 страниц, все данные берутся из API (ничего не захардкожено): Overview, Transactions, Transaction details, Network graph, Demo Lab, Model evaluation, API playground, Settings & model card.
- Проверено: `tsc --noEmit` без ошибок; `scripts/e2e_check.mjs` проходит все страницы в headless Chromium (0 ошибок консоли и HTTP, 6/6 проверок на ложные срабатывания); backend-тесты; скриншоты в `docs/screenshots/`.
- Изменения backend в части 2: объяснения для флагнутых строк датасета считаются при загрузке (в explorer видны причины); у фан-ин алертов severity сильнее зависит от пересылки денег, чем от популярности аккаунта; `/metrics/evaluation` отдаёт HTTP-латентность.

## Часть 3 — исследование и защита: ГОТОВА

- `scripts/economics.py` → `docs/ECONOMICS.md`, `experiments/results/economics.json`, `docs/figures/econ_*.png`. Три сценария, безубыточность, чувствительность ±50%. Правилам дана та же многоуровневая обработка, что и модели.
- `presentation/NeuroShield_presentation.pptx` — 12 слайдов с заметками докладчика; генератор `presentation/build_deck.js` читает цифры из JSON. Пересборка: `PPTX_SKILL=<путь к скиллу pptx> NODE_PATH=$(npm root -g) node presentation/build_deck.js` (нужны pptxgenjs, react-icons, sharp).
- `docs/NeuroShield_report.pdf` (генератор `scripts/make_report_pdf.py`), `docs/PITCH.md`, `docs/JURY_QA.md`, `docs/VIDEO_SCRIPT.md`, `docs/ROADMAP.md`, `docs/ARCHITECTURE.md`, `docs/CHECKLIST.md`.

## Часть 4 — исправления и стресс-тесты: ГОТОВА

- Исправлены две ошибки измерения: навигация сравнивается с нормой того же сценария; pass-through считается только по переводам на счета. Модель переобучена: PR-AUC 0,862 → 0,879. См. `docs/DECISIONS.md`.
- `ml/evaluation/fairness_experiment.py`: варианты модели для счетов < 30 дней с правилом выбора, заданным заранее; опциональный отдельный порог (Settings → «New-account recipients», выключен по умолчанию).
- `ml/evaluation/adversary.py`: стресс-тест «хитрый мошенник». `scripts/load_test.py`: нагрузка с 1 и 2 процессами.
- Кроссплатформенная пересборка `python -m scripts.bootstrap [--full]` (проверена: тот же seed даёт тот же хэш модели и те же метрики); `run_windows.bat`, `run.sh`; CI `.github/workflows/ci.yml` (написан, не запускался).
- Публичные датасеты: скачать не удалось (сеть), оценка пригодности — в `docs/DECISIONS.md`.
- Презентация, PDF и тексты защиты обновлены; цифры для презентации берутся из JSON, включая `experiments/results/demo_scenarios.json`.

## Часть 5 — сервер, устойчивость, публичные данные, GitHub: ГОТОВА

- Устранена задержка 44 мс в многопроцессном режиме uvicorn (TCP_NODELAY), есть регрессионный тест.
- Эксперимент «обучение на примерах обхода» (`ml/evaluation/robust_training.py`): не принят по правилу, заданному заранее. Подробности в `docs/DECISIONS.md`.
- Скрипт проверки на публичном датасете ULB: `python -m ml.evaluation.public_ulb creditcard.csv` (данные нужно скачать самостоятельно с Kaggle).
- Проект на GitHub: https://github.com/tiammms/my-project. Первый прогон CI зелёный: тесты, фронтенд, Docker-образ и UI-проверка в контейнере.

## Часть 6 — модуль «Биометрия и звонки»: ГОТОВ

- Backend: `backend/app/biometrics/` (registry, face, liveness, audio, speech, scam_text, fusion, store) и `backend/app/api/bio_routes.py` (`/api/v1/bio/*`).
- Модели: OpenCV YuNet + SFace скачиваются `python -m scripts.download_models` (SHA-256), распознавание речи — `requirements-voice.txt` (faster-whisper). `run_windows.bat` делает всё сам.
- Frontend: 6 новых разделов в группе «Клиент: лицо и звонки».
- Тесты: `backend/tests/test_bio.py`. В CI модели скачиваются и проверяются на реальном фото и речи.
- Чего нет: детектор синтетического голоса и пассивный anti-spoofing (нет проверенных открытых моделей). Подробности и как подключить — `docs/BIOMETRICS.md`.

## Часть 7 — Android-приложение: ГОТОВО (проверено на эмуляторе)

- `android/` — проект Android Studio (Kotlin, Compose, minSdk 29, targetSdk 35).
- CI-задача `android`: unit-тесты (23) → APK (R8) → релиз `android-latest`. Задача `android-emulator`: эмулятор API 31, установка, все экраны, входящий звонок, SMS с кодом во время звонка. Скриншоты лежат в ветке `ci-screens`.
- Не проверено на реальном телефоне: анализ речи через громкую связь (зависит от прошивки) и проверка лица (в эмуляторе нет камеры).

## Как работать дальше (для вас)

- Запуск: `python -m backend.app.main` → http://127.0.0.1:8000 (Windows: `run_windows.bat`).
- Изменили код → `python -m unittest discover -s backend/tests -t .`, затем commit и push: GitHub CI проверит остальное.
- Изменили генератор или модель → `python -m scripts.bootstrap` пересоберёт данные, модель, отчёты, PDF и запустит тесты. Презентацию пересоберите командой из раздела «Часть 3».
- Есть `creditcard.csv` → `python -m ml.evaluation.public_ulb creditcard.csv`.

## Что можно улучшить дальше

- Прогнать модель на реальных данных (ULB, затем данные банка).
- Добавить новые независимые сигналы против «хитрого мошенника».
- Перейти на PostgreSQL и проверить нагрузку за обратным прокси.

## Текст для начала нового чата (скопируйте)

> Продолжаем проект NeuroShield API. Прикрепляю архив проекта. Прочитай `docs/HANDOFF.md`, `docs/DECISIONS.md` и `experiments/results/RESULTS.md`, распакуй архив и запусти тесты (`python -m unittest discover -s backend/tests -t .`). Затем выполни задачу из раздела «Что можно улучшить дальше» (или мою новую просьбу). Требования исходного ТЗ в силе: никаких выдуманных метрик, все цифры только из `experiments/results`, синтетика помечается явно. Не переделывай готовые части без причины.
