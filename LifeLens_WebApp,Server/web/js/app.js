const query = new URLSearchParams(location.search);
const key = query.get('key') || localStorage.getItem('kimchi-key') || '';
const content = document.querySelector('#content');
const $ = (selector) => document.querySelector(selector);
let page = query.get('page') || 'home';
const pageHistory = [];
let isNavigating = false;
const matterVisualState = new Map();
const gestureHueValues = [0, 21, 42, 85, 170, 213];
let gestureHueIndex = 0;
let gestureSpeechSequence = 0;
let lastTwoFingerTapAt = 0;
let twoFingerTapStartedAt = 0;
let isTwoFingerTap = false;
window.scrollTo(0, 0);
const genUuid = () => (crypto.randomUUID ? crypto.randomUUID() : 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
  const r = Math.random() * 16 | 0;
  return (c === 'x' ? r : (r & 0x3 | 0x8)).toString(16);
}));
let deviceId = query.get('device_id') || localStorage.getItem('kimchi-device-id') || genUuid();
localStorage.setItem('kimchi-device-id', deviceId);
const sharedLifeLogId = (() => {
  try { return window.NativeBridge?.getSharedLifeLogId?.() || 'lifelens-shared'; }
  catch (_) { return 'lifelens-shared'; }
})();

const defaultIotServer = 'http://192.168.3.80:5580';
const normalizeIotServer = (value) => {
  const address = String(value || '').trim();
  if (!address) return '';
  return (address.startsWith('http://') || address.startsWith('https://')) ? address.replace(/\/$/, '') : `http://${address.replace(/\/$/, '')}`;
};
const displayServerAddress = (value) => String(value || '').trim().replace(/^(?:https?:\/\/)+/i, '').replace(/\/$/, '');
let iotServer = normalizeIotServer(localStorage.getItem('kimchi-iot-server') || defaultIotServer);
localStorage.setItem('kimchi-iot-server', iotServer);

document.addEventListener('touchstart', (event) => {
  isTwoFingerTap = event.touches.length === 2;
  if (isTwoFingerTap) twoFingerTapStartedAt = Date.now();
}, { passive: true });
document.addEventListener('touchend', (event) => {
  if (!isTwoFingerTap || event.touches.length) return;
  isTwoFingerTap = false;
  const now = Date.now();
  if (now - twoFingerTapStartedAt > 350) return;
  if (now - lastTwoFingerTapAt < 480) {
    lastTwoFingerTapAt = 0;
    if (window.NativeBridge?.refreshWebApp) window.NativeBridge.refreshWebApp();
    else window.location.reload();
    return;
  }
  lastTwoFingerTapAt = now;
}, { passive: true });

const matterWsUrl = () => `${iotServer.replace(/^http/, 'ws')}/ws`;
function matterCommand(command, args = {}, timeout = 30000) {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(matterWsUrl());
    const messageId = `kimchi-${Date.now()}-${Math.random().toString(16).slice(2)}`;
    let ready = false;
    const timer = setTimeout(() => { socket.close(); reject(new Error('Matter 서버 응답 시간 초과')); }, timeout);
    const close = () => { clearTimeout(timer); if (socket.readyState === WebSocket.OPEN) socket.close(); };
    socket.onerror = () => { close(); reject(new Error('Matter 브릿지 서버 연결 실패')); };
    socket.onmessage = ({ data }) => {
      let message;
      try { message = JSON.parse(data); } catch (_) { return; }
      if (!ready) { ready = true; socket.send(JSON.stringify({ message_id: messageId, command, args })); return; }
      if (message.message_id !== messageId) return;
      close();
      if ('error_code' in message) reject(new Error(message.details || `Matter 오류 ${message.error_code}`));
      else resolve(message.result);
    };
  });
}

const isLightDevice = (device) => {
  const attrs = device?.attributes || {};
  return Object.prototype.hasOwnProperty.call(attrs, '1/8/0') || Object.prototype.hasOwnProperty.call(attrs, '1/768/0');
};
const gestureNotice = (message) => {
  const caption = $('#speech-caption');
  if (caption) {
    caption.innerHTML = `<article class="speech-log-entry system"><p>${esc(message)}</p></article>`;
    caption.dataset.signature = '';
  }
};
window.handleNativeIotGesture = async (action) => {
  const speechSequence = ++gestureSpeechSequence;
  window.NativeBridge?.cancelGestureFeedback?.();
  // The index+middle V is contextual: colour on the IoT screen, next slide elsewhere.
  if (action === 'presentation_next' && page === 'iot') action = 'cycle_light_color';
  const speakLatestGesture = (text) => {
    if (speechSequence === gestureSpeechSequence) window.NativeBridge?.speakGestureFeedback?.(text);
  };
  try {
    if (action === 'presentation_next' || action === 'presentation_previous') {
      const direction = action === 'presentation_next' ? 'next' : 'previous';
      await api('/v1/presentation/control', { method: 'POST', body: JSON.stringify({ action: direction }) });
      gestureNotice(`손 제스처 · 슬라이드 ${direction === 'next' ? '다음' : '이전'}으로 이동했습니다.`);
      speakLatestGesture(direction === 'next' ? '다음' : '이전');
      return;
    }
    const devices = await matterCommand('get_nodes', { only_available: false });
    const lights = devices.filter((device) => device.available && isLightDevice(device));
    if (!lights.length) throw new Error('제어 가능한 조명이 없습니다.');
    if (action === 'toggle_lights') {
      const lightStates = lights.map((device) => {
        const attrs = device.attributes || {};
        const previous = matterVisualState.get(String(device.node_id));
        return typeof previous?.isOn === 'boolean' ? previous.isOn : attrs['1/6/0'] === true;
      });
      const shouldTurnOn = !lightStates.every(Boolean);
      await Promise.all(lights.map((device) => {
        const previous = matterVisualState.get(String(device.node_id));
        matterVisualState.set(String(device.node_id), { ...previous, isOn: shouldTurnOn });
        return matterCommand('device_command', { node_id: Number(device.node_id), endpoint_id: 1, cluster_id: 6, command_name: shouldTurnOn ? 'On' : 'Off', payload: {}, response_type: null });
      }));
      gestureNotice('손 제스처 · 조명 전원을 전환했습니다.');
      speakLatestGesture(shouldTurnOn ? '불이 켜졌어요' : '불이 꺼졌어요');
    } else if (action === 'cycle_light_color') {
      const colorLights = lights.filter((device) => Object.prototype.hasOwnProperty.call(device.attributes || {}, '1/768/0'));
      if (!colorLights.length) throw new Error('색상 제어 가능한 조명이 없습니다.');
      const hue = gestureHueValues[gestureHueIndex++ % gestureHueValues.length];
      await Promise.all(colorLights.map((device) => matterCommand('device_command', { node_id: Number(device.node_id), endpoint_id: 1, cluster_id: 768, command_name: 'MoveToHueAndSaturation', payload: { hue, saturation: 254, transitionTime: 0, optionsMask: {}, optionsOverride: {} }, response_type: null })));
      gestureNotice('손 제스처 · 조명 색상을 변경했습니다.');
      speakLatestGesture('조명색을 변경했어요');
    }
    if (page === 'iot') render();
  } catch (error) {
    gestureNotice(`손 제스처 · ${error.message || 'IoT 제어에 실패했습니다.'}`);
  }
};

