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
  roomLimit: 10,
  showAllRooms: false,
  selectedMembers: new Set(),
  modalMode: "CREATE", // CREATE or INVITE
  modalRoomId: null
};

function $(id) {
  return document.getElementById(id);
}

function openModal(id, mode = "CREATE") {
  const modal = $(id);
  if (modal) {
    modal.classList.remove("hidden");
    if (id === "createRoomModal") {
      state.modalMode = mode;
      resetCreateRoomForm();
      renderMemberSelector();
    } else if (id === "roomMembersModal") {
      loadRoomMembers();
    }
  }
}

async function loadRoomMembers() {
  if (!state.current || state.current.type !== "ROOM") return;
  const box = $("roomMembersList");
  if (!box) return;
  
  box.innerHTML = '<div class="item skeleton" style="height: 40px;"></div>';
  
  try {
    const members = await apiGet(`/api/rooms/members?roomId=${state.current.roomId}`);
    box.innerHTML = members.map(m => `
      <div class="item">
        <div class="user-avatar-small" style="background: #f1f5f9;"></div>
        <div class="item-content">
          <div class="item-title">${escapeHtml(m.name || m.nodeId)}</div>
          <div class="item-meta">${m.role === 'OWNER' ? '创建者' : '成员'}</div>
        </div>
        ${m.online ? '<div class="status-dot status-online"></div>' : ''}
      </div>
    `).join('');
  } catch (e) {
    box.innerHTML = `<div style="padding: 20px; color: #ef4444; text-align: center;">加载失败: ${escapeHtml(e.message)}</div>`;
  }
}

function closeModal(id) {
  const modal = $(id);
  if (modal) modal.classList.add("hidden");
}

function resetCreateRoomForm() {
  const isInvite = state.modalMode === "INVITE";
  const title = $("createRoomModal").querySelector("h3");
  if (title) title.textContent = isInvite ? "邀请新成员" : "创建群组";
  
  const nameGroup = $("createRoomName").closest(".form-group");
  const typeGroup = document.querySelector('input[name="roomType"]').closest(".form-group");
  const descGroup = $("createRoomDesc").closest(".form-group");
  const confirmBtn = $("confirmCreateBtn");

  if (isInvite) {
    nameGroup.classList.add("hidden");
    typeGroup.classList.add("hidden");
    descGroup.classList.add("hidden");
    confirmBtn.textContent = "发送邀请";
  } else {
    nameGroup.classList.remove("hidden");
    typeGroup.classList.remove("hidden");
    descGroup.classList.remove("hidden");
    confirmBtn.textContent = "立即创建";
  }

  $("createRoomName").value = "";
  $("createRoomDesc").value = "";
  $("memberSearch").value = "";
  state.selectedMembers.clear();
  const roomTypeRadios = document.getElementsByName("roomType");
  if (roomTypeRadios.length > 0) roomTypeRadios[0].checked = true;
  updateCreateBtnState();
}

function updateCreateBtnState() {
  const name = $("createRoomName").value.trim();
  const btn = $("confirmCreateBtn");
  if (btn) {
    if (state.modalMode === "INVITE") {
      btn.disabled = state.selectedMembers.size === 0;
    } else {
      btn.disabled = !name;
    }
  }
}

function renderMemberSelector(filter = "") {
  const box = $("memberChecklist");
  if (!box) return;
  
  const filteredPeers = state.peers.filter(p => 
    (p.name || p.nodeId).toLowerCase().includes(filter.toLowerCase())
  );

  box.innerHTML = filteredPeers.map(p => `
    <label class="member-check-item">
      <input type="checkbox" value="${p.nodeId}" ${state.selectedMembers.has(p.nodeId) ? 'checked' : ''} onchange="toggleMemberSelection('${p.nodeId}')">
      <div class="user-avatar-small ${p.online ? 'online' : ''}" style="width: 24px; height: 24px;"></div>
      <div class="member-check-info">
        <span class="member-check-name">${escapeHtml(p.name || p.nodeId)}</span>
        <span class="member-check-id">${p.nodeId.substring(0, 8)}</span>
      </div>
    </label>
  `).join('');
}

function toggleMemberSelection(nodeId) {
  if (state.selectedMembers.has(nodeId)) {
    state.selectedMembers.delete(nodeId);
  } else {
    state.selectedMembers.add(nodeId);
  }
  updateCreateBtnState();
}

