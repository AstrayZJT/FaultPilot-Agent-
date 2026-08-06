(() => {
  let incidentId = localStorage.getItem('faultpilot.incidentId');
  let eventSource;
  const $ = id => document.getElementById(id);
  const auth = () => {
    const user = $('username').value;
    const password = $('password').value;
    return user && password ? { Authorization: `Basic ${btoa(`${user}:${password}`)}` } : {};
  };
  const api = async (url, options = {}) => {
    const response = await fetch(url, { ...options, headers: { ...auth(), ...(options.headers || {}) } });
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
      try { $('report').textContent = JSON.stringify(await api(`/api/incidents/${incidentId}/report`), null, 2); } catch (_) {}
      try { $('evidence').innerHTML = (await api(`/api/incidents/${incidentId}/evidence`)).map(e => `<li><b>${e.type}</b><br>${e.summary}</li>`).join(''); } catch (_) {}
    } catch (error) { $('incident-empty').textContent = error.message; }
  };
  const connectEvents = () => {
    if (!incidentId) return;
    if (eventSource) eventSource.close();
    eventSource = new EventSource(`/api/incidents/${incidentId}/events`);
    eventSource.onopen = () => setConnection(true);
    eventSource.onerror = () => setConnection(false);
    eventSource.onmessage = event => appendEvent(event.lastEventId, 'message', event.data);
    ['INVESTIGATION_STARTED','INVESTIGATION_PLANNED','AGENTS_COMPLETED','DIAGNOSIS_COMPLETED','ACTION_PENDING','ACTION_CONFIRMED','ACTION_REJECTED','VERIFICATION_COMPLETED'].forEach(name => eventSource.addEventListener(name, event => { appendEvent(event.lastEventId, name, event.data); render(); }));
  };
  const appendEvent = (id, type, data) => { const item = document.createElement('li'); item.innerHTML = `<time>#${id}</time><code>${type}</code><span>${escapeHtml(data)}</span>`; $('events').prepend(item); };
  const escapeHtml = value => String(value).replace(/[&<>'"]/g, c => ({ '&':'&amp;','<':'&lt;','>':'&gt;',"'":'&#39;','"':'&quot;' }[c]));
  $('connect').onclick = async () => {
    try {
      const token = await (await fetch('/api/security/csrf', { credentials:'same-origin' })).json();
      const body = new URLSearchParams({ username:$('username').value, password:$('password').value, _csrf:token.token });
      const login = await fetch('/login', { method:'POST', credentials:'same-origin', headers:{'Content-Type':'application/x-www-form-urlencoded', [token.headerName]:token.token}, body });
      if (!login.ok && login.status !== 302) throw new Error('Login failed');
      setConnection(true); await render(); connectEvents();
    } catch (_) { setConnection(false); }
  };
  $('refresh').onclick = render;
  $('incident-form').onsubmit = async event => { event.preventDefault(); try { const result = await api('/api/incidents', { method:'POST', headers:{'Content-Type':'application/json','X-Request-Id':crypto.randomUUID()}, body: JSON.stringify({ serviceName:$('service').value, symptom:$('symptom').value, allowRemediation:$('allow-remediation').checked }) }); incidentId=result.incidentId; localStorage.setItem('faultpilot.incidentId', incidentId); $('events').innerHTML=''; await render(); connectEvents(); } catch(error) { alert(error.message); } };
  render();
})();