const esc = (value) => String(value ?? '').replace(/[&<>]/g, (char) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[char]));
const api = async (path, options = {}) => {
  const response = await fetch(path, { ...options, headers: { 'Content-Type': 'application/json', 'X-API-Key': key, ...options.headers } });
  if (!response.ok) throw new Error(await response.text());
  return response.json();
};
const heroCacheKey = (pageName) => `lifelens-hero-copy-${pageName}`;
const setHeroCopy = (pageName, copy, animated = false) => {
  const hero = document.querySelector(`[data-hero-page="${pageName}"]`);
  if (!hero || !copy?.title || !copy?.body) return;
  const title = hero.querySelector('[data-hero-title]');
  const body = hero.querySelector('[data-hero-copy]');
  if (!title || !body) return;
  const textNodes = [title, body];
  const update = () => {
    title.textContent = String(copy.title).replace(/\s*\n\s*/g, ' ').trim();
    body.textContent = String(copy.body).replace(/\s*\n\s*/g, ' ').trim();
  };
  if (!animated) return update();
  textNodes.forEach((node) => node.classList.add('hero-copy-out'));
  window.setTimeout(() => {
    update();
    textNodes.forEach((node) => { node.classList.remove('hero-copy-out'); node.classList.add('hero-copy-in'); });
    window.setTimeout(() => textNodes.forEach((node) => node.classList.remove('hero-copy-in')), 420);
  }, 180);
};
const applyCachedHeroCopy = (pageName) => {
  try {
    const previous = JSON.parse(localStorage.getItem(heroCacheKey(pageName)) || 'null');
    if (previous) setHeroCopy(pageName, previous);
  } catch (_) {}
};
const requestHeroCopy = async (pageName, animateOnCurrentPage = false) => {
  try {
    const copy = await api('/v1/page-copy', { method: 'POST', body: JSON.stringify({ page: pageName }) });
    localStorage.setItem(heroCacheKey(pageName), JSON.stringify(copy));
    if (animateOnCurrentPage && page === pageName) setHeroCopy(pageName, copy, true);
  } catch (_) {}
};
const refreshHeroCopiesAtLaunch = () => ['home', 'lifelog', 'iot']
  .forEach((pageName) => requestHeroCopy(pageName, pageName === 'home'));
