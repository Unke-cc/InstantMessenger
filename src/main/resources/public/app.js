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
  if (res.status === 401) {
    window.location.href = "/login.html";
    return;
  }
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
  if (res.status === 401) {
    window.location.href = "/login.html";
    return;
  }
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
    
    let contentHtml = escapeHtml(m.content || "");
    // Check for file message pattern: [FILE:id]name (size)
    const fileMatch = m.content && m.content.match(/^\[FILE:([^\]]+)\](.*)$/);
    if (fileMatch) {
      const fileId = fileMatch[1];
      const fileNameAndSize = fileMatch[2];
      contentHtml = `
        <div class="file-msg-card">
          <div class="file-msg-icon">
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M13 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z"></path><polyline points="13 2 13 9 20 9"></polyline></svg>
          </div>
          <div class="file-msg-info">
            <div class="file-msg-name">${escapeHtml(fileNameAndSize)}</div>
            <button class="btn-text" style="padding: 0; margin-top: 4px;" onclick="downloadFile('${fileId}', '${escapeHtml(fileNameAndSize.split(' (')[0])}')">立即下载</button>
          </div>
        </div>
      `;
    }
    
    div.innerHTML = `
      <div class="msg-header">
        <span class="msg-sender">${escapeHtml(from)}</span>
        <span class="msg-time">${fmtTime(m.ts)}</span>
      </div>
      <div class="msg-bubble">${contentHtml}</div>
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

async function logout() {
  try {
    await apiPost("/api/auth/logout");
    window.location.href = "/login.html";
  } catch (e) {
    console.error(e);
  }
}

async function init() {
  try {
    // First check auth status
    const authStatus = await apiGet("/api/auth/status");
    if (!authStatus.loggedIn) {
      window.location.href = "/login.html";
      return;
    }

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

  if ($("logoutBtn")) {
    $("logoutBtn").addEventListener("click", logout);
  }

  initFileTransfer();

  await refreshAll();
  state.pollTimer = setInterval(pollOnce, 1000);
  setInterval(refreshAll, 5000);
}

// --- File Transfer Functions ---

function initFileTransfer() {
  const area = $("fileUploadArea");
  const input = $("fileInput");
  const showBtn = $("showFilesBtn");

  if (area) {
    area.addEventListener("click", () => input.click());
    area.addEventListener("dragover", e => {
      e.preventDefault();
      area.classList.add("dragover");
    });
    area.addEventListener("dragleave", () => area.classList.remove("dragover"));
    area.addEventListener("drop", e => {
      e.preventDefault();
      area.classList.remove("dragover");
      if (e.dataTransfer.files.length > 0) {
        uploadFiles(e.dataTransfer.files);
      }
    });
  }

  if (input) {
    input.addEventListener("change", () => {
      if (input.files.length > 0) {
        uploadFiles(input.files);
        input.value = ""; // Clear for next selection
      }
    });
  }

  if (showBtn) {
    showBtn.addEventListener("click", () => {
      openModal("fileListModal");
      loadFilesList();
    });
  }

  $("closeFileModalBtn")?.addEventListener("click", () => closeModal("fileListModal"));
  $("closeFilesBtn")?.addEventListener("click", () => closeModal("fileListModal"));
  $("refreshFilesBtn")?.addEventListener("click", loadFilesList);
}

async function uploadFiles(files) {
  for (const file of files) {
    uploadFile(file).catch(e => {
      console.error("Upload failed for", file.name, e);
      setStatus(`文件 ${file.name} 上传失败: ${e.message}`, "error");
    });
  }
}

async function uploadFile(file) {
  const fileId_temp = "up-" + Math.random().toString(36).substring(2, 9);
  addActiveUploadUI(fileId_temp, file.name);

  try {
    // 1. Init upload
    const initData = await apiPost("/api/files/upload/init", {
      fileName: file.name,
      fileSize: file.size,
      contentType: file.type || "application/octet-stream",
      fileHash: null // Skip hash for large files on frontend
    });

    const { fileId, chunkSize, missingChunks } = initData;
    // Replace temp ID with real ID in UI
    updateActiveUploadId(fileId_temp, fileId);

    // 2. Upload chunks
    let uploadedCount = 0;
    const totalChunks = Math.ceil(file.size / chunkSize);

    // We can upload a few chunks in parallel for better performance
    const concurrency = 3;
    const chunksToUpload = [...missingChunks];
    
    const worker = async () => {
      while (chunksToUpload.length > 0) {
        const index = chunksToUpload.shift();
        const start = index * chunkSize;
        const end = Math.min(start + chunkSize, file.size);
        const chunk = file.slice(start, end);
        
        await fetch(`/api/files/upload/chunk?fileId=${fileId}&chunkIndex=${index}`, {
          method: "POST",
          body: chunk,
          headers: {
            "Content-Type": "application/octet-stream"
          }
        }).then(res => {
          if (res.status === 401) window.location.href = "/login.html";
          return res.json();
        }).then(json => {
          if (!json.ok) throw new Error(json.error || "Chunk upload failed");
        });

        uploadedCount++;
        const percent = Math.floor((uploadedCount / totalChunks) * 100);
        updateUploadProgress(fileId, percent);
      }
    };

    const workers = Array(Math.min(concurrency, chunksToUpload.length)).fill(0).map(() => worker());
    await Promise.all(workers);

    // 3. Complete upload
    await apiPost("/api/files/upload/complete", { fileId });
    
    finishActiveUploadUI(fileId, true);
    setStatus(`文件 ${file.name} 上传成功`);

    // --- NEW: Auto-send message to current conversation ---
    if (state.current) {
      const downloadUrl = `/api/files/download/${fileId}`;
      const fileMsg = `[FILE:${fileId}]${file.name} (${formatSize(file.size)})`;
      
      try {
        if (state.current.type === "PRIVATE") {
          await apiPost("/api/send/private", { peerNodeId: state.current.peerNodeId, content: fileMsg });
        } else {
          await apiPost("/api/send/room", { roomId: state.current.roomId, content: fileMsg });
        }
        pollOnce();
      } catch (e) {
        console.error("Failed to auto-send file message", e);
      }
    }
  } catch (e) {
    finishActiveUploadUI(fileId_temp, false, e.message);
    throw e;
  }
}

function addActiveUploadUI(tempId, fileName) {
  const box = $("activeUploads");
  if (!box) return;
  const div = document.createElement("div");
  div.id = `upload-${tempId}`;
  div.className = "item upload-item";
  div.innerHTML = `
    <div class="item-content">
      <div class="item-title" title="${escapeHtml(fileName)}">${escapeHtml(fileName)}</div>
      <div class="progress-container">
        <div class="progress-bar" style="width: 0%"></div>
      </div>
    </div>
    <div class="item-meta">0%</div>
  `;
  box.prepend(div);
}

function updateActiveUploadId(oldId, newId) {
  const el = $(`upload-${oldId}`);
  if (el) el.id = `upload-${newId}`;
}

function updateUploadProgress(fileId, percent) {
  const el = $(`upload-${fileId}`);
  if (el) {
    const bar = el.querySelector(".progress-bar");
    const text = el.querySelector(".item-meta");
    if (bar) bar.style.width = `${percent}%`;
    if (text) text.textContent = `${percent}%`;
  }
}

function finishActiveUploadUI(fileId, success, errorMsg) {
  const el = $(`upload-${fileId}`);
  if (el) {
    if (success) {
      el.classList.add("upload-success");
      setTimeout(() => el.remove(), 3000);
    } else {
      el.classList.add("upload-failed");
      el.querySelector(".item-meta").textContent = "失败";
      el.title = errorMsg || "未知错误";
    }
  }
}

async function loadFilesList() {
  const box = $("fileListContainer");
  if (!box) return;
  box.innerHTML = '<div class="item skeleton" style="height: 40px;"></div>';

  try {
    const files = await apiGet("/api/files");
    renderFilesList(files);
  } catch (e) {
    box.innerHTML = `<div style="padding: 20px; color: #ef4444; text-align: center;">加载失败: ${escapeHtml(e.message)}</div>`;
  }
}

function renderFilesList(files) {
  const box = $("fileListContainer");
  if (!box) return;

  if (files.length === 0) {
    box.innerHTML = '<div style="padding: 40px; text-align: center; color: #94a3b8;">暂无文件</div>';
    return;
  }

  box.innerHTML = files.map(f => {
    const size = formatSize(f.fileSize);
    const time = new Date(f.createdAt).toLocaleString();
    return `
      <div class="item">
        <div class="item-content">
          <div class="item-title">${escapeHtml(f.fileName)}</div>
          <div class="item-meta">${size} · ${time}</div>
        </div>
        <button class="btn-icon" onclick="downloadFile('${f.fileId}', '${escapeHtml(f.fileName)}')" title="下载">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path><polyline points="7 10 12 15 17 10"></polyline><line x1="12" y1="15" x2="12" y2="3"></line></svg>
        </button>
      </div>
    `;
  }).join("");
}

window.downloadFile = function(fileId, fileName) {
  const url = `/api/files/download/${fileId}`;
  const a = document.createElement("a");
  a.href = url;
  a.download = fileName;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
};

function formatSize(bytes) {
  if (bytes === 0) return "0 B";
  const k = 1024;
  const sizes = ["B", "KB", "MB", "GB", "TB"];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + " " + sizes[i];
}

init();
