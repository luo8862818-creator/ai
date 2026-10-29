'use strict';
const form = document.querySelector('#chat-form');
const input = document.querySelector('#message');
const sendButton = document.querySelector('#send');
const refreshButton = document.querySelector('#refresh-chat');
const messages = document.querySelector('#messages');
const welcome = document.querySelector('#welcome');

const sessionId = crypto.randomUUID();
let busy = false;
function refresh() {
  sendButton.disabled = busy || !input.value.trim();
  input.style.height = 'auto';
  input.style.height = Math.min(input.scrollHeight, 112) + 'px';
}
// Build DOM nodes rather than injecting HTML from model output.
function inlineText(parent, text) {
  // Recognize code and emphasis before repairing links, so examples remain literal.
  const tokens = /`[^`]+`|\*\*[^*]+\*\*|\[[^\]\n]+\]\\?\(\[https?:\/\/[^\s\]]+\]\(https?:\/\/[^\s)]+\)\\?\)|\[[^\]\n]+\]\\?\(https?:\/\/[^\s)\\]+\\?\)|https?:\/\/[^\s<>`*\\\u3000-\u303f\uff00-\uffef]+/g;
  let offset = 0;
  for (const match of text.matchAll(tokens)) {
    parent.append(document.createTextNode(text.slice(offset, match.index)));
    const part = match[0];
    if (part.startsWith('`')) {
      const code = document.createElement('code');
      code.textContent = part.slice(1, -1);
      parent.append(code);
    } else if (part.startsWith('**')) {
      const strong = document.createElement('strong');
      // Emphasis may contain links; textContent would leave the Markdown visible.
      inlineText(strong, part.slice(2, -2));
      parent.append(strong);
    } else {
      const normalized = part
        .replace(/\[([^\]\n]+)\]\\?\(\[(https?:\/\/[^\s\]]+)\]\(\2\)\\?\)/g, '[$1]($2)')
        .replace(/\[([^\]\n]+)\]\\?\((https?:\/\/[^\s)\\]+)\\?\)/g, '[$1]($2)');
      const link = normalized.match(/^\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)$/);
      const href = link ? link[2] : normalized;
      try {
        const url = new URL(href);
        if (!['http:', 'https:'].includes(url.protocol)) throw new Error('Unsupported URL');
        const anchor = document.createElement('a');
        anchor.href = href;
        anchor.textContent = link ? link[1] : part;
        anchor.target = '_blank';
        anchor.rel = 'noopener noreferrer';
        parent.append(anchor);
      } catch { parent.append(document.createTextNode(part)); }
    }
    offset = match.index + part.length;
  }
  parent.append(document.createTextNode(text.slice(offset)));
}
function renderAnswer(parent, text) {
  parent.classList.add('formatted');
  const lines = text.replace(/\r\n?/g, '\n').split('\n');
  const cells = line => line.trim().replace(/^\||\|$/g, '').split('|').map(cell => cell.trim());
  let i = 0;
  while (i < lines.length) {
    const line = lines[i];
    if (!line.trim()) { i++; continue; }
    if (line.trim().startsWith('```')) {
      const pre = document.createElement('pre');
      const code = document.createElement('code');
      const block = [];
      i++;
      while (i < lines.length && !lines[i].trim().startsWith('```')) block.push(lines[i++]);
      code.textContent = block.join('\n'); pre.append(code); parent.append(pre); i++; continue;
    }
    if (line.includes('|') && i + 1 < lines.length && /^\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)+\|?$/.test(lines[i + 1].trim())) {
      const wrap = document.createElement('div'); wrap.className = 'table-scroll';
      const table = document.createElement('table');
      const head = document.createElement('thead'); const tr = document.createElement('tr');
      cells(line).forEach(value => { const th = document.createElement('th'); inlineText(th, value); tr.append(th); });
      head.append(tr); table.append(head); i += 2;
      const body = document.createElement('tbody');
      while (i < lines.length && lines[i].includes('|') && lines[i].trim()) {
        const row = document.createElement('tr');
        cells(lines[i++]).forEach(value => { const td = document.createElement('td'); inlineText(td, value); row.append(td); });
        body.append(row);
      }
      table.append(body); wrap.append(table); parent.append(wrap); continue;
    }
    const item = line.match(/^\s*(?:([-*+])|\d+[.)、])\s+(.+)$/);
    if (item) {
      const tag = item[1] ? 'ul' : 'ol';
      const list = document.createElement(tag);
      if (tag === 'ol') list.start = parseInt(line.trim(), 10) || 1;
      while (i < lines.length) {
        const match = lines[i].match(/^\s*(?:([-*+])|\d+[.)、])\s+(.+)$/);
        if (!match || (match[1] ? 'ul' : 'ol') !== tag) break;
        const li = document.createElement('li'); inlineText(li, match[2]); list.append(li); i++;
      }
      parent.append(list); continue;
    }
    const heading = line.match(/^#{1,6}\s+(.+)$/);
    const p = document.createElement(heading ? 'h3' : 'p');
    inlineText(p, heading ? heading[1] : line); parent.append(p); i++;
  }
}
function append(text, role) {
  const row = document.createElement('div');
  row.className = 'message ' + role;
  const avatar = document.createElement('div');
  avatar.className = 'avatar';
  if (role === 'user') avatar.textContent = '我';
  else {
    const robot = document.createElement('img');
    robot.src = '/robot.svg';
    robot.alt = '机器人';
    avatar.append(robot);
  }
  const bubble = document.createElement('div');
  bubble.className = 'bubble';
  if (role === 'assistant') renderAnswer(bubble, text);
  else bubble.textContent = text;
  row.append(avatar, bubble);
  messages.append(row);
  messages.scrollTop = messages.scrollHeight;
  return row;
}
input.addEventListener('input', refresh);
input.addEventListener('keydown', event => {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault();
    if (!sendButton.disabled) form.requestSubmit();
  }
});
async function loadSuggestions() {
  const container = document.querySelector('.suggestions');
  try {
    const response = await fetch('/api/chat/suggestions');
    if (!response.ok) return;
    const questions = await response.json();
    for (const question of questions) {
      const button = document.createElement('button');
      button.type = 'button';
      button.textContent = question;
      button.addEventListener('click', () => {
        input.value = question;
        refresh();
        input.focus();
      });
      container.append(button);
    }
    container.hidden = questions.length === 0;
  } catch { /* No configured suggestions: keep the welcome page quiet. */ }
}
loadSuggestions();
refreshButton.addEventListener('click', () => {
  if (!busy) window.location.reload();
});
function createReply() {
  const row = append('', 'assistant');
  row.classList.add('working');
  const bubble = row.querySelector('.bubble');
  bubble.classList.remove('formatted');
  const details = document.createElement('details');
  details.className = 'process';
  details.open = true;
  const summary = document.createElement('summary');
  const icon = document.createElement('span'); icon.className = 'process-indicator';
  const label = document.createElement('span'); label.textContent = '正在处理';
  const time = document.createElement('span'); time.className = 'elapsed';
  const chevron = document.createElement('span'); chevron.className = 'process-chevron'; chevron.textContent = '⌄';
  summary.append(icon, label, time, chevron);
  const note = document.createElement('p'); note.className = 'process-note';
  note.textContent = '执行进度与工具调用记录 · 用时包含网络等待和回复生成';
  const steps = document.createElement('ol'); steps.className = 'process-steps';
  details.append(summary, note, steps);
  const answer = document.createElement('div'); answer.className = 'answer-body'; answer.hidden = true;
  const errorBox = document.createElement('p'); errorBox.className = 'reply-error'; errorBox.hidden = true;
  bubble.append(details, answer, errorBox);
  const started = performance.now();
  let finished = false;
  let backendElapsed = 0;
  let answerText = '';
  let renderFrame = 0;
  const seen = new Set();
  const seconds = ms => (ms / 1000).toFixed(2) + ' 秒';
  const tick = () => { time.textContent = seconds(Math.max(backendElapsed, performance.now() - started)); };
  tick();
  const timer = setInterval(tick, 100);
  const scroll = () => { if (messages.scrollHeight - messages.scrollTop - messages.clientHeight < 260) messages.scrollTop = messages.scrollHeight; };
  const step = (text, key, elapsedMs = 0, isTool = false) => {
    if (seen.has(key)) return;
    seen.add(key);
    const item = document.createElement('li');
    if (isTool) item.className = 'tool-step';
    const name = document.createElement('span'); name.textContent = text;
    const stamp = document.createElement('span'); stamp.className = 'step-time'; stamp.textContent = seconds(elapsedMs);
    item.append(name, stamp); steps.append(item); scroll();
  };
  const render = () => {
    renderFrame = 0;
    answer.replaceChildren();
    renderAnswer(answer, answerText);
    answer.hidden = !answerText;
    scroll();
  };
  const setAnswer = text => {
    answerText = text;
    if (!renderFrame) renderFrame = requestAnimationFrame(render);
  };
  const finish = (ok, elapsedMs) => {
    if (finished) return;
    finished = true;
    clearInterval(timer);
    time.textContent = seconds(Number.isFinite(elapsedMs) ? elapsedMs : performance.now() - started);
    label.textContent = ok ? '处理完成' : '处理未完成';
    row.classList.remove('working');
    row.classList.toggle('failed', !ok);
    // Leave recorded tools discoverable without occupying the answer area.
    details.open = !ok;
    if (renderFrame) cancelAnimationFrame(renderFrame);
    render();
  };
  return {
    event(type, data) {
      backendElapsed = Math.max(backendElapsed, data.elapsedMs || 0);
      if (type === 'status') step(data.text, data.stepId, data.elapsedMs);
      if (type === 'tool') step('调用工具：' + data.text, data.stepId, data.elapsedMs, true);
      if (type === 'answer') setAnswer(data.text);
      if (type === 'complete') {
        setAnswer(data.answer);
        step('回复已完成', 'complete', data.elapsedMs);
        finish(true, data.elapsedMs);
      }
      if (type === 'error') {
        errorBox.textContent = data.message;
        errorBox.hidden = false;
        if (answerText) {
          const notice = document.createElement('p'); notice.textContent = '以上为未完成的部分回复，请核实后再使用。';
          step(notice.textContent, 'partial-warning', data.elapsedMs);
        }
        finish(false, data.elapsedMs);
      }
    },
    fail(message) {
      if (finished) return;
      errorBox.textContent = message;
      errorBox.hidden = false;
      finish(false);
    },
    get finished() { return finished; }
  };
}

