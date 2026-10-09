"""Start the built application from a portable workspace, without printing secrets."""
from pathlib import Path
import os
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]

def environment():
    env = os.environ.copy()
    config = ROOT / '.env'
    if config.is_file():
        for raw in config.read_text(encoding='utf-8-sig').splitlines():
            line = raw.strip()
            if not line or line.startswith('#'):
                continue
            key, sep, value = line.partition('=')
            if not sep or not key.strip().replace('_', '').isalnum():
                raise ValueError('Invalid .env assignment')
            env.setdefault(key.strip(), value.strip().strip('\"\''))
    for key, relative in {'EKINA_WORKSPACE_DIR': '.', 'EKINA_SANDBOX_DIR': 'sandbox',
                          'EKINA_AGENT_HOME': 'AgentHome', 'EKINA_VAULT_PATH': 'AgentHome/notes',
                          'EKINA_OPENCODE_WORKDIR': 'sandbox/opencode-jobs'}.items():
        env.setdefault(key, str((ROOT / relative).resolve()))
    for key in ['EKINA_SANDBOX_DIR', 'EKINA_AGENT_HOME', 'EKINA_VAULT_PATH', 'EKINA_OPENCODE_WORKDIR']:
        Path(env[key]).mkdir(parents=True, exist_ok=True)
    return env

def main():
    env = environment()
    required = ['OPENCODE_API_KEY', 'OPENCODE_BASE_URL', 'OPENCODE_CHAT_MODEL', 'EKINA_RESEARCH_MODEL', 'SPRING_DATASOURCE_PASSWORD']
    missing = [key for key in required if not env.get(key)]
    if missing:
        raise SystemExit('Configure required variables in .env: ' + ', '.join(missing))
    jars = sorted((ROOT / 'ekina-bootstrap/target').glob('ekina-bootstrap-*.jar'))
    if len(jars) != 1:
        raise SystemExit('Expected one application JAR. Run: mvn clean package -DskipTests')
    return subprocess.call(['java', '-Dfile.encoding=UTF-8', '-jar', str(jars[0])], cwd=ROOT, env=env)

if __name__ == '__main__':
    sys.exit(main())
