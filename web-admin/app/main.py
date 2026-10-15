"""The Interchange dedicated server's web admin.

It keeps no game state: each page shows what the server says over the admin
channel, and each button is one command on it. The game runs the same with
this container stopped.
"""

import asyncio
import logging
import os
from urllib.parse import urlsplit

from fastapi import FastAPI, Form, Request
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates

from . import auth
from .control import Control, ControlError, ServerDown

logging.basicConfig(level=os.environ.get("LOG_LEVEL", "INFO"))
LOG = logging.getLogger("interchange.web")

HERE = os.path.dirname(os.path.abspath(__file__))
control = Control()

app = FastAPI(title="Interchange Server Admin", docs_url=None, redoc_url=None)
app.mount("/static", StaticFiles(directory=os.path.join(HERE, "static")), name="static")
templates = Jinja2Templates(directory=os.path.join(HERE, "templates"))

NEW_GAME_KEYS = ("size", "seed", "year", "region", "rivals", "dayLength")


@app.on_event("startup")
async def startup():
    # Stops here without a real password, rather than serving a page anyone can guess into.
    problem = auth.problem()
    if problem:
        LOG.error(problem)
        raise RuntimeError(problem)
    LOG.info("Admin ready, control socket %s", control.path)


@app.middleware("http")
async def same_origin_posts(request: Request, call_next):
    """Refuses a POST that didn't come from this page, so another site can't make a signed-in browser press buttons."""
    if request.method == "POST":
        source = request.headers.get("origin") or request.headers.get("referer") or ""
        hosts = {request.headers.get("host", ""), request.headers.get("x-forwarded-host", "")} - {""}
        if not source or urlsplit(source).netloc not in hosts:
            LOG.warning("Refused a POST to %s from %r", request.url.path, source)
            return JSONResponse({"ok": False, "error": "Refused: not from this page"}, status_code=403)
    return await call_next(request)


def _authorised(request):
    return auth.valid(request.cookies.get(auth.COOKIE))


# --- signing in ------------------------------------------------------------


@app.get("/login", response_class=HTMLResponse)
async def login_page(request: Request, bad: int = 0):
    return templates.TemplateResponse(request, "login.html", {"bad": bad})


@app.post("/login")
async def login(request: Request, password: str = Form("")):
    address = request.client.host if request.client else "?"
    if auth.blocked(address):
        LOG.warning("Refused a sign-in from %s: too many wrong passwords", address)
        return RedirectResponse("/login?bad=2", status_code=303)
    if not auth.check(password):
        wait = auth.failed(address)
        LOG.warning("Wrong admin password from %s", address)
        # Slower with each wrong one, to make guessing slow.
        await asyncio.sleep(wait)
        return RedirectResponse("/login?bad=1", status_code=303)
    auth.succeeded(address)
    response = RedirectResponse("/", status_code=303)
    response.set_cookie(auth.COOKIE, auth.issue(), httponly=True, samesite="lax", secure=auth.secure_cookies())
    return response


@app.post("/logout")
async def logout(request: Request):
    auth.revoke(request.cookies.get(auth.COOKIE))
    response = RedirectResponse("/login", status_code=303)
    response.delete_cookie(auth.COOKIE)
    return response


# --- pages -----------------------------------------------------------------


@app.get("/", response_class=HTMLResponse)
async def dashboard(request: Request):
    if not _authorised(request):
        return RedirectResponse("/login", status_code=303)
    return templates.TemplateResponse(request, "dashboard.html", {})


@app.get("/api/state")
async def state(request: Request):
    """Everything the page shows, in one call, polled by the page."""
    if not _authorised(request):
        return JSONResponse({"error": "unauthorised"}, status_code=401)
    try:
        status = await asyncio.to_thread(control.status)
        players = await asyncio.to_thread(control.players)
        companies = await asyncio.to_thread(control.companies)
        log_lines = await asyncio.to_thread(control.log, 150)
    except (ServerDown, ControlError) as down:
        return JSONResponse({"running": False, "reason": str(down)})
    return JSONResponse(
        {"running": True, "status": status, "players": players, "companies": companies, "log": log_lines}
    )


# --- actions ---------------------------------------------------------------


async def _action(request, handler):
    """Runs one command and tells the page how it went."""
    if not _authorised(request):
        return JSONResponse({"error": "unauthorised"}, status_code=401)
    try:
        await asyncio.to_thread(handler)
        return JSONResponse({"ok": True})
    except (ServerDown, ControlError) as error:
        return JSONResponse({"ok": False, "error": str(error)})


@app.post("/api/pause")
async def pause(request: Request, paused: str = Form("1")):
    return await _action(request, lambda: control.pause(paused == "1"))


@app.post("/api/speed")
async def speed(request: Request, speed: str = Form("")):
    return await _action(request, lambda: control.speed(speed))


@app.post("/api/save")
async def save(request: Request):
    return await _action(request, control.save)


@app.post("/api/chat")
async def chat(request: Request, message: str = Form("")):
    if not message.strip():
        return JSONResponse({"ok": False, "error": "Nothing to say"})
    return await _action(request, lambda: control.chat(message.strip()))


@app.post("/api/kick")
async def kick(request: Request, name: str = Form("")):
    if not name.strip():
        return JSONResponse({"ok": False, "error": "No player named"})
    return await _action(request, lambda: control.kick(name.strip()))


@app.post("/api/unlock")
async def unlock(request: Request, company: str = Form("")):
    return await _action(request, lambda: control.unlock(company))


@app.post("/api/newgame")
async def new_game(request: Request):
    form = await request.form()
    settings = {k: str(form.get(k, "")).strip() for k in NEW_GAME_KEYS}
    return await _action(request, lambda: control.new_game({k: v for k, v in settings.items() if v}))