function setStatus(text, type = "info") {
  const statusLine = $("statusLine");
  if (!statusLine) return;
  statusLine.textContent = text || (state.me ? "在线" : "连接中...");
  statusLine.style.color = type === "error" ? "#ef4444" : "var(--accent-online)";
}

function showLoading(btn, loading = true) {
  if (!btn) return;
  if (loading) {
    btn.disabled = true;
    btn.dataset.originalText = btn.innerHTML;
    btn.innerHTML = `<span class="loading-spinner"></span>`;
  } else {
    btn.disabled = false;
    btn.innerHTML = btn.dataset.originalText || btn.innerHTML;
  }
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
  return `${hh}:${mm}`;
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
  if (!box) return;
  const peers = state.peers;
  if ($("peerCount")) $("peerCount").textContent = peers.length;
  
  // Basic diffing: check if content changed
  const newContent = peers.map(p => `
    <div class="item" onclick="openPrivate('${p.nodeId}', '${p.name || p.nodeId}')">
      <div class="user-avatar-small ${p.online ? 'online' : ''}"></div>
      <div class="item-content">
        <div class="item-title">${escapeHtml(p.name || p.nodeId)}</div>
        <div class="item-meta">${escapeHtml(p.nodeId.substring(0, 8))}</div>
      </div>
      <div class="status-dot ${p.online ? 'status-online' : 'status-offline'}"></div>
    </div>
  `).join('');

  if (box.dataset.lastContent !== newContent) {
    box.innerHTML = newContent;
    box.dataset.lastContent = newContent;
    // Update member selector if it's currently visible
    if (!$("createRoomModal").classList.contains("hidden")) {
      renderMemberSelector($("memberSearch").value);
    }
  }
}

function renderRooms() {
  const box = $("roomsList");
  if (!box) return;
  let rooms = state.rooms;
  const total = rooms.length;
  
  if (!state.showAllRooms && total > state.roomLimit) {
    rooms = rooms.slice(0, state.roomLimit);
    $("viewMoreRoomsBtn").classList.remove("hidden");
  } else {
    $("viewMoreRoomsBtn").classList.add("hidden");
  }

  const newContent = rooms.map(r => `
    <div class="item ${state.current && state.current.roomId === r.roomId ? 'active' : ''}" 
         onclick="openRoom('${r.roomId}', '${r.roomName || r.roomId}')">
      <div class="user-avatar-small" style="background: #e0f2fe; color: #0369a1; display: flex; align-items: center; justify-content: center; font-weight: bold; font-size: 12px;">
        ${(r.roomName || 'G').substring(0, 1).toUpperCase()}
      </div>
      <div class="item-content">
        <div class="item-title">${escapeHtml(r.roomName || r.roomId)}</div>
        <div class="item-meta">${total > 0 ? '群组' : ''}</div>
      </div>
    </div>
  `).join('');

  if (box.dataset.lastContent !== newContent) {
    box.innerHTML = newContent;
    box.dataset.lastContent = newContent;
  }
}

function renderConvs() {
  const box = $("convsList");
  if (!box) return;
  const newContent = state.convs.map(c => {
    const isActive = state.current && state.current.convId === c.convId;
    const title = c.title || (c.convType === "ROOM" ? c.roomId : c.peerNodeId) || c.convId;
    return `
      <div class="item ${isActive ? 'active' : ''}" onclick="handleConvClick('${c.convId}')">
        <div class="user-avatar-small" style="background: #f1f5f9;"></div>
        <div class="item-content">
          <div class="item-title">${escapeHtml(title)}</div>
          <div class="item-meta">${fmtTime(c.lastMsgTs)}</div>
        </div>
      </div>
    `;
  }).join('');

  if (box.dataset.lastContent !== newContent) {
    box.innerHTML = newContent;
    box.dataset.lastContent = newContent;
  }
}

function handleConvClick(convId) {
  const c = state.convs.find(cv => cv.convId === convId);
  if (c) openConversation(c);
}

