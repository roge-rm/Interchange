# The Interchange server's web admin. It keeps no game state, so it can be
# restarted or left out without the game noticing. The build context is the
# repository root.

# Python 3.11 to match the distroless image below, as site-packages are copied across whole.
FROM python:3.11-slim-bookworm AS build

COPY web-admin/requirements.txt /requirements.txt

RUN pip install --no-cache-dir --target /packages -r /requirements.txt \
 && find /packages -name '__pycache__' -type d -prune -exec rm -rf {} + \
 && find /packages -name '*.dist-info' -type d -prune -exec rm -rf {} +

# Distroless: Python and nothing else, no shell.
FROM gcr.io/distroless/python3-debian12

COPY --from=build /packages /packages
COPY web-admin/app /app/app

ENV PYTHONPATH=/packages:/app \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    INTERCHANGE_CONTROL_SOCKET=/run/interchange/control.sock \
    WEB_PORT=8088 \
    WEB_BIND=0.0.0.0

WORKDIR /app
EXPOSE 8088/tcp

# The server's uid, as the control socket's file permissions are all that guard it.
USER 10003:10003

ENTRYPOINT ["/usr/bin/python3.11", "-m", "app.serve"]
