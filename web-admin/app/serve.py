"""Starts the admin page. The image has no shell, so the host and port come
from the environment here instead of a command line.
"""

import os

import uvicorn


def main():
    uvicorn.run(
        "app.main:app",
        host=os.environ.get("WEB_BIND", "0.0.0.0"),
        port=int(os.environ.get("WEB_PORT", "8088")),
        # Behind a reverse proxy the client's address comes in a header, believed only from these addresses.
        proxy_headers=True,
        forwarded_allow_ips=os.environ.get("FORWARDED_ALLOW_IPS", "127.0.0.1"),
        log_level=os.environ.get("LOG_LEVEL", "info").lower(),
    )


if __name__ == "__main__":
    main()
