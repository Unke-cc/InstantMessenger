const state = {
  me: null,
  peers: [],
  rooms: [],
  convs: [],
  current: null,
  messagesById: new Map(),
  orderedIds: [],
  oldestTs: null,
  pollSince: 0,
  pollTimer: null,
};

function $(id) {
  return document.getElementById(id);
}

function setStatus(text) {
  $("statusLine").textContent = text || "";
}

async function apiGet(path) {
  const res = await fetch(path, { headers: { "Accept": "application/json" } });
  const json = await res.json();
  if (!json.ok) throw new Error(json.error || "API error");
  return json.data;
}

async function apiPost(path, body) {
  const res = await fetch(path, {
    method: "POST",
    headers: { "Content-Type": "application/json", "Accept": "application/json" },
    body: JSON.stringify(body || {}),
  });
  const json = await res.json();
  if (!json.ok) throw new Error(json.error || "API error");
  return json.data;
}

function fmtTime(ts) {
  if (!ts) return "";
  const d = new Date(ts);
  const hh = String(d.getHours()).padStart(2, "0");
  const mm = String(d.getMinutes()).padStart(2, "0");
  const ss = String(d.getSeconds()).padStart(2, "0");
  return `${hh}:${mm}:${ss}`;
}

function clearMessages() {
  state.messagesById.clear();
  state.orderedIds = [];
  state.oldestTs = null;
}

function upsertMessages(list) {
  let changed = false;
  for (const m of list) {
    if (!m || !m.msgId) continue;
    const existed = state.messagesById.has(m.msgId);
    state.messagesById.set(m.msgId, m);
    if (!existed) {
      state.orderedIds.push(m.msgId);
      changed = true;
    } else {
      changed = true;
    }
    if (m.ts && (state.oldestTs == null || m.ts < state.oldestTs)) state.oldestTs = m.ts;
  }
  if (changed) {
    state.orderedIds.sort((a, b) => {
      const ma = state.messagesById.get(a);
      const mb = state.messagesById.get(b);
      const ta = ma && ma.ts ? ma.ts : 0;
      const tb = mb && mb.ts ? mb.ts : 0;
      if (ta !== tb) return ta - tb;
      return a.localeCompare(b);
    });
  }
}

function renderPeers() {
  const box = $("peersList");
  box.innerHTML = "";
  for (const p of state.peers) {
    const div = document.createElement("div");
    div.className = "item";
    const badgeClass = p.online ? "badge badgeOnline" : "badge badgeOffline";
    div.innerHTML = `<div>${escapeHtml(p.name || p.nodeId)}<span class="${badgeClass}">${p.online ? "online" : "offline"}</span></div>` +
      `<div class="sub">${escapeHtml(p.nodeId)} ${escapeHtml(p.ip || "")}:${p.p2pPort || ""}</div>`;
    div.addEventListener("click", () => openPrivate(p.nodeId, p.name || p.nodeId));
    box.appendChild(div);
  }
}

function renderRooms() {
  const box = $("roomsList");
  box.innerHTML = "";
  for (const r of state.rooms) {
    const div = document.createElement("div");
    div.className = "item";
    div.innerHTML = `<div>${escapeHtml(r.roomName || r.roomId)}</div>` +
      `<div class="sub">${escapeHtml(r.roomId)} / ${escapeHtml(r.policy || "")}</div>`;
    div.addEventListener("click", () => openRoom(r.roomId, r.roomName || r.roomId));
    box.appendChild(div);
  }
}

function renderConvs() {
  const box = $("convsList");
  box.innerHTML = "";
  for (const c of state.convs) {
    const div = document.createElement("div");
    div.className = "item";
    const title = c.title || (c.convType === "ROOM" ? c.roomId : c.peerNodeId) || c.convId;
    const sub = c.convType === "ROOM" ? `ROOM ${c.roomId}` : `PRIVATE ${c.peerNodeId}`;
    div.innerHTML = `<div>${escapeHtml(title)}</div>` +
      `<div class="sub">${escapeHtml(sub)} / ${fmtTime(c.lastMsgTs)}</div>`;
    div.addEventListener("click", () => openConversation(c));
    box.appendChild(div);
  }
}