const localDay = () => {
  const now = new Date();
  const offset = now.getTimezoneOffset() * 60000;
  return new Date(now.getTime() - offset).toISOString().slice(0, 10);
};
// A stale relay response must never make another day's records appear in this timeline.
// Use the phone's local calendar, matching the day chosen in the calendar UI.
const dayFromTimestamp = (value) => {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '';
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
};
const timelineGroups = (records = []) => {
  const groups = new Map();
  records.forEach((record) => {
    const date = new Date(record.spoken_at);
    const key = Number.isNaN(date.getTime()) ? record.spoken_at : `${date.getFullYear()}-${date.getMonth()}-${date.getDate()}-${date.getHours()}-${date.getMinutes()}`;
    const time = Number.isNaN(date.getTime()) ? '기록 시각 없음' : date.toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' });
    const group = groups.get(key) || { time, stamp: Number.isNaN(date.getTime()) ? 0 : date.getTime(), texts: [] };
    group.texts.push(record.text);
    groups.set(key, group);
  });
  return [...groups.values()];
};
const inlineMarkdown = (text) => esc(text)
  .replace(/`([^`]+)`/g, '<code>$1</code>')
  .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
  .replace(/\*([^*]+)\*/g, '<em>$1</em>');
const renderMarkdown = (markdown) => {
  const cleaned = String(markdown || '').replace(/\r/g, '').replace(/^\s*(?:#{1,6}\s*)?오늘의\s*리포트\s*[:：]?[^\n]*\n+/i, '').replace(/\n\s*(?:#{1,6}\s*)?(?:keywords|핵심\s*키워드|키워드)\s*[:：]?\s*\[[\s\S]*$/i, '').trim();
  const lines = cleaned.split('\n');
  let html = '';
  let listOpen = false;
  const closeList = () => { if (listOpen) { html += '</ul>'; listOpen = false; } };
  lines.forEach((line) => {
    if (/^###\s+/.test(line)) { closeList(); html += `<h4>${inlineMarkdown(line.replace(/^###\s+/, ''))}</h4>`; }
    else if (/^##\s+/.test(line)) { closeList(); html += `<h3>${inlineMarkdown(line.replace(/^##\s+/, ''))}</h3>`; }
    else if (/^#\s+/.test(line)) { closeList(); html += `<h2>${inlineMarkdown(line.replace(/^#\s+/, ''))}</h2>`; }
    else if (/^[-*]\s+/.test(line)) { if (!listOpen) { html += '<ul>'; listOpen = true; } html += `<li>${inlineMarkdown(line.replace(/^[-*]\s+/, ''))}</li>`; }
    else if (line.trim()) { closeList(); html += `<p>${inlineMarkdown(line)}</p>`; }
    else closeList();
  });
  closeList();
  return html || '<p>리포트 내용이 없습니다.</p>';
};
const nativeLifeLogRecords = (day) => {
  try { return JSON.parse(window.NativeBridge?.getLifeLogRecords?.(day) || '[]'); }
  catch (_) { return []; }
};
const nativeLifeLogPhotos = (day) => {
  try { return JSON.parse(window.NativeBridge?.getLifeLogPhotos?.(day) || '[]'); }
  catch (_) { return []; }
};
const syncLifeLogRecords = async (day, records) => {
  if (!records?.length) return;
  await api('/v1/lifelogs/sync', {
    method: 'POST',
    body: JSON.stringify({ device_id: sharedLifeLogId, date: day, records }),
  });
};
const renderSpeechTimeline = (target, vision) => {
  // Older installed Android builds expose only latestTranscript in the vision state. Read the
  // persisted life-log as well so finalized utterances stay visible instead of being replaced.
  const savedRecords = nativeLifeLogRecords(localDay()).map((record) => ({
    id: record.client_record_id,
    text: record.text,
    spokenAt: record.spoken_at,
  }));
  const liveRecords = Array.isArray(vision.transcripts) ? vision.transcripts : [];
  const recordsById = new Map();
  [...savedRecords, ...liveRecords].forEach((record) => {
    const text = String(record.text || '').trim();
    if (!text) return;
    const id = record.id || record.client_record_id || `${record.spokenAt || record.spoken_at}:${text}`;
    recordsById.set(id, { id, text, spokenAt: record.spokenAt || record.spoken_at });
  });
  const records = [...recordsById.values()]
    .sort((a, b) => new Date(a.spokenAt).getTime() - new Date(b.spokenAt).getTime())
    .slice(-50);
  const latest = String(vision.latestTranscript || '').trim();
  const lastFinal = String(records.at(-1)?.text || '').trim();
  const items = records.map((record) => ({
    id: record.id,
    text: record.text,
    spokenAt: record.spokenAt,
    partial: false,
  })).filter((record) => record.text);
  if (latest && latest !== lastFinal) {
    items.push({ id: 'partial', text: latest, spokenAt: Date.now(), partial: true });
  } else if (!items.length && latest) {
    items.push({ id: 'latest', text: latest, spokenAt: Date.now(), partial: false });
  }
  const signature = items.map((item) => `${item.id}:${item.text}`).join('|');
  if (target.dataset.signature === signature) return;
  target.dataset.signature = signature;
  target.innerHTML = items.length
    ? items.map((item) => `<article class="speech-log-entry${item.partial ? ' partial' : ''}"><p>${esc(item.text)}</p></article>`).join('')
    : '<p class="speech-log-empty">마이크를 켜면 대화가 시간순으로 표시됩니다.</p>';
  target.scrollTop = target.scrollHeight;
};
function refreshVisionState() {
  try {
    const bridge = window.NativeBridge;
    if (!bridge?.getVisionState) return;
    const vision = JSON.parse(bridge.getVisionState());
    const gesture = document.querySelector('#gesture-name');
    const confidence = document.querySelector('#gesture-confidence');
    const session = document.querySelector('#session-toggle');
    const sessionState = document.querySelector('#session-state');
    const transcript = document.querySelector('#speech-caption');
    if (gesture) gesture.textContent = vision.isGestureActive ? vision.gestureName : '연결 대기 중';
    if (confidence) confidence.textContent = vision.isGestureActive ? `인식 신뢰도 ${vision.gestureConfidence}%` : '글래스 스트림 연결 시 자동 시작';
    const streamReady = vision.isGestureActive;
    if (session) {
      session.disabled = !streamReady;
      session.textContent = !streamReady ? '연결 대기' : (vision.isSessionEnabled ? '세션 끄기' : '세션 켜기');
    }
    if (sessionState) {
      sessionState.textContent = !streamReady ? '대기 중 · 글래스 스트림 연결 필요' : (vision.isSessionEnabled ? (vision.isMicrophoneOn ? '켜져있음 · 카메라 · STT 기록 중' : '켜져있음 · 마이크 준비 중') : '꺼져있음 · 카메라 · STT 대기');
      sessionState.className = `control-state ${streamReady && vision.isSessionEnabled && vision.isMicrophoneOn ? 'on' : 'off'}`;
    }
    if (transcript) renderSpeechTimeline(transcript, vision);
  } catch (_) {}
}

function toggleSession() {
  window.NativeBridge?.toggleSession?.();
  window.setTimeout(refreshVisionState, 250);
}
function openLiveVision() { window.NativeBridge?.openLiveVision?.(); }

function setPage(next, rememberPage = true) {
  if (next === page || isNavigating) return;
  isNavigating = true;
  if (rememberPage) pageHistory.push(page);
  const params = new URLSearchParams({ page: next, key, device_id: deviceId });
  history.replaceState({}, '', `?${params.toString()}`);
  const rank = { home: 0, skills: 1, lifelog: 2, iot: 2, debug: 3 };
  const forward = (rank[next] ?? 0) >= (rank[page] ?? 0);
  content.classList.remove('page-enter-left', 'page-enter-right');
  content.classList.add(forward ? 'page-exit-left' : 'page-exit-right');
  window.setTimeout(() => {
    page = next;
    document.querySelectorAll('.bottom button').forEach((button) => button.classList.toggle('active', button.dataset.page === next));
    window.scrollTo(0, 0);
    render();
    content.classList.remove('page-exit-left', 'page-exit-right');
    content.classList.add(forward ? 'page-enter-right' : 'page-enter-left');
    window.setTimeout(() => { content.classList.remove('page-enter-left', 'page-enter-right'); isNavigating = false; }, 260);
  }, 180);
}
window.handleNativeBack = () => {
  const previous = pageHistory.pop();
  if (previous) setPage(previous, false);
  else if (page !== 'home') setPage('home', false);
  return true;
};

function home() {
  content.innerHTML = `
    <section class="hero home-hero" data-hero-page="home"><span class="hero-kicker">RAY-BAN META × 팀 김치찌개</span><h2 data-hero-title>See more.<br>Live fully.</h2><p data-hero-copy>삶을 비추는 렌즈이자 일상의 친구, LifeLens가 글래스의 시선과 대화를 기록해 더 나은 하루를 돕습니다.</p></section>
    <section class="card vision-status"><span class="section-label">STREAM STATUS</span><div class="vision-session"><b>LifeLens 세션</b><small class="control-state off" id="session-state">상태 확인 중</small></div><h2 id="gesture-name">연결 대기 중</h2><p class="muted" id="gesture-confidence">글래스 스트림 연결 시 자동 시작</p><div class="speech-caption" id="speech-caption" role="log" aria-live="polite" aria-label="실시간 STT 타임라인"><p class="speech-log-empty">마이크를 켜면 대화가 시간순으로 표시됩니다.</p></div></section>`;
}

function skills() {
  content.innerHTML = `
    <button class="card skill featured" id="skill-live"><span class="icon">◉</span><div><span class="badge">LIVE</span><h2>Live Vision</h2><p>글래스 카메라 스트림과 손 제스처를 확인합니다.</p></div><em>보기 →</em></button>
    <button class="card skill" id="skill-log"><span class="icon">◷</span><div><span class="badge soft">DAILY</span><h2>LifeLog</h2><p>대화를 타임라인으로 모아 하루를 요약합니다.</p></div><em>보기 →</em></button>
    <button class="card skill" id="skill-iot"><span class="icon">⌁</span><div><span class="badge soft">MATTER</span><h2>IoT 제어</h2><p>IoT 기기를 제어합니다.</p></div><em>보기 →</em></button>
    <button class="card skill"><span class="icon">✋</span><div><span class="badge soft">READY</span><h2>Hand Gesture</h2><p>가벼운 손짓으로 화면과 촬영을 제어합니다.</p></div></button>`;
  $('#skill-live').onclick = openLiveVision;
  $('#skill-log').onclick = () => setPage('lifelog');
  $('#skill-iot').onclick = () => setPage('iot');
}

function iot() {
  content.innerHTML = `<section class="hero mini-hero" data-hero-page="iot"><span class="hero-kicker">MATTER BRIDGE</span><h2 data-hero-title>IoT 제어</h2><p data-hero-copy>외부 Matter 브릿지 서버에 연결된 Wi-Fi 기기를 제어합니다.</p><div class="actions"><button class="primary" id="add-matter">기기 추가</button></div></section><section class="card"><div class="panel-heading"><div><span class="section-label">MY DEVICES</span><h2>연결된 기기</h2></div><span class="date-pill">브릿지</span></div><p class="muted">서버: ${esc(displayServerAddress(iotServer))}</p><div id="matter-devices" class="matter-devices">불러오는 중…</div></section>`;
  const draw = async () => {
    let devices = [];
    try { devices = await matterCommand('get_nodes', { only_available: false }); } catch (error) {
      return $('#matter-devices').innerHTML = `<p class="empty">${esc(error.message)}<br>Debug에서 서버 주소와 Matter 서버 실행 상태를 확인하세요.</p>`;
    }
    $('#matter-devices').innerHTML = devices.length ? devices.map((device) => {
      const attrs = device.attributes || {};
      const name = attrs['0/40/5'] || attrs['0/40/3'] || `Matter 기기 ${device.node_id}`;
      const visual = matterVisualState.get(String(device.node_id)) || {};
      const isOn = typeof visual.isOn === 'boolean' ? visual.isOn : attrs['1/6/0'] === true;
      const level = Number.isFinite(visual.level) ? visual.level : (Number.isFinite(attrs['1/8/0']) ? attrs['1/8/0'] : 254);
      const brightness = Math.round(level / 254 * 100);
      const hasLevel = Object.prototype.hasOwnProperty.call(attrs, '1/8/0');
      const hasColor = Object.prototype.hasOwnProperty.call(attrs, '1/768/0');
      const lightControls = (hasLevel || hasColor) ? `<div class="light-controls ${isOn ? '' : 'disabled'}">${hasLevel ? `<label class="brightness-control"><span>밝기</span><input type="range" min="1" max="254" value="${level}" data-action="brightness" data-id="${esc(device.node_id)}" ${isOn ? '' : 'disabled'}><output>${brightness}%</output></label>` : ''}${hasColor ? `<div class="color-control"><span>색상</span><div class="color-swatches"><button aria-label="빨강" class="swatch red" data-action="color" data-id="${esc(device.node_id)}" data-hue="0" ${isOn ? '' : 'disabled'}></button><button aria-label="주황" class="swatch orange" data-action="color" data-id="${esc(device.node_id)}" data-hue="21" ${isOn ? '' : 'disabled'}></button><button aria-label="노랑" class="swatch yellow" data-action="color" data-id="${esc(device.node_id)}" data-hue="42" ${isOn ? '' : 'disabled'}></button><button aria-label="초록" class="swatch green" data-action="color" data-id="${esc(device.node_id)}" data-hue="85" ${isOn ? '' : 'disabled'}></button><button aria-label="파랑" class="swatch blue" data-action="color" data-id="${esc(device.node_id)}" data-hue="170" ${isOn ? '' : 'disabled'}></button><button aria-label="보라" class="swatch purple" data-action="color" data-id="${esc(device.node_id)}" data-hue="213" ${isOn ? '' : 'disabled'}></button></div></div>` : ''}</div>` : '';
      return `<article class="matter-device"><div><b>${esc(name)}</b><small>${device.available ? (isOn ? '켜져있음' : '꺼져있음') : '오프라인'} · ID ${esc(device.node_id)}</small></div><div class="matter-actions"><button class="control-button ${isOn ? 'active' : ''}" data-action="toggle" data-id="${esc(device.node_id)}" data-state="${isOn}">${isOn ? '끄기' : '켜기'}</button><button class="icon-button" data-action="edit" data-id="${esc(device.node_id)}" data-name="${esc(name)}">수정</button></div>${lightControls}</article>`;
    }).join('') : '<p class="empty">등록된 Matter 기기가 없습니다.<br>기기 추가에서 QR 코드 또는 설정 코드를 입력하세요.</p>';
    document.querySelectorAll('.matter-device button').forEach((button) => button.onclick = async () => {
      const nodeId = button.dataset.id;
      try {
        if (button.dataset.action === 'toggle') {
          const previous = matterVisualState.get(String(nodeId));
          matterVisualState.set(String(nodeId), { ...previous, isOn: button.dataset.state !== 'true' });
          await matterCommand('device_command', { node_id: Number(nodeId), endpoint_id: 1, cluster_id: 6, command_name: button.dataset.state === 'true' ? 'Off' : 'On', payload: {}, response_type: null });
        }
        if (button.dataset.action === 'edit') {
          const name = prompt('기기 이름', button.dataset.name);
          if (name?.trim()) await matterCommand('write_attribute', { node_id: Number(nodeId), attribute_path: '0/40/5', value: name.trim() });
        }
        if (button.dataset.action === 'color') await matterCommand('device_command', { node_id: Number(nodeId), endpoint_id: 1, cluster_id: 768, command_name: 'MoveToHueAndSaturation', payload: { hue: Number(button.dataset.hue), saturation: 254, transitionTime: 0, optionsMask: {}, optionsOverride: {} }, response_type: null });
        await draw();
      } catch (error) { alert(error.message || 'Matter 요청에 실패했습니다.'); }
    });
    document.querySelectorAll('.brightness-control input').forEach((input) => {
      input.oninput = () => input.parentElement.querySelector('output').textContent = `${Math.round(Number(input.value) / 254 * 100)}%`;
      input.onchange = async () => {
        const nodeId = String(input.dataset.id);
        const previous = matterVisualState.get(nodeId);
        matterVisualState.set(nodeId, { ...previous, level: Number(input.value) });
        try { await matterCommand('device_command', { node_id: Number(nodeId), endpoint_id: 1, cluster_id: 8, command_name: 'MoveToLevelWithOnOff', payload: { level: Number(input.value), transitionTime: 0, optionsMask: {}, optionsOverride: {} }, response_type: null }); }
        catch (error) { if (previous) matterVisualState.set(nodeId, previous); else matterVisualState.delete(nodeId); await draw(); alert(error.message || '밝기 변경에 실패했습니다.'); }
      };
    });
  };
  $('#add-matter').onclick = async () => {
    const code = prompt('Matter QR 코드 또는 설정 코드 입력');
    if (!code?.trim()) return;
    try { await matterCommand('commission_with_code', { code: code.trim(), network_only: true }, 300000); await draw(); }
    catch (error) { alert(error.message || 'Matter 기기 추가에 실패했습니다.'); }
  };
  draw();
}

async function lifeLog() {
  let day = localDay();
  let calendarMonth = new Date(`${day}T12:00:00`);
  const dayLabel = (value) => new Date(`${value}T12:00:00`).toLocaleDateString('ko-KR', { month: 'long', day: 'numeric', weekday: 'short' });
  const toDay = (value) => `${value.getFullYear()}-${String(value.getMonth() + 1).padStart(2, '0')}-${String(value.getDate()).padStart(2, '0')}`;
  content.innerHTML = `<section class="hero mini-hero" data-hero-page="lifelog"><span class="hero-kicker">DAILY MEMORY</span><h2 data-hero-title>라이프로그</h2><p data-hero-copy id="lifelog-date">${dayLabel(day)} · 대화 기록을 다시 살펴보세요.</p><div class="actions"><button class="primary" id="summarize">일기 작성</button><button class="glass-button" id="open-calendar">▦ 날짜 선택</button></div></section><section class="card"><div class="panel-heading"><div><span class="section-label" id="lifelog-section-label">STT TIMELINE</span><h2 id="lifelog-panel-title">대화 원문</h2></div><div class="log-toggle compact" role="tablist"><button class="active" data-log-view="records">대화 원문</button><button data-log-view="report">리포트 보기</button></div></div><div id="records" class="timeline">불러오는 중…</div></section><section class="photo-viewer" id="lifelog-photo-viewer" hidden><button class="photo-viewer-close" id="photo-viewer-close" aria-label="닫기">×</button><img id="photo-viewer-image" alt="라이프로그 사진"><p id="photo-viewer-meta"></p></section><dialog class="calendar-dialog" id="lifelog-calendar"><section class="calendar-sheet"><div class="calendar-heading"><div><span class="section-label">LIFELOG CALENDAR</span><h2 id="calendar-month"></h2></div><button class="calendar-close" id="calendar-close" aria-label="닫기">×</button></div><div class="calendar-weekdays"><span>일</span><span>월</span><span>화</span><span>수</span><span>목</span><span>금</span><span>토</span></div><div class="calendar-days" id="calendar-days"></div><div class="calendar-actions"><button class="glass-action" id="calendar-prev">이전 달</button><button class="primary" id="calendar-today">오늘</button><button class="glass-action" id="calendar-next">다음 달</button></div></section></dialog>`;
  let currentLog = null;
  let logView = 'records';
  let isGenerating = false;
  let loadRequestId = 0;
  const originalPhotoUrls = new Map();
  const preloadOriginalPhotos = async (photos = []) => {
    const activeIds = new Set(photos.map((photo) => photo.client_photo_id));
    originalPhotoUrls.forEach((url, id) => {
      if (!activeIds.has(id)) { URL.revokeObjectURL(url); originalPhotoUrls.delete(id); }
    });
    await Promise.allSettled(photos.filter((photo) => photo.url).map(async (photo) => {
      if (originalPhotoUrls.has(photo.client_photo_id)) {
        photo.loaded_url = originalPhotoUrls.get(photo.client_photo_id);
        return;
      }
      const response = await fetch(photo.url, { cache: 'force-cache' });
      if (!response.ok) throw new Error(`사진 불러오기 실패: ${response.status}`);
      const url = URL.createObjectURL(await response.blob());
      originalPhotoUrls.set(photo.client_photo_id, url);
      photo.loaded_url = url;
    }));
    photos.forEach((photo) => { photo.loaded_url ||= originalPhotoUrls.get(photo.client_photo_id) || ''; });
  };
  const photoMeta = (photo) => {
    const time = new Date(photo.taken_at).toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' });
    const location = photoLocation(photo);
    return `${time} · ${location}${photo.vision_caption ? `\n${photo.vision_caption}` : ''}`;
  };
  const photoLocation = (photo) => {
    if (photo.location_name) return photo.location_name;
    return Number.isFinite(Number(photo.latitude)) && Number.isFinite(Number(photo.longitude))
      ? `${Number(photo.latitude).toFixed(5)}, ${Number(photo.longitude).toFixed(5)}`
      : '위치 정보 없음';
  };
  const photoSource = (photo) => photo.loaded_url || (photo.url ? esc(photo.url) : `data:image/jpeg;base64,${esc(photo.thumbnail || '')}`);
  const photoThumb = (photo, index, kind = '') => `<button class="photo-thumb ${kind}" data-photo-index="${index}"><img src="${photoSource(photo)}" alt="촬영 사진"><span>${esc(photoLocation(photo))}</span></button>`;
  const bindPhotoClicks = () => document.querySelectorAll('[data-photo-index]').forEach((button) => button.onclick = () => {
    const photo = currentLog?.photos?.[Number(button.dataset.photoIndex)];
    if (!photo) return;
    // Android uses a native photo screen. Browsers keep the web full-screen viewer.
    if (window.NativeBridge?.openPhotoViewer?.(photo.url || '', photo.uri || '')) return;
    const viewer = $('#lifelog-photo-viewer');
    const image = $('#photo-viewer-image');
    // The thumbnail is already decoded by WebView. Render it first so a failed original request
    // never leaves a blank full-screen viewer.
    const thumbnail = button.querySelector('img')?.currentSrc || photoSource(photo);
    const full = photo.loaded_url || photo.url || '';
    $('#photo-viewer-meta').textContent = photoMeta(photo);
    image.onerror = () => { image.onerror = null; image.src = thumbnail; };
    image.src = thumbnail;
    if (full && full !== thumbnail) {
      const fullImage = new Image();
      fullImage.onload = () => { if (!viewer.hidden) image.src = fullImage.src; };
      fullImage.src = full;
    }
    viewer.hidden = false;
    document.body.classList.add('photo-viewer-open');
  });
  const renderPanel = () => {
    const target = $('#records');
    const title = $('#lifelog-panel-title');
    $('#lifelog-section-label').textContent = logView === 'report' ? 'LIFELOG' : 'STT TIMELINE';
    document.querySelectorAll('[data-log-view]').forEach((button) => button.classList.toggle('active', button.dataset.logView === logView));
    if (isGenerating) {
      title.textContent = '리포트 생성 중';
      target.className = 'report-loading';
      target.innerHTML = '<span class="spinner"></span><p>대화·사진·위치 정보를 분석하고 있습니다…</p>';
      return;
    }
    if (logView === 'records') {
      title.textContent = '대화 원문';
      target.className = 'timeline';
      const groups = timelineGroups(currentLog?.records);
      const items = [...groups.map((group) => ({ ...group, type: 'text' })), ...(currentLog?.photos || []).map((photo, index) => ({ type: 'photo', photo, index, time: new Date(photo.taken_at).toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' }), stamp: new Date(photo.taken_at).getTime() }))].sort((a, b) => (a.stamp || 0) - (b.stamp || 0));
      target.innerHTML = items.length ? items.map((item) => item.type === 'photo' ? `<article class="record photo-timeline-record"><time>${esc(item.time)}</time>${photoThumb(item.photo, item.index, 'timeline-photo')}</article>` : `<article class="record"><p>${item.texts.map((text) => esc(text)).join('<br>')}</p></article>`).join('') : '<p class="empty">아직 기록된 대화나 사진이 없습니다.</p>';
      bindPhotoClicks();
      return;
    }
    title.textContent = '오늘의 리포트';
    target.className = 'report-view';
    const summary = currentLog?.summary;
    target.innerHTML = summary ? `<article class="markdown-report">${renderMarkdown(summary.summary)}</article>${summary.keywords?.length ? `<div class="report-group"><b>핵심 키워드</b><div class="report-tags">${summary.keywords.map((keyword) => `<span>${esc(keyword)}</span>`).join('')}</div></div>` : ''}${summary.todos?.length ? `<div class="report-group"><b>할 일</b><ul>${summary.todos.map((todo) => `<li>${esc(todo)}</li>`).join('')}</ul></div>` : ''}${currentLog?.photos?.length ? `<div class="report-photo-group"><b>오늘의 사진</b><div class="photo-carousel">${currentLog.photos.map((photo, index) => photoThumb(photo, index, 'report-photo')).join('')}</div></div>` : ''}<div class="report-share-row"><button class="glass-action report-share" id="share-report">↗ SNS 공유</button></div>` : '<p class="empty">아직 생성된 리포트가 없습니다.<br>오늘 요약하기를 눌러 생성하세요.</p>';
    bindPhotoClicks();
    $('#share-report')?.addEventListener('click', async () => {
      const text = `LifeLens 리포트 · ${dayLabel(day)}\n\n${summary.summary}`;
      if (window.NativeBridge?.shareText?.('LifeLens 리포트', text)) return;
      if (navigator.share) return navigator.share({ title: 'LifeLens 리포트', text });
      await navigator.clipboard?.writeText(text);
      alert('리포트를 클립보드에 복사했습니다.');
    });
  };
  const draw = async () => {
    const selectedDay = day;
    const requestId = ++loadRequestId;
    const target = $('#records');
    target.className = 'timeline';
    target.innerHTML = '<p class="empty">기록을 불러오는 중…</p>';
    const localRecords = nativeLifeLogRecords(selectedDay);
    const localPhotos = nativeLifeLogPhotos(selectedDay);
    try {
      await syncLifeLogRecords(selectedDay, localRecords);
      const response = await api(`/v1/lifelogs/${encodeURIComponent(sharedLifeLogId)}/${selectedDay}`);
      if (requestId !== loadRequestId || selectedDay !== day) return;
      const serverRecords = (response.records || []).filter((record) => dayFromTimestamp(record.spoken_at) === selectedDay);
      const serverPhotos = (response.photos || []).filter((photo) => dayFromTimestamp(photo.taken_at) === selectedDay);
      const recordsForDay = localRecords.filter((record) => dayFromTimestamp(record.spoken_at) === selectedDay);
      const photosForDay = localPhotos.filter((photo) => dayFromTimestamp(photo.taken_at) === selectedDay);
      const allRecords = [...serverRecords, ...recordsForDay.filter((record) => !serverRecords.some((serverRecord) => serverRecord.client_record_id === record.client_record_id))].sort((a, b) => new Date(a.spoken_at) - new Date(b.spoken_at));
      const allPhotos = [...serverPhotos, ...photosForDay.filter((photo) => !serverPhotos.some((serverPhoto) => serverPhoto.client_photo_id === photo.client_photo_id))].sort((a, b) => new Date(a.taken_at) - new Date(b.taken_at));
      await preloadOriginalPhotos(allPhotos);
      if (requestId !== loadRequestId || selectedDay !== day) return;
      currentLog = { ...response, records: allRecords, localRecords, photos: allPhotos };
    } catch {
      if (requestId !== loadRequestId || selectedDay !== day) return;
      currentLog = { records: localRecords, localRecords, photos: localPhotos, summary: null };
    }
    if (requestId !== loadRequestId || selectedDay !== day) return;
    renderPanel();
  };
  const renderCalendar = () => {
    const year = calendarMonth.getFullYear();
    const month = calendarMonth.getMonth();
    $('#calendar-month').textContent = `${year}년 ${month + 1}월`;
    const first = new Date(year, month, 1).getDay();
    const last = new Date(year, month + 1, 0).getDate();
    const days = Array.from({ length: first + last }, (_, index) => {
      if (index < first) return '<span class="calendar-blank"></span>';
      const value = new Date(year, month, index - first + 1);
      const valueDay = toDay(value);
      const future = valueDay > localDay();
      return `<button class="calendar-day ${valueDay === day ? 'selected' : ''} ${valueDay === localDay() ? 'today' : ''}" data-day="${valueDay}" ${future ? 'disabled' : ''}>${value.getDate()}</button>`;
    }).join('');
    $('#calendar-days').innerHTML = days;
    document.querySelectorAll('.calendar-day').forEach((button) => button.onclick = () => {
      day = button.dataset.day;
      $('#lifelog-date').textContent = `${dayLabel(day)} · 대화 기록을 다시 살펴보세요.`;
      $('#lifelog-calendar').close();
      draw();
    });
  };
  $('#open-calendar').onclick = () => { renderCalendar(); $('#lifelog-calendar').showModal(); };
  $('#calendar-close').onclick = () => $('#lifelog-calendar').close();
  const closePhotoViewer = () => {
    $('#lifelog-photo-viewer').hidden = true;
    $('#photo-viewer-image').removeAttribute('src');
    document.body.classList.remove('photo-viewer-open');
  };
  $('#photo-viewer-close').onclick = closePhotoViewer;
  $('#lifelog-photo-viewer').onclick = (event) => { if (event.target === event.currentTarget) closePhotoViewer(); };
  $('#calendar-prev').onclick = () => { calendarMonth = new Date(calendarMonth.getFullYear(), calendarMonth.getMonth() - 1, 1); renderCalendar(); };
  $('#calendar-next').onclick = () => { calendarMonth = new Date(calendarMonth.getFullYear(), calendarMonth.getMonth() + 1, 1); renderCalendar(); };
  $('#calendar-today').onclick = () => { calendarMonth = new Date(); day = localDay(); $('#lifelog-date').textContent = `${dayLabel(day)} · 대화 기록을 다시 살펴보세요.`; $('#lifelog-calendar').close(); draw(); };
  document.querySelectorAll('[data-log-view]').forEach((button) => button.onclick = () => { logView = button.dataset.logView; renderPanel(); });
  $('#summarize').onclick = async () => {
    if (!currentLog?.records?.length && !currentLog?.photos?.length) return alert('요약할 STT 기록이나 사진이 없습니다.');
    const button = $('#summarize'); button.disabled = true; button.textContent = '요약 생성 중…'; logView = 'report'; isGenerating = true; renderPanel();
    try {
      await api('/v1/lifelogs/summarize', { method: 'POST', body: JSON.stringify({ device_id: sharedLifeLogId, date: day, records: currentLog.records }) });
      isGenerating = false;
      await draw();
    } catch (error) {
      isGenerating = false;
      renderPanel();
      const detail = String(error?.message || '알 수 없는 오류').replace(/<[^>]*>/g, '').slice(0, 160);
      alert(`요약 생성 실패: ${detail}`);
    }
    button.disabled = false; button.textContent = '오늘 요약하기';
  };
  await draw();
}

function debug() {
  content.innerHTML = `<section class="card connection-card"><span class="section-label">BACKEND SERVER</span><h2>LifeLens 백엔드 서버</h2><div class="connection-row"><span class="connection-dot checking" id="backend-dot"></span><p class="muted" id="debug-state">연결 상태 확인 중…</p></div></section><section class="card connection-card"><span class="section-label">OLLAMA SERVER</span><h2>Ollama 서버</h2><p class="muted" id="ollama-state">설정 불러오는 중…</p><div class="iot-server-form"><input id="ollama-server-input" inputmode="url" autocomplete="off" value="127.0.0.1:11434" placeholder="127.0.0.1:11434"><button class="primary" id="save-ollama-server">저장</button></div></section><section class="card connection-card"><span class="section-label">MATTER BRIDGE SERVER</span><h2>IoT 제어 브릿지</h2><div class="connection-row"><span class="connection-dot checking" id="matter-dot"></span><p class="muted" id="matter-state">연결 상태 확인 중…</p></div><div class="iot-server-form"><input id="iot-server-input" inputmode="url" autocomplete="off" value="${esc(displayServerAddress(iotServer))}" placeholder="192.168.3.80:5580"><button class="primary" id="save-iot-server">저장</button></div></section><section class="card stt-test-card"><span class="section-label">STT TEST</span><h2>STT 기록 테스트</h2><p class="muted">실제 STT처럼 현재 시각의 로컬 라이프로그 타임라인에 추가합니다.</p><textarea id="manual-stt-input" maxlength="4000" placeholder="테스트할 대화 내용을 입력하세요."></textarea><div class="stt-test-actions"><button class="control-button" id="submit-manual-stt">타임라인 추가</button><small class="muted" id="manual-stt-result"></small></div></section>`;
  const setBackendStatus = (ok, text) => { $('#backend-dot').className = `connection-dot ${ok ? 'online' : 'offline'}`; $('#debug-state').textContent = text; };
  const checkMatterBridge = () => {
    $('#matter-dot').className = 'connection-dot checking'; $('#matter-state').textContent = 'Matter 브릿지 서버 연결 확인 중…';
    matterCommand('server_info', {}, 8000).then((info) => { $('#matter-dot').className = 'connection-dot online'; $('#matter-state').textContent = `연결됨 · WebSocket · API ${info.schema_version ?? '-'}`; }).catch(() => { $('#matter-dot').className = 'connection-dot offline'; $('#matter-state').textContent = '연결 안 됨 · 주소와 포트를 확인하세요.'; });
  };
  fetch('/health', { cache: 'no-store' }).then((response) => response.json()).then((data) => setBackendStatus(true, `연결됨 · ${displayServerAddress(data.server_ip)} · ${data.model}`)).catch(() => setBackendStatus(false, '연결 안 됨 · 백엔드 서버 주소를 확인하세요.'));
  api('/v1/settings/ollama').then((settings) => {
    $('#ollama-server-input').value = displayServerAddress(settings.ollama_url);
    $('#ollama-state').textContent = `일기 ${settings.ollama_model} · 사진 ${settings.ollama_vision_model}`;
  }).catch(() => { $('#ollama-state').textContent = '설정을 불러오지 못했습니다.'; });
  checkMatterBridge();
  $('#save-ollama-server').onclick = async () => {
    const button = $('#save-ollama-server');
    const next = normalizeIotServer($('#ollama-server-input').value);
    button.disabled = true;
    $('#ollama-state').textContent = '저장 중…';
    try {
      const settings = await api('/v1/settings/ollama', { method: 'PUT', body: JSON.stringify({ ollama_url: next }) });
      $('#ollama-server-input').value = displayServerAddress(settings.ollama_url);
      $('#ollama-state').textContent = `저장됨 · 일기 ${settings.ollama_model} · 사진 ${settings.ollama_vision_model}`;
    } catch (error) {
      $('#ollama-state').textContent = '주소 형식을 확인하세요. 예: 127.0.0.1:11434';
    } finally { button.disabled = false; }
  };
  $('#save-iot-server').onclick = () => {
    const next = normalizeIotServer($('#iot-server-input').value);
    if (!/^https?:\/\/[^/:]+:\d+$/.test(next)) {
      $('#matter-dot').className = 'connection-dot offline';
      $('#matter-state').textContent = 'IP 주소와 포트 번호를 입력하세요.';
      return;
    }
    iotServer = next;
    localStorage.setItem('kimchi-iot-server', iotServer);
    $('#iot-server-input').value = displayServerAddress(iotServer);
    checkMatterBridge();
  };
  $('#submit-manual-stt').onclick = () => {
    const input = $('#manual-stt-input');
    const text = input.value.trim();
    if (!text) return input.focus();
    const button = $('#submit-manual-stt');
    const stored = window.NativeBridge?.addTestLifeLogRecord?.(text);
    if (stored) {
      input.value = '';
      $('#manual-stt-result').textContent = '타임라인에 추가됨';
    } else {
      $('#manual-stt-result').textContent = '추가 실패 · 앱을 새로고침하세요.';
    }
  };
}

function render() {
  document.querySelectorAll('.bottom button').forEach((button) => button.classList.toggle('active', button.dataset.page === page));
  if (page === 'home') home(); else if (page === 'skills') skills(); else if (page === 'lifelog') lifeLog(); else if (page === 'iot') iot(); else debug();
  window.setTimeout(() => applyCachedHeroCopy(page), 0);
}
document.querySelectorAll('.bottom button').forEach((button) => button.onclick = () => setPage(button.dataset.page));
window.setPage = setPage;
render();
refreshHeroCopiesAtLaunch();
refreshVisionState();
setInterval(refreshVisionState, 1000);
