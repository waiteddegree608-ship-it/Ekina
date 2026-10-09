# Ekina

**An AI research workspace with traceable evidence and reviewable outputs.**

[中文](README.md) · [Setup](docs/GETTING_STARTED.md) · [Architecture](docs/ARCHITECTURE.md) · [MCP](mcps/README.md)

Ekina brings research questions, literature evidence, experiments, writing, and review into a locally deployed workspace. Its Java backend and Vue interface preserve project context and connect research outputs to their supporting records.

## What is included

- Research projects with plans, evidence records, review records, conclusions, and checkpoints.
- Literature review campaigns with full-text import, source tracking, illustrated outputs, and revision workflows.
- Experimental paper workflows with literature search, reproduction attempts, experiment tasks, LaTeX, and citation checks.
- Reusable research skills and built-in backend capability packs.
- A standalone, read-only MCP server exposing project, review, paper, and capability queries.

This is a **source preview**, not a validated autonomous scientist. Model access and external workers need configuration. Synthetic or smoke-test metrics are not scientific results. Desktop pets, Live2D assets, personal research data, and runtime histories are excluded.

## Run locally

Requirements: Java 21, Maven 3.9+, Node.js 22.12+, Python 3.10+, and PostgreSQL 16 with pgvector. Docker Compose can provide the database.

```sh
cp .env.example .env
# Set your provider URL, model IDs, API key, and database password.
docker compose up -d db
npm --prefix ekina-ui ci
npm --prefix ekina-ui run build
python -m pip install -r scripts/requirements-review-documents.txt
mvn package -DskipTests
python scripts/run.py
```

Open http://127.0.0.1:8081/shell/ in your browser. On Windows, use `Copy-Item .env.example .env`. See [setup details](docs/GETTING_STARTED.md) for optional workers and integrations.

Project-owned source is [MIT licensed](LICENSE). Third-party dependencies retain their own licenses. Unreviewed venue templates are not bundled; see [release preparation](docs/RELEASE.md) and [third-party notices](THIRD_PARTY_NOTICES.md).
