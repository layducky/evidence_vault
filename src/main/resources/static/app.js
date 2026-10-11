// Minimal UI over the REST API. No build step, no framework.

const $ = (sel) => document.querySelector(sel);
const RECENT_KEY = "ev.recentCases";

async function api(path, options = {}) {
  const res = await fetch(path, {
    headers: { "Content-Type": "application/json" },
    ...options,
  });
  const body = res.status === 204 ? null : await res.json().catch(() => null);
  if (!res.ok) {
    // Errors come back as ProblemDetail: { title, status, detail, ... }
    const msg = body?.detail || body?.title || `HTTP ${res.status}`;
    throw new Error(`${res.status}: ${msg}`);
  }
  return body;
}

function toast(message, isError = false) {
  const el = $("#toast");
  el.textContent = message;
  el.className = isError ? "error" : "";
  el.hidden = false;
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => (el.hidden = true), 4000);
}

function formJson(form) {
  return Object.fromEntries(new FormData(form).entries());
}

// --- recent cases (per browser) ---

function loadRecent() {
  try {
    return JSON.parse(localStorage.getItem(RECENT_KEY)) || [];
  } catch {
    return [];
  }
}

function saveRecent(c) {
  const list = [{ id: c.id, caseNumber: c.caseNumber, title: c.title },
    ...loadRecent().filter((x) => x.id !== c.id)].slice(0, 10);
  try {
    localStorage.setItem(RECENT_KEY, JSON.stringify(list));
  } catch {
    // storage unavailable: the list just won't persist
  }
  renderRecent(list);
}

function renderRecent(list = loadRecent()) {
  const ul = $("#recent");
  ul.replaceChildren(...list.map((c) => {
    const li = document.createElement("li");
    li.innerHTML = `<span class="num"></span><span class="sub"></span>`;
    li.querySelector(".num").textContent = c.caseNumber;
    li.querySelector(".sub").textContent = c.title;
    li.onclick = () => openCase(c.id);
    return li;
  }));
}

// --- case + evidence ---

let currentCaseId = null;

async function openCase(id) {
  try {
    const c = await api(`/api/cases/${encodeURIComponent(id)}`);
    currentCaseId = c.id;
    saveRecent(c);
    $("#case-panel").hidden = false;
    $("#case-title").textContent = `${c.caseNumber} · ${c.title}`;
    $("#case-meta").innerHTML = "";
    $("#case-meta").append(
      `Status ${c.status} · created ${new Date(c.createdAt).toLocaleString()} · id `,
      Object.assign(document.createElement("code"), { textContent: c.id }),
    );
    await Promise.all([loadEvidence(), loadHolds()]);
    $("#case-panel").scrollIntoView({ behavior: "smooth" });
  } catch (e) {
    toast(e.message, true);
  }
}

async function loadEvidence() {
  const rows = await api(`/api/cases/${currentCaseId}/evidence`);
  const tbody = $("#evidence-rows");
  if (rows.length === 0) {
    tbody.innerHTML = `<tr><td colspan="7" class="empty">No evidence yet</td></tr>`;
    return;
  }
  tbody.replaceChildren(...rows.map((e) => {
    const tr = document.createElement("tr");
    [
      e.fileName,
      e.contentType,
      e.sizeBytes.toLocaleString(),
      e.status,
      e.legalHold ? "Yes" : "No",
      e.retainUntil ? new Date(e.retainUntil).toLocaleDateString() : "—",
    ].forEach((v) => tr.appendChild(Object.assign(document.createElement("td"), { textContent: v })));
    // Evidence-level hold: POST places it, DELETE releases it.
    const toggle = button(e.legalHold ? "Release hold" : "Hold", async () => {
      await api(`/api/evidence/${e.id}/legal-hold`, { method: e.legalHold ? "DELETE" : "POST" });
      toast(`${e.legalHold ? "Released hold on" : "Held"} ${e.fileName}`);
      await loadEvidence();
    });
    tr.appendChild(document.createElement("td")).appendChild(toggle);
    return tr;
  }));
}

async function loadHolds() {
  const holds = await api(`/api/cases/${currentCaseId}/legal-holds`);
  const ul = $("#holds");
  if (holds.length === 0) {
    ul.innerHTML = `<li class="empty">No active legal hold</li>`;
    return;
  }
  ul.replaceChildren(...holds.map((h) => {
    const li = document.createElement("li");
    li.innerHTML = `<span class="num"></span><span class="sub"></span>`;
    li.querySelector(".num").textContent = h.reason;
    li.querySelector(".sub").textContent = `by ${h.placedBy} · ${new Date(h.placedAt).toLocaleString()}`;
    li.appendChild(button("Release", async () => {
      await api(`/api/cases/${currentCaseId}/legal-holds/${h.id}`, { method: "DELETE" });
      toast("Legal hold released");
      await loadHolds();
    }));
    return li;
  }));
}

/** A small button whose async errors show up as a toast. */
function button(label, onClick) {
  const b = Object.assign(document.createElement("button"), { type: "button", textContent: label, className: "small" });
  b.onclick = async (ev) => {
    ev.stopPropagation();
    try {
      await onClick();
    } catch (e) {
      toast(e.message, true);
    }
  };
  return b;
}

// --- wiring ---

$("#case-form").addEventListener("submit", async (ev) => {
  ev.preventDefault();
  try {
    const c = await api("/api/cases", { method: "POST", body: JSON.stringify(formJson(ev.target)) });
    ev.target.reset();
    toast(`Created case ${c.caseNumber}`);
    await openCase(c.id);
  } catch (e) {
    toast(e.message, true);
  }
});

$("#lookup-form").addEventListener("submit", (ev) => {
  ev.preventDefault();
  openCase(formJson(ev.target).id.trim());
});

$("#evidence-form").addEventListener("submit", async (ev) => {
  ev.preventDefault();
  const data = formJson(ev.target);
  const body = {
    fileName: data.fileName,
    contentType: data.contentType,
    sizeBytes: Number(data.sizeBytes),
    retainUntil: data.retainUntil ? new Date(data.retainUntil + "T00:00:00Z").toISOString() : null,
  };
  try {
    await api(`/api/cases/${currentCaseId}/evidence`, { method: "POST", body: JSON.stringify(body) });
    ev.target.fileName.value = "";
    toast(`Registered ${body.fileName}`);
    await loadEvidence();
  } catch (e) {
    toast(e.message, true);
  }
});

$("#hold-form").addEventListener("submit", async (ev) => {
  ev.preventDefault();
  try {
    const hold = await api(`/api/cases/${currentCaseId}/legal-holds`,
      { method: "POST", body: JSON.stringify(formJson(ev.target)) });
    ev.target.reset();
    toast(`Legal hold placed: ${hold.frozenEvidenceIds.length} evidence frozen`);
    await loadHolds();
  } catch (e) {
    toast(e.message, true);
  }
});

$("#retention-form").addEventListener("submit", async (ev) => {
  ev.preventDefault();
  try {
    const run = await api("/api/admin/retention/run", { method: "POST" });
    toast(`Retention (${run.strategy}) deleted ${run.deleted} evidence`);
    if (currentCaseId) {
      await loadEvidence();
    }
  } catch (e) {
    toast(e.message, true);
  }
});

async function checkHealth() {
  const el = $("#health");
  try {
    const h = await api("/actuator/health");
    el.textContent = `API ${h.status}`;
    el.className = `pill ${h.status === "UP" ? "up" : "down"}`;
  } catch {
    el.textContent = "API DOWN";
    el.className = "pill down";
  }
}

renderRecent();
checkHealth();
