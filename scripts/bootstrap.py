"""Cross-platform full rebuild (Windows / macOS / Linux): data -> training -> fairness -> demo set ->
adversary -> reports -> (frontend) -> tests.   Usage: python -m scripts.bootstrap [--full]
--full also re-runs the robustness experiments (other seeds / harsher data, ~8 min) and the load test."""
import importlib.util
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def run(*args, cwd=ROOT):
    print("\n>>", " ".join(args), flush=True)
    subprocess.run([sys.executable, *args] if args[0] == "-m" else list(args), cwd=cwd, check=True)


def main():
    full = "--full" in sys.argv
    run("-m", "ml.data.generate", "--variant", "default", "--seed", "42")
    run("-m", "ml.training.train")
    run("-m", "ml.evaluation.fairness_experiment")      # also writes the opt-in policy override
    run("-m", "scripts.export_demo_set")
    run("-m", "ml.evaluation.adversary")
    if full:
        run("-m", "ml.evaluation.run_variants")
        run("-m", "scripts.load_test")
        run("-m", "ml.evaluation.robust_training")
    run("-m", "scripts.snapshot_scenarios")
    run("-m", "scripts.make_results_md")
    if importlib.util.find_spec("matplotlib") and importlib.util.find_spec("reportlab"):
        run("-m", "scripts.economics")
        run("-m", "scripts.make_report_pdf")
    else:
        print("matplotlib/reportlab missing: skipping economics charts and PDF (pip install -r requirements-docs.txt)")
    node = shutil.which("node")
    if node and (ROOT / "frontend/node_modules").exists():
        run(node, "build.mjs", cwd=ROOT / "frontend")
    else:
        print("node or frontend/node_modules missing: keeping the prebuilt frontend/dist")
    run("-m", "unittest", "discover", "-s", "backend/tests", "-t", ".")


if __name__ == "__main__":
    main()