// Parse SSE frames across arbitrary network chunk and UTF-8 boundaries.
async function readEvents(response, onEvent) {
  if (!response.body) throw new Error('浏览器无法读取流式回复，请升级浏览器。');
  const reader = response.body.getReader();
  const decoder = new TextDecoder('utf-8');
  let pending = '';
  let eventType = 'message';
  let data = [];
  const line = value => {
    if (value.endsWith('\r')) value = value.slice(0, -1);
    if (value === '') {
      if (data.length) onEvent(eventType, JSON.parse(data.join('\n')));
      eventType = 'message'; data = [];
    } else if (value.startsWith('event:')) eventType = value.slice(6).trim();
    else if (value.startsWith('data:')) data.push(value.slice(5).replace(/^ /, ''));
  };
  try {
    while (true) {
      const chunk = await reader.read();
      pending += chunk.done ? decoder.decode() : decoder.decode(chunk.value, {stream: true});
      let newline;
      while ((newline = pending.indexOf('\n')) >= 0) {
        line(pending.slice(0, newline));
        pending = pending.slice(newline + 1);
      }
      if (chunk.done) { if (pending) line(pending); line(''); break; }
    }
  } finally { reader.releaseLock(); }
}
form.addEventListener('submit', async event => {
  event.preventDefault();
  const message = input.value.trim();
  if (busy || !message) return;
  busy = true;
  refreshButton.disabled = true;
  welcome.hidden = true;
  append(message, 'user');
  input.value = '';
  refresh();
  const reply = createReply();
  try {
    const response = await fetch('/api/chat/stream', {
      method: 'POST',
      headers: {'Content-Type': 'application/json', 'Accept': 'text/event-stream'},
      body: JSON.stringify({message, sessionId})
    });
    if (!response.ok) {
      let result;
      try { result = await response.json(); } catch { /* fall back to HTTP status */ }
      throw new Error(result?.message || '请求失败（HTTP ' + response.status + '），请稍后重试。');
    }
    if (!response.headers.get('Content-Type')?.includes('text/event-stream')) throw new Error('服务器未返回流式回复，请重启后端并刷新页面。');
    await readEvents(response, (type, data) => reply.event(type, data));
    if (!reply.finished) throw new Error('回复连接提前结束。任务可能仍在运行，请检查执行记录。');
  } catch (error) {
    reply.fail(error instanceof TypeError ? '网络连接中断，请检查后端服务。任务可能仍在运行，请先核实执行状态。' : error.message);
  } finally {
    busy = false;
    refreshButton.disabled = false;
    refresh();
    input.focus();
  }
});
refresh();