function renderMessages(scrollToBottom) {
  const list = $("messagesList");
  if (!list) return;
  const fragment = document.createDocumentFragment();
  
  for (const id of state.orderedIds) {
    const m = state.messagesById.get(id);
    if (!m) continue;
    
    const div = document.createElement("div");
    const isOut = m.direction === "OUT";
    div.className = isOut ? "msg msgOut" : "msg msgIn";
    
    const status = isOut ? (m.status || "SENT") : "";
    const displayStatus = status === "DELIVERED" ? "已送达" : (status === "SENT" ? "已发送" : status);
    const from = isOut ? "我" : (m.fromName || m.fromNodeId || "未知");
    
    div.innerHTML = `
      <div class="msg-header">
        <span class="msg-sender">${escapeHtml(from)}</span>
        <span class="msg-time">${fmtTime(m.ts)}</span>
      </div>
      <div class="msg-bubble">${escapeHtml(m.content || "")}</div>
      ${isOut ? `<div class="msg-footer"><span class="msg-status">${displayStatus}</span></div>` : ""}
    `;
    fragment.appendChild(div);
  }
  
  list.innerHTML = "";
  list.appendChild(fragment);
  
  $("loadMoreBtn").classList.toggle("hidden", !state.current);
  $("membersBtn").classList.toggle("hidden", !(state.current && state.current.type === "ROOM"));
  
  if (scrollToBottom) {
    const pane = $("messagesPane");
    pane.scrollTo({
      top: pane.scrollHeight,
      behavior: "smooth"
    });
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
    console.error(e);
  }
}

function setCurrent(cur) {
  state.current = cur;
  clearMessages();
  state.pollSince = 0;
  $("chatTitle").textContent = cur ? cur.title : "未选择会话";
  if ($("chatSubtitle")) {
    $("chatSubtitle").textContent = cur ? (cur.type === "ROOM" ? "群聊模式" : "私聊模式") : "点选左侧联系人开始聊天";
  }
  renderMessages(false);
  
  // Close sidebar on mobile after selection
  if (window.innerWidth <= 768) {
    toggleSidebar(false);
  }
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
    console.error(e);
  }
}

window.openPrivate = async function(peerNodeId, title) {
  setCurrent({ type: "PRIVATE", peerNodeId, convId: "p:" + peerNodeId, title: title || peerNodeId });
  await loadLatest();
};

window.openRoom = async function(roomId, title) {
  setCurrent({ type: "ROOM", roomId, convId: "r:" + roomId, title: title || roomId });
  await loadLatest();
  try {
    setStatus("同步中...");
    await apiPost("/api/rooms/sync", { roomId });
    setTimeout(pollOnce, 800);
    setTimeout(() => setStatus(""), 1500);
  } catch (e) {
    setStatus(e.message, "error");
  }
};

async function openConversation(c) {
  if (c.convType === "ROOM") {
    await window.openRoom(c.roomId, c.title || c.roomId);
  } else {
    await window.openPrivate(c.peerNodeId, c.title || c.peerNodeId);
  }
}

async function sendCurrent() {
  if (!state.current) return;
  const input = $("composerInput");
  const btn = $("sendBtn");
  const content = input.value.trim();
  if (!content) return;
  
  input.value = "";
  showLoading(btn, true);
  
  try {
    if (state.current.type === "PRIVATE") {
      await apiPost("/api/send/private", { peerNodeId: state.current.peerNodeId, content });
    } else {
      await apiPost("/api/send/room", { roomId: state.current.roomId, content });
    }
    await pollOnce();
  } catch (e) {
    setStatus(e.message, "error");
    input.value = content;
  } finally {
    showLoading(btn, false);
    input.focus();
  }
}

async function saveMe() {
  const input = $("meNameInput");
  const name = input.value.trim();
  if (!name) return;
  
  try {
    const me = await apiPost("/api/me", { name });
    state.me = me;
    updateMeHeader(me);
    setStatus("昵称已更新");
  } catch (e) {
    setStatus(e.message, "error");
  }
}

function updateMeHeader(me) {
  $("meId").textContent = `${me.name || "未设置"} (${me.nodeId.substring(0, 8)})`;
}

async function inviteMembers() {
  if (!state.current || state.current.type !== "ROOM") return;
  const btn = $("confirmCreateBtn");
  showLoading(btn, true);
  try {
    const members = Array.from(state.selectedMembers);
    await apiPost("/api/rooms/invite", { 
      roomId: state.current.roomId, 
      members: members
    });
    closeModal("createRoomModal");
    setStatus("邀请已发送");
    loadRoomMembers();
  } catch (e) {
    setStatus(e.message, "error");
  } finally {
    showLoading(btn, false);
  }
}