function renderMessages(scrollToBottom) {
  const list = $("messagesList");
  list.innerHTML = "";
  for (const id of state.orderedIds) {
    const m = state.messagesById.get(id);
    if (!m) continue;
    const div = document.createElement("div");
    const dir = m.direction === "OUT" ? "msg msgOut" : "msg msgIn";
    div.className = dir;
    const status = m.direction === "OUT" ? (m.status || "") : "";
    const from = m.direction === "OUT" ? "我" : (m.fromName || m.fromNodeId || "");
    div.innerHTML = `<div class="msgMeta"><span>${escapeHtml(from)}</span><span>${fmtTime(m.ts)}</span><span class="msgStatus">${escapeHtml(status)}</span></div>` +
      `<div>${escapeHtml(m.content || "")}</div>`;
    list.appendChild(div);
  }
  $("loadMoreBtn").classList.toggle("hidden", !state.current);
  $("membersBtn").classList.toggle("hidden", !(state.current && state.current.type === "ROOM"));
  if (scrollToBottom) {
    const pane = $("messagesPane");
    pane.scrollTop = pane.scrollHeight;
  }
}

async function refreshAll() {
  try {
    const [peers, rooms, convs] = await Promise.all([
      apiGet("/api/peers"),
      apiGet("/api/rooms"),
      apiGet("/api/conversations"),
    ]);
    state.peers = peers;
    state.rooms = rooms;
    state.convs = convs;
    renderPeers();
    renderRooms();
    renderConvs();
  } catch (e) {
    setStatus(e.message);
  }
}

function setCurrent(cur) {
  state.current = cur;
  clearMessages();
  state.pollSince = 0;
  $("chatTitle").textContent = cur ? cur.title : "未选择会话";
  renderMessages(false);
}

async function loadLatest() {
  if (!state.current) return;
  const beforeTs = Date.now() + 1;
  const limit = 50;
  const url = buildMessagesUrl(beforeTs, limit);
  const msgs = await apiGet(url);
  upsertMessages(msgs);
  let max = 0;
  for (const m of msgs) max = Math.max(max, m.updatedAt || m.ts || 0);
  state.pollSince = max || Date.now();
  renderMessages(true);
}

async function loadMore() {
  if (!state.current) return;
  const beforeTs = state.oldestTs != null ? state.oldestTs : (Date.now() + 1);
  const limit = 50;
  const url = buildMessagesUrl(beforeTs, limit);
  const msgs = await apiGet(url);
  upsertMessages(msgs);
  renderMessages(false);
}

function buildMessagesUrl(beforeTs, limit) {
  const cur = state.current;
  const qp = new URLSearchParams();
  qp.set("beforeTs", String(beforeTs));
  qp.set("limit", String(limit));
  if (cur.type === "ROOM") qp.set("roomId", cur.roomId);
  if (cur.type === "PRIVATE") qp.set("peerNodeId", cur.peerNodeId);
  return "/api/messages?" + qp.toString();
}

function buildPollUrl() {
  const cur = state.current;
  const qp = new URLSearchParams();
  qp.set("sinceTs", String(state.pollSince || 0));
  qp.set("limit", "200");
  if (cur.type === "ROOM") qp.set("roomId", cur.roomId);
  if (cur.type === "PRIVATE") qp.set("peerNodeId", cur.peerNodeId);
  return "/api/poll?" + qp.toString();
}

async function pollOnce() {
  if (!state.current) return;
  try {
    const data = await apiGet(buildPollUrl());
    if (!data || !data.messages) return;
    upsertMessages(data.messages);
    state.pollSince = Math.max(state.pollSince, data.maxTs || 0);
    if (data.messages.length > 0) renderMessages(true);
  } catch (e) {
    setStatus(e.message);
  }
}

async function openPrivate(peerNodeId, title) {
  setCurrent({ type: "PRIVATE", peerNodeId, title: title || peerNodeId });
  await loadLatest();
}

async function openRoom(roomId, title) {
  setCurrent({ type: "ROOM", roomId, title: title || roomId });
  await loadLatest();
}

async function openConversation(c) {
  if (c.convType === "ROOM") {
    await openRoom(c.roomId, c.title || c.roomId);
  } else {
    await openPrivate(c.peerNodeId, c.title || c.peerNodeId);
  }
}

