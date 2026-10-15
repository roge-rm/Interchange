"""The admin page's password, sign-in tokens and limits on guessing.

One password, from ADMIN_PASSWORD, checked in constant time. There's one
admin, so there are no accounts.
"""

import hmac
import os
import secrets
import time

COOKIE = "interchange_admin"
_TOKENS = set()

# Failed sign-ins kept for this long, and how many are let through in that time.
WINDOW_SECONDS = 15 * 60
MOST_FAILS_PER_ADDRESS = 5
MOST_FAILS = 30
_FAILS = {}
_ALL_FAILS = []

# What .env.example used to ship; refused like an empty one.
_PLACEHOLDERS = {"", "change-me", "changeme"}


def problem():
    """Why the password can't be used, or None if it can."""
    password = os.environ.get("ADMIN_PASSWORD", "")
    if password.strip().lower() in _PLACEHOLDERS:
        return (
            "ADMIN_PASSWORD is empty or still the example's. Set your own in .env. "
            "The admin page can start a new game and kick players, so it won't run without one."
        )
    return None


def check(candidate):
    if problem():
        return False
    # compare_digest, so the time taken doesn't give away how much was right.
    return hmac.compare_digest((candidate or "").encode(), os.environ["ADMIN_PASSWORD"].encode())


def _prune(now):
    cutoff = now - WINDOW_SECONDS
    _ALL_FAILS[:] = [t for t in _ALL_FAILS if t > cutoff]
    for address in list(_FAILS):
        _FAILS[address] = [t for t in _FAILS[address] if t > cutoff]
        if not _FAILS[address]:
            del _FAILS[address]


def blocked(address):
    """Whether sign-ins are refused for now: too many failed from [address], or from everywhere."""
    _prune(time.monotonic())
    return len(_FAILS.get(address, [])) >= MOST_FAILS_PER_ADDRESS or len(_ALL_FAILS) >= MOST_FAILS


def failed(address):
    """Notes a failed sign-in, and gives the seconds to wait before answering: longer with each."""
    now = time.monotonic()
    _FAILS.setdefault(address, []).append(now)
    _ALL_FAILS.append(now)
    return min(2 ** len(_FAILS[address]), 16)


def succeeded(address):
    _FAILS.pop(address, None)


def issue():
    token = secrets.token_urlsafe(32)
    _TOKENS.add(token)
    return token


def valid(token):
    return bool(token) and token in _TOKENS


def revoke(token):
    _TOKENS.discard(token)


def secure_cookies():
    return os.environ.get("HTTPS", "0") not in ("0", "false", "no", "")
