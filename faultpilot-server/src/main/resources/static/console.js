(() => {
  let incidentId = localStorage.getItem('faultpilot.incidentId');
  let eventSource;
  let csrf;
  const $ = id => document.getElementById(id);
  const auth = () => {
    const user = $('username').value;
    const password = $('password').value;
    return user && password ? { Authorization: `Basic ${btoa(`${user}:${password}`)}` } : {};
  };
  const loadCsrf = async () => {
    csrf = await (await fetch('/api/security/csrf', { credentials: 'same-origin' })).json();
  };
  const api = async (url, options = {}) => {
    const method = (options.method || 'GET').toUpperCase();
    if (!['GET', 'HEAD', 'OPTIONS'].includes(method) && !csrf) await loadCsrf();
    const headers = { ...auth(), ...(options.headers || {}) };
    if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) headers[csrf.headerName] = csrf.token;
    const response = await fetch(url, { ...options, credentials: 'same-origin', headers });
    if (!response.ok) throw new Error(`${response.status} ${await response.text()}`);
    return response.status === 204 ? null : response.json();
  };
  const setConnection = connected => { $('connection').textContent = connected ? 'Connected' : 'Disconnected'; $('connection').classList.toggle('connected', connected); };
  const render = async () => {
    if (!incidentId) return;
    try {
      const incident = await api(`/api/incidents/${incidentId}`);
      $('incident-empty').hidden = true; $('incident-view').hidden = false;
      $('incident-id').textContent = incident.incidentId; $('incident-status').textContent = incident.status; $('incident-service').textContent = incident.snapshot.serviceName;
      $('report').textContent = 'Waiting for report...';
      $('evidence').innerHTML = '';
      renderAction(null);
      try { $('report').textContent = JSON.stringify(await api(`/api/incidents/${incidentId}/report`), null, 2); }
      catch (_) { $('report').textContent = incident.status === 'FAILED' ? 'No diagnosis report was produced. Inspect the event stream for the failure.' : 'No diagnosis report is available yet.'; }
      try { $('evidence').innerHTML = (await api(`/api/incidents/${incidentId}/evidence`)).map(e => `<li><b>${escapeHtml(e.type)}</b><br><small>${escapeHtml(e.source)}</small><br>${escapeHtml(e.summary)}</li>`).join(''); } catch (_) { $('evidence').innerHTML = '<li>No evidence is available.</li>'; }
      try { renderAction((await api(`/api/pending-actions/incident/${incidentId}`))[0]); } catch (_) { renderAction(null); }
    } catch (error) { $('incident-empty').textContent = error.message; }
  };
  const connectEvents = () => {
    if (!incidentId) return;
    if (eventSource) eventSource.close();
    eventSource = new EventSource(`/api/incidents/${incidentId}/events`);
    eventSource.onopen = () => setConnection(true);
    eventSource.onerror = () => setConnection(false);
    eventSource.onmessage = event => appendEvent(event.lastEventId, 'message', event.data);
    ['INVESTIGATION_STARTED','INVESTIGATION_PLANNED','AGENTS_COMPLETED','DIAGNOSIS_COMPLETED','DIAGNOSIS_INCONCLUSIVE','ORCHESTRATION_FAILED','ACTION_PENDING','ACTION_SKIPPED','ACTION_CONFIRMED','ACTION_REJECTED','VERIFICATION_COMPLETED'].forEach(name => eventSource.addEventListener(name, event => { appendEvent(event.lastEventId, name, event.data); render(); }));
  };
  const appendEvent = (id, type, data) => { const item = document.createElement('li'); item.innerHTML = `<time>#${id}</time><code>${type}</code><span>${escapeHtml(data)}</span>`; $('events').prepend(item); };
  const escapeHtml = value => String(value).replace(/[&<>'"]/g, c => ({ '&':'&amp;','<':'&lt;','>':'&gt;',"'":'&#39;','"':'&quot;' }[c]));
  const renderAction = action => {
    if (!action) { $('action').textContent = 'None'; return; }
    $('action').innerHTML = `<strong>${action.actionCode}</strong><span>${action.status}</span>`;
    if (action.status !== 'PENDING') return;
    const confirm = document.createElement('button'); confirm.textContent = 'Confirm';
    confirm.onclick = async () => { await api(`/api/pending-actions/${action.id}/confirm`, { method: 'POST', headers: { 'X-Request-Id': crypto.randomUUID() } }); render(); };
    const reject = document.createElement('button'); reject.textContent = 'Reject';
    reject.className = 'quiet';
    reject.onclick = async () => { await api(`/api/pending-actions/${action.id}/reject`, { method: 'POST', headers: { 'X-Request-Id': crypto.randomUUID() } }); render(); };
    $('action').append(confirm, reject);
  };
  $('connect').onclick = async () => {
    try {
      await loadCsrf();
      const body = new URLSearchParams({ username:$('username').value, password:$('password').value, _csrf:csrf.token });
      const login = await fetch('/login', { method:'POST', credentials:'same-origin', headers:{'Content-Type':'application/x-www-form-urlencoded', [csrf.headerName]:csrf.token}, body });
      if (!login.ok && login.status !== 302) throw new Error('Login failed');
      setConnection(true); await render(); connectEvents();
    } catch (_) { setConnection(false); }
  };
  $('refresh').onclick = render;
  $('incident-form').onsubmit = async event => { event.preventDefault(); try { const result = await api('/api/incidents', { method:'POST', headers:{'Content-Type':'application/json','X-Request-Id':crypto.randomUUID()}, body: JSON.stringify({ serviceName:$('service').value, symptom:$('symptom').value, allowRemediation:$('allow-remediation').checked }) }); incidentId=result.incidentId; localStorage.setItem('faultpilot.incidentId', incidentId); $('events').innerHTML=''; await render(); connectEvents(); } catch(error) { alert(error.message); } };
  render();
})();
