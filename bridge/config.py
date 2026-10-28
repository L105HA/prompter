import os
from pathlib import Path

BASE_DIR = Path(__file__).parent
TOKEN_FILE = BASE_DIR / ".token"

def get_token() -> str:
    if TOKEN_FILE.exists():
        return TOKEN_FILE.read_text().strip()
    token = os.urandom(32).hex()
    TOKEN_FILE.write_text(token)
    try:
        TOKEN_FILE.chmod(0o600)
    except OSError:
        pass
    return token

HOST = os.getenv("PROMPTER_HOST", "0.0.0.0")
PORT = int(os.getenv("PROMPTER_PORT", "8788"))
TMUX_SESSION = os.getenv("PROMPTER_TMUX_SESSION", "opencode")
TOKEN = get_token()