async function createRoom() {
  if (state.modalMode === "INVITE") {
    return inviteMembers();
  }
  const nameInput = $("createRoomName");
  const descInput = $("createRoomDesc");
  const roomName = nameInput.value.trim();
  const roomDesc = descInput.value.trim();
  const roomType = document.querySelector('input[name="roomType"]:checked').value;
  
  if (!roomName) return;
  
  const btn = $("confirmCreateBtn");
  showLoading(btn, true);
  
  try {
    const members = Array.from(state.selectedMembers);
    const data = await apiPost("/api/rooms", { 
      roomName, 
      description: roomDesc,
      policy: roomType,
      initialMembers: members
    });
    closeModal("createRoomModal");
    await refreshAll();
    await window.openRoom(data.roomId, roomName);
  } catch (e) {
    setStatus(e.message, "error");
  } finally {
    showLoading(btn, false);
  }
}

async function joinRoom() {
  const idInput = $("joinRoomId");
  const invInput = $("joinInviter");
  const rid = idInput.value.trim();
  const inv = invInput.value.trim();
  
  if (!rid || !inv.includes(":")) return;
  
  const [ip, portStr] = inv.split(":", 2);
  const port = parseInt(portStr, 10);
  
  const btn = $("confirmJoinBtn");
  showLoading(btn, true);
  
  try {
    await apiPost("/api/rooms/join", { roomId: rid, inviterIp: ip, inviterPort: port });
    closeModal("joinRoomModal");
    await refreshAll();
    await window.openRoom(rid, rid);
  } catch (e) {
    setStatus(e.message, "error");
  } finally {
    showLoading(btn, false);
  }
}

function toggleSidebar(show) {
  const sidebar = $("sidebar-container");
  const overlay = $("sidebar-overlay");
  if (show) {
    sidebar.classList.add("open");
    overlay.classList.add("open");
  } else {
    sidebar.classList.remove("open");
    overlay.classList.remove("open");
  }
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, m => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":"&#039;"}[m]));
}

async function init() {
  try {
    const me = await apiGet("/api/me");
    state.me = me;
    updateMeHeader(me);
    $("meNameInput").value = me.name || "";
  } catch (e) {
    console.error(e);
  }

  $("menuToggle").addEventListener("click", () => toggleSidebar(true));
  $("sidebar-overlay").addEventListener("click", () => toggleSidebar(false));
  $("meNameInput").addEventListener("blur", saveMe);
  $("sendBtn").addEventListener("click", sendCurrent);
  $("composerInput").addEventListener("keydown", e => {
    if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); sendCurrent(); }
  });
  $("loadMoreBtn").addEventListener("click", loadMore);
  $("membersBtn").addEventListener("click", () => openModal("roomMembersModal"));
  $("showCreateRoomBtn").addEventListener("click", () => openModal("createRoomModal"));
  $("createRoomName").addEventListener("input", updateCreateBtnState);
  $("memberSearch").addEventListener("input", e => renderMemberSelector(e.target.value));
  $("confirmCreateBtn").addEventListener("click", createRoom);
  $("cancelCreateBtn").addEventListener("click", () => closeModal("createRoomModal"));
  $("closeModalBtn").addEventListener("click", () => closeModal("createRoomModal"));
  
  // Join modal events
  $("confirmJoinBtn").addEventListener("click", joinRoom);
  $("cancelJoinBtn").addEventListener("click", () => closeModal("joinRoomModal"));
  $("closeJoinModalBtn").addEventListener("click", () => closeModal("joinRoomModal"));

  // Members modal events
  $("closeMembersModalBtn").addEventListener("click", () => closeModal("roomMembersModal"));
  $("closeMembersBtn").addEventListener("click", () => closeModal("roomMembersModal"));
  $("addMemberBtn").addEventListener("click", () => {
    closeModal("roomMembersModal");
    openModal("createRoomModal", "INVITE");
  });

  $("viewMoreRoomsBtn").addEventListener("click", () => {
    state.showAllRooms = true;
    renderRooms();
  });

  await refreshAll();
  state.pollTimer = setInterval(pollOnce, 1000);
  setInterval(refreshAll, 5000);
}

init();