async function sendCurrent() {
  if (!state.current) return;
  const text = $("composerInput").value;
  const content = text != null ? text.trim() : "";
  if (!content) return;
  $("composerInput").value = "";
  try {
    if (state.current.type === "PRIVATE") {
      await apiPost("/api/send/private", { peerNodeId: state.current.peerNodeId, content });
    } else {
      await apiPost("/api/send/room", { roomId: state.current.roomId, content });
    }
    await refreshAll();
    await pollOnce();
  } catch (e) {
    setStatus(e.message);
  }
}

async function saveMe() {
  const name = $("meNameInput").value;
  const v = name != null ? name.trim() : "";
  if (!v) return;
  try {
    const me = await apiPost("/api/me", { name: v });
    state.me = me;
    $("meId").textContent = `${me.name} / ${me.nodeId} / p2p:${me.p2pPort} web:${me.webPort}`;
    setStatus("已保存");
    await refreshAll();
  } catch (e) {
    setStatus(e.message);
  }
}

async function createRoom() {
  const roomName = $("createRoomName").value;
  const v = roomName != null ? roomName.trim() : "";
  if (!v) return;
  try {
    const data = await apiPost("/api/rooms", { roomName: v });
    $("createRoomName").value = "";
    await refreshAll();
    await openRoom(data.roomId, v);
  } catch (e) {
    setStatus(e.message);
  }
}

async function joinRoom() {
  const roomId = $("joinRoomId").value;
  const inviter = $("joinInviter").value;
  const rid = roomId != null ? roomId.trim() : "";
  const inv = inviter != null ? inviter.trim() : "";
  if (!rid || !inv || !inv.includes(":")) return;
  const [ip, portStr] = inv.split(":", 2);
  const port = parseInt(portStr, 10);
  if (!ip || !port) return;
  try {
    await apiPost("/api/rooms/join", { roomId: rid, inviterIp: ip, inviterPort: port });
    $("joinRoomId").value = "";
    $("joinInviter").value = "";
    await refreshAll();
    const room = state.rooms.find(r => r.roomId === rid);
    await openRoom(rid, room ? (room.roomName || rid) : rid);
  } catch (e) {
    setStatus(e.message);
  }
}

async function showMembers() {
  if (!state.current || state.current.type !== "ROOM") return;
  try {
    const members = await apiGet("/api/rooms/members?roomId=" + encodeURIComponent(state.current.roomId));
    const body = document.createElement("div");
    for (const m of members) {
      const div = document.createElement("div");
      div.className = "item";
      div.innerHTML = `<div>${escapeHtml(m.name || m.nodeId)}</div>` +
        `<div class="sub">${escapeHtml(m.nodeId)} ${escapeHtml(m.ip || "")}:${m.p2pPort || ""}</div>`;
      body.appendChild(div);
    }
    openModal("Room Members", body);
  } catch (e) {
    setStatus(e.message);
  }
}

function openModal(title, bodyEl) {
  $("modalTitle").textContent = title;
  const body = $("modalBody");
  body.innerHTML = "";
  body.appendChild(bodyEl);
  $("modal").classList.remove("hidden");
}

function closeModal() {
  $("modal").classList.add("hidden");
}

function escapeHtml(s) {
  return String(s)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

async function init() {
  try {
    const me = await apiGet("/api/me");
    state.me = me;
    $("meId").textContent = `${me.name} / ${me.nodeId} / p2p:${me.p2pPort} web:${me.webPort}`;
    $("meNameInput").value = me.name || "";
  } catch (e) {
    setStatus(e.message);
  }

  $("meSaveBtn").addEventListener("click", saveMe);
  $("sendBtn").addEventListener("click", sendCurrent);
  $("composerInput").addEventListener("keydown", (ev) => {
    if (ev.key === "Enter") sendCurrent();
  });
  $("loadMoreBtn").addEventListener("click", loadMore);
  $("createRoomBtn").addEventListener("click", createRoom);
  $("joinRoomBtn").addEventListener("click", joinRoom);
  $("membersBtn").addEventListener("click", showMembers);
  $("modalCloseBtn").addEventListener("click", closeModal);
  $("modal").addEventListener("click", (ev) => {
    if (ev.target && ev.target.id === "modal") closeModal();
  });

  await refreshAll();
  state.pollTimer = setInterval(pollOnce, 800);
  setInterval(refreshAll, 3000);
}

init();

