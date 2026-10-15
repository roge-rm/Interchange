// The dashboard: plain fetch and DOM, polling one endpoint.

const POLL_MILLIS = 3000;

// As the app names them.
const REGIONS = {
  MIXED: "Mixed", PRAIRIES: "Prairies", SHIELD: "Canadian Shield",
  ROCKIES: "Rockies", MARITIMES: "Maritimes", NORTH: "North",
};

const el = (id) => document.getElementById(id);
let formFilled = false;
let paused = false;

function formatDuration(totalSeconds) {
  const seconds = Math.max(0, Math.floor(totalSeconds));
  const days = Math.floor(seconds / 86400);
  const hours = Math.floor((seconds % 86400) / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  if (days > 0) return `${days}d ${hours}h`;
  if (hours > 0) return `${hours}h ${minutes}m`;
  if (minutes > 0) return `${minutes}m ${seconds % 60}s`;
  return `${seconds}s`;
}

function formatAgo(epochMillis) {
  if (!epochMillis) return "not yet";
  return `${formatDuration((Date.now() - epochMillis) / 1000)} ago`;
}

function formatMoney(amount) {
  const text = `$${Math.abs(amount).toLocaleString("en-CA")}`;
  return amount < 0 ? `−${text}` : text;
}

function setActionResult(text, good) {
  const node = el("action-result");
  node.textContent = text || "";
  node.className = good ? "hint ok" : "error";
}

async function post(path, body) {
  const response = await fetch(path, { method: "POST", body });
  if (response.status === 401) {
    window.location = "/login";
    return { ok: false };
  }
  const result = await response.json().catch(() => ({ ok: false, error: "No answer" }));
  setActionResult(result.ok ? "Done." : result.error || "Refused.", result.ok);
  await refresh();
  return result;
}

function form(fields) {
  const data = new FormData();
  for (const [key, value] of Object.entries(fields)) data.append(key, value);
  return data;
}

function cell(text, className) {
  const td = document.createElement("td");
  td.textContent = text;
  if (className) td.className = className;
  return td;
}

function quietButton(text, onclick) {
  const button = document.createElement("button");
  button.textContent = text;
  button.className = "quiet";
  button.onclick = onclick;
  return button;
}

function renderPlayers(players) {
  const body = document.querySelector("#players tbody");
  body.innerHTML = "";
  el("nobody").hidden = players.length > 0;
  for (const player of players) {
    const row = document.createElement("tr");
    const action = cell("", "act");
    action.appendChild(quietButton("Kick", () => {
      if (confirm(`Kick ${player.name}?`)) post("/api/kick", form({ name: player.name }));
    }));
    row.append(cell(player.name), cell(player.company < 0 ? "Watching" : player.companyName), action);
    body.appendChild(row);
  }
}

function renderCompanies(companies) {
  const body = document.querySelector("#companies tbody");
  body.innerHTML = "";
  for (const company of companies) {
    const row = document.createElement("tr");
    const name = cell(company.name);
    if (!company.human) {
      const tag = document.createElement("span");
      tag.className = "tag";
      tag.textContent = "computer";
      name.append(" ", tag);
    }
    const password = cell("", "act");
    if (company.password) {
      password.appendChild(quietButton("Clear", () => {
        if (confirm(`Clear the password for ${company.name}?`)) post("/api/unlock", form({ company: company.id }));
      }));
    } else {
      password.textContent = company.human ? "None" : "";
    }
    row.append(
      name,
      cell(formatMoney(company.money), "numeric"),
      cell(formatMoney(company.value), "numeric"),
      cell(company.human ? company.players : "", "numeric"),
      password,
    );
    body.appendChild(row);
  }
}

function options(select, values, label) {
  select.innerHTML = "";
  for (const value of values) {
    const option = document.createElement("option");
    option.value = value;
    option.textContent = label(value);
    select.appendChild(option);
  }
}

// Filled once, so a refresh doesn't undo what the admin is typing.
function fillNewGame(defaults) {
  if (formFilled || !defaults) return;
  formFilled = true;
  options(el("n-size"), defaults.sizes, (s) => `${s} × ${s}`);
  options(el("n-region"), defaults.regions, (r) => REGIONS[r] || r);
  options(el("n-day-length"), defaults.dayLengths, (d) => `×${d}`);
  el("n-size").value = defaults.size;
  el("n-region").value = defaults.region;
  el("n-day-length").value = defaults.dayLength;
  el("n-seed").value = defaults.seed;
  el("n-year").value = defaults.year;
  el("n-year").min = defaults.firstYear;
  el("n-year").max = defaults.lastYear;
  el("n-rivals").value = defaults.rivals;
  el("n-rivals").max = defaults.mostRivals;
}

async function refresh() {
  let state;
  try {
    const response = await fetch("/api/state");
    if (response.status === 401) {
      window.location = "/login";
      return;
    }
    state = await response.json();
  } catch (error) {
    el("offline-reason").textContent = "The admin page can't be reached.";
    el("offline").hidden = false;
    return;
  }

  if (!state.running) {
    el("offline-reason").textContent = state.reason || "Not running.";
    el("offline").hidden = false;
    el("online").hidden = true;
    return;
  }
  el("offline").hidden = true;
  el("online").hidden = false;

  const status = state.status;
  const making = status.making;
  el("server-name").textContent = status.name || "Server";
  el("making").hidden = !making;
  el("s-date").textContent = making ? "-" : status.date + (status.paused ? ", paused" : "");
  el("s-speed").textContent = making ? "-" : `${status.speed}×`;
  el("s-map").textContent = making ? "-" : `${status.mapWidth} × ${status.mapHeight}`;
  el("s-region").textContent = making ? "-" : REGIONS[status.region] || status.region;
  el("s-seed").textContent = making ? "-" : status.seed;
  el("s-players").textContent = making ? "-" : status.players;
  el("s-save").textContent = formatAgo(status.lastSaveEpochMillis);
  el("s-uptime").textContent = formatDuration(status.uptimeSeconds);
  el("s-port").textContent = status.port;

  paused = !!status.paused;
  el("pause").textContent = paused ? "Resume" : "Pause";
  for (const button of document.querySelectorAll("#speeds button")) {
    button.classList.toggle("on", Number(button.dataset.speed) === status.speed);
  }

  renderPlayers(state.players || []);
  renderCompanies(state.companies || []);
  fillNewGame(status.newGame);

  // Kept where it is unless it was at the bottom already.
  const log = el("log");
  const atBottom = log.scrollHeight - log.scrollTop - log.clientHeight < 40;
  log.textContent = (state.log || []).join("\n");
  if (atBottom) log.scrollTop = log.scrollHeight;
}

el("pause").onclick = () => post("/api/pause", form({ paused: paused ? "0" : "1" }));

for (const button of document.querySelectorAll("#speeds button")) {
  button.onclick = () => post("/api/speed", form({ speed: button.dataset.speed }));
}

el("save").onclick = () => post("/api/save");

el("chat-form").onsubmit = async (event) => {
  event.preventDefault();
  const input = el("chat");
  if (!input.value.trim()) return;
  const result = await post("/api/chat", form({ message: input.value }));
  if (result.ok) input.value = "";
};

el("new-game").onsubmit = async (event) => {
  event.preventDefault();
  if (!confirm("Start a new game? Everyone is let go, and this game is kept as game.ichg.old.")) return;
  const result = await post("/api/newgame", new FormData(el("new-game")));
  // A blank seed stays blank, for a new random one next time.
  if (result.ok) formFilled = false;
};

refresh();
setInterval(refresh, POLL_MILLIS);
