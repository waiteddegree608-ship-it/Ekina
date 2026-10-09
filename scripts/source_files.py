"""Shared allowlist for source checks and source-only archives."""
from pathlib import Path
import os

ROOT = Path(__file__).resolve().parents[1]
DIRECTORIES = {'ekina-common', 'ekina-domain', 'ekina-infrastructure', 'ekina-application', 'ekina-bootstrap',
               'ekina-ui', 'plugins', 'mcps', 'skills', 'personas', 'templates', 'examples', 'scripts', 'docs', 'database'}
ROOT_FILES = {'README.md', 'README.en.md', 'CONTRIBUTING.md', 'SECURITY.md', 'THIRD_PARTY_NOTICES.md',
              'LICENSE', 'pom.xml', '.gitignore', '.env.example', 'docker-compose.yml'}
EXCLUDE = {'node_modules', 'target', 'dist', '__pycache__', '.git', 'data', 'scratch', 'AgentHome', 'sandbox', 'exports'}
SUFFIXES = {'.java', '.xml', '.sql', '.json', '.yaml', '.yml', '.md', '.py', '.txt', '.vue', '.ts', '.css', '.html',
            '.svg', '.tex', '.bib', '.cls', '.bst', '.sty', '.properties', '.mjs'}

def source_files():
    for name in sorted(ROOT_FILES):
        p = ROOT / name
        if p.is_file() and not p.is_symlink():
            yield p
    for name in sorted(DIRECTORIES):
        directory = ROOT / name
        if not directory.is_dir() or directory.is_symlink():
            continue
        for current, dirs, files in os.walk(directory, followlinks=False):
            dirs[:] = sorted(d for d in dirs if d not in EXCLUDE and not Path(current, d).is_symlink())
            for filename in sorted(files):
                p = Path(current, filename)
                is_demo = p.relative_to(ROOT).as_posix() == 'docs/images/workspace-demo.png'
                if p.is_symlink() or (p.suffix not in SUFFIXES and not is_demo):
                    continue
                if '/src/main/resources/static/' in p.as_posix():
                    continue
                if p.name.startswith('.env') or p.name.endswith('.tsbuildinfo'):
                    continue
                yield p
