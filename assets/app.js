/* ── NR Band Manager App Logic v2 ── */

// Common NR5G bands for India / Poco F6
const NR_BANDS = [1,2,3,5,7,8,12,20,25,28,38,40,41,48,66,71,77,78,79,257,258,260,261];
const LTE_BANDS = [1,2,3,4,5,7,8,11,12,13,14,17,18,19,20,21,25,26,28,29,30,32,34,38,39,40,41,42,43,46,48,66,71];

// State
let selectedNR = new Set();
let selectedLTE = new Set();
let toastTimer = null;
let allSims = [];

// ── Initialization ──

document.addEventListener('DOMContentLoaded', () => {
  initBandGrids();
  initApp();
  loadSims();
});

function loadSims(retries = 5) {
  try {
    let simsStr = NRBand.getSimInfo();
    allSims = JSON.parse(simsStr);
    
    if (allSims.length === 0 && retries > 0) {
      setTimeout(() => loadSims(retries - 1), 1000);
      return;
    }

    let html = `<option value="-1">All SIMs (Default)</option>`;
    allSims.forEach(sim => {
      html += `<option value="${sim.subId}">SIM ${sim.slot + 1} (${sim.name})</option>`;
    });
    const globSel = document.getElementById('globalSimSelect');
    if (globSel) {
      globSel.innerHTML = html;
      
      // Remember user's last selected SIM
      const savedSim = localStorage.getItem('savedGlobalSim');
      if (savedSim) {
        globSel.value = savedSim;
      }
      
      globSel.addEventListener('change', (e) => {
        localStorage.setItem('savedGlobalSim', e.target.value);
      });
    }
  } catch(e) {
    if (retries > 0) {
      setTimeout(() => loadSims(retries - 1), 1000);
    } else {
      console.log("Failed to load SIMs");
    }
  }
}

function clearBands(type) {
  if (type === 'nr') selectedNR.clear();
  if (type === 'lte') selectedLTE.clear();
  renderBands('nr', NR_BANDS, selectedNR, 'nrBandGrid');
  renderBands('lte', LTE_BANDS, selectedLTE, 'lteBandGrid');
}

function copyLogs() {
  const el = document.getElementById('lockResults');
  const text = el.innerText;
  if (!text) return;
  // Copy to clipboard fallback since it's a webview
  NRBand.execRoot(`echo "${text.replace(/"/g, '\\"')}" > /sdcard/nrband_logs.txt`);
  el.innerHTML += `<div style="color:var(--accent);margin-top:10px;">Logs saved to /sdcard/nrband_logs.txt!</div>`;
}

function renderBands(type, bands, selectedSet, containerId) {
  const grid = document.getElementById(containerId);
  if (!grid) return;
  grid.innerHTML = '';
  bands.forEach(b => {
    const chip = document.createElement('div');
    const prefix = type === 'nr' ? 'n' : 'B';
    chip.className = 'band-chip' + (selectedSet.has(b) ? ' selected' : '');
    chip.textContent = prefix + b;
    chip.id = `${type}-band-${b}`;
    chip.onclick = () => toggleBand(type, b, chip);
    grid.appendChild(chip);
  });
}

async function initApp() {
  setStatus('Checking root…', 'warning');
  try {
    const hasRoot = await call('checkRoot');
    if (hasRoot === 'true' || hasRoot === true) {
      setStatus('Root ✓ — Ready', 'ok');
    } else {
      setStatus('No root access!', 'error');
      showToast('⚠️ Root required! Grant su permission and reopen.');
      return;
    }
    const info = JSON.parse(await call('getDeviceInfo'));
    document.getElementById('devModel').textContent = info.model || '—';
    document.getElementById('devSoc').textContent = info.soc || info.hardware || '—';
    document.getElementById('devAndroid').textContent = `${info.android} (SDK ${info.sdk})`;
    document.getElementById('devRoot').textContent = '✓ Granted';
    loadSavedLocks();
    refreshNetwork();
  } catch (e) {
    setStatus('Init failed', 'error');
    console.error(e);
  }
}

// ── Bridge ──

function call(method, ...args) {
  return new Promise((resolve) => {
    try {
      let result;
      switch(args.length) {
        case 0: result = NRBand[method](); break;
        case 1: result = NRBand[method](args[0]); break;
        case 2: result = NRBand[method](args[0], args[1]); break;
        default: result = NRBand[method](...args);
      }
      resolve(result);
    } catch(e) { resolve('ERROR: ' + e.message); }
  });
}

// ── Tabs ──

function switchTab(tabId) {
  document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
  document.querySelectorAll('.tab-content').forEach(t => t.classList.remove('active'));
  document.querySelector(`.tab[data-tab="${tabId}"]`).classList.add('active');
  document.getElementById('tab-' + tabId).classList.add('active');
}

// ── Status ──

function setStatus(text, type) {
  document.getElementById('statusPill').className = 'status-pill ' + (type || '');
  document.getElementById('statusText').textContent = text;
}

function showToast(msg) {
  const toast = document.getElementById('toast');
  toast.textContent = msg;
  toast.classList.remove('hidden');
  toast.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => {
    toast.classList.remove('show');
    setTimeout(() => toast.classList.add('hidden'), 300);
  }, 3000);
}

// ── Dashboard ──

async function refreshNetwork() {
  const el = document.getElementById('networkStatus');
  el.textContent = 'Loading…';
  const bands = await call('getCurrentBands');
  el.textContent = bands || 'No data';
}

async function loadSavedLocks() {
  try {
    const locks = JSON.parse(await call('getSavedLocks'));
    const el = document.getElementById('savedLockInfo');
    let text = '';
    if (locks.nr_bands) text += 'NR Bands: ' + locks.nr_bands + '\n';
    if (locks.lte_bands) text += 'LTE Bands: ' + locks.lte_bands + '\n';
    if (locks.network_mode) text += 'Network Mode: ' + locks.network_mode + '\n';
    el.textContent = text || 'No lock saved';
    document.getElementById('autoReapply').checked = locks.auto_reapply || false;
    if (locks.nr_bands) {
      locks.nr_bands.split(',').forEach(b => {
        const n = parseInt(b.trim());
        if (n) { selectedNR.add(n); updateChip('nr', n, true); }
      });
    }
    if (locks.lte_bands) {
      locks.lte_bands.split(',').forEach(b => {
        const n = parseInt(b.trim());
        if (n) { selectedLTE.add(n); updateChip('lte', n, true); }
      });
    }
  } catch(e) { console.error(e); }
}

async function reapplyLock() {
  showToast('Re-applying saved lock…');
  const locks = JSON.parse(await call('getSavedLocks'));
  let result = '';
  if (locks.nr_bands) result += await call('setNRBands', locks.nr_bands) + '\n';
  if (locks.lte_bands) result += await call('setLTEBands', locks.lte_bands) + '\n';
  showToast('✓ Lock commands sent');
  document.getElementById('savedLockInfo').textContent += '\n\n── Result ──\n' + result;
}

async function restoreDefaults() {
  if (!confirm('Restore all bands to hardware defaults?')) return;
  showToast('Restoring defaults…');
  const r = await call('restoreDefaults');
  showToast('✓ Defaults restored');
  selectedNR.clear(); selectedLTE.clear();
  document.querySelectorAll('.band-chip').forEach(c => c.classList.remove('selected'));
  document.getElementById('savedLockInfo').textContent = r;
}

async function toggleAutoReapply() {
  const checked = document.getElementById('autoReapply').checked;
  await call('setAutoReapply', checked);
  showToast(checked ? '✓ Auto re-apply enabled' : 'Auto re-apply disabled');
}

// ── Scan ──

async function runProbe() {
  const btn = document.getElementById('probeBtn');
  const results = document.getElementById('probeResults');
  btn.disabled = true;
  btn.textContent = 'Probing modem…';
  results.classList.remove('hidden');
  results.textContent = 'Running comprehensive modem diagnostics…\n\n';
  const output = await call('probeBands');
  results.textContent = output || 'No response.';
  btn.disabled = false;
  btn.innerHTML = '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><path d="m21 21-4.35-4.35"/></svg> Run Modem Probe';
}

async function runDiagnostics() {
  const results = document.getElementById('probeResults');
  results.classList.remove('hidden');
  results.textContent = 'Running full diagnostics…\n\n';
  const output = await call('runDiagnostics');
  results.textContent = output;
}

async function scanBands(type) {
  const results = document.getElementById('scanResults');
  results.classList.remove('hidden');
  results.textContent = 'Scanning…\n';

  let output = '';
  output += '═══ Current Network & Bands ═══\n';
  output += await call('getCurrentBands') + '\n\n';

  output += '\n═══ Radio Info ═══\n';
  output += await call('getRadioInfo') + '\n';

  results.textContent = output;
}

// ── Lock ──

function initBandGrids() {
  const nrGrid = document.getElementById('nrBandGrid');
  const lteGrid = document.getElementById('lteBandGrid');
  NR_BANDS.forEach(b => {
    const chip = document.createElement('div');
    chip.className = 'band-chip';
    chip.textContent = 'n' + b;
    chip.id = 'nr-band-' + b;
    chip.onclick = () => toggleBand('nr', b, chip);
    nrGrid.appendChild(chip);
  });
  LTE_BANDS.forEach(b => {
    const chip = document.createElement('div');
    chip.className = 'band-chip';
    chip.textContent = 'B' + b;
    chip.id = 'lte-band-' + b;
    chip.onclick = () => toggleBand('lte', b, chip);
    lteGrid.appendChild(chip);
  });
}

function toggleBand(type, band, chip) {
  const set = type === 'nr' ? selectedNR : selectedLTE;
  if (set.has(band)) { set.delete(band); chip.classList.remove('selected'); }
  else { set.add(band); chip.classList.add('selected'); }
  updateLockBtn(type);
}

function updateChip(type, band, sel) {
  const c = document.getElementById(`${type}-band-${band}`);
  if (c) { sel ? c.classList.add('selected') : c.classList.remove('selected'); }
}

function updateLockBtn(type) {
  const set = type === 'nr' ? selectedNR : selectedLTE;
  const btn = document.getElementById(type === 'nr' ? 'lockNRBtn' : 'lockLTEBtn');
  btn.textContent = `Lock ${set.size} ${type.toUpperCase()} Band${set.size !== 1 ? 's' : ''}`;
  btn.disabled = set.size === 0;
}

async function lockNRBands() {
  if (selectedNR.size === 0) {
    showToast("Please select at least one NR band");
    return;
  }
  const btn = document.getElementById('lockNRBtn');
  btn.innerHTML = `<div class="spinner"></div> Locking...`;
  btn.disabled = true;

  const subId = parseInt(document.getElementById('globalSimSelect').value);
  const bandStr = Array.from(selectedNR).join(',');
  
  setTimeout(() => {
    let res = NRBand.lockBands('nr', bandStr, subId);
    showResults(res);
    btn.innerHTML = `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="11" width="18" height="11" rx="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg> Lock Selected NR Bands`;
    btn.disabled = false;
    showToast("Band lock applied!");
  }, 100);
}

async function lockLTEBands() {
  if (selectedLTE.size === 0) {
    showToast("Please select at least one LTE band");
    return;
  }
  const btn = document.getElementById('lockLTEBtn');
  btn.innerHTML = `<div class="spinner"></div> Locking...`;
  btn.disabled = true;

  const subId = parseInt(document.getElementById('globalSimSelect').value);
  const bandStr = Array.from(selectedLTE).join(',');

  setTimeout(() => {
    let res = NRBand.lockBands('lte', bandStr, subId);
    showResults(res);
    btn.innerHTML = `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="11" width="18" height="11" rx="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg> Lock Selected LTE Bands`;
    btn.disabled = false;
    showToast("LTE Band lock applied!");
  }, 100);
}

function setModeNative(bitmask) {
  showToast("Setting network mode...");
  const subId = parseInt(document.getElementById('globalSimSelect').value);
  setTimeout(() => {
    let res = NRBand.setNetworkModeNative(bitmask, subId);
    showResults(res);
  }, 100);
}

function force5GPlusPlus() {
  if (selectedNR.size === 0) {
    showToast("Please select the bands you want to use for 5G++ first!");
    return;
  }

  const subId = parseInt(document.getElementById('globalSimSelect').value);
  showToast("Applying 5G++ optimizations to selected bands...");

  // Call Native optimizations
  setTimeout(() => {
    let res = NRBand.force5GPlusPlusNative(subId);
    
    // Apply the lock
    let bandStr = Array.from(selectedNR).join(',');
    res += "\n\n" + NRBand.lockBands('nr', bandStr, subId);
    
    showResults(res);
    showToast("5G++ optimizations applied!");
  }, 100);
}

function showResults(res) {
  const el = document.getElementById('lockResults');
  el.classList.remove('hidden');
  
  // Clean up logs to be less overwhelming
  let cleanRes = res.replace(/public void com\.android\.internal\.telephony.*/g, "");
  el.innerHTML = cleanRes.replace(/\n/g, '<br>');
}

async function setMode(mode) {
  showToast('Setting network mode…');
  const result = await call('setNetworkMode', mode);

  const resultEl = document.getElementById('lockResults');
  resultEl.classList.remove('hidden');
  resultEl.textContent = 'Network mode ' + mode + ':\n' + result;
  showToast('✓ Network mode set');
}

async function setNROnly() {
  showToast('Forcing NR Only mode…');
  const result = await call('setNROnly');
  const resultEl = document.getElementById('lockResults');
  resultEl.classList.remove('hidden');
  resultEl.textContent = 'NR Only:\n' + result;
  showToast('✓ NR Only mode set');
}

// ── Terminal ──

async function sendATCmd() {
  const input = document.getElementById('atInput');
  const output = document.getElementById('termOutput');
  const cmd = input.value.trim();
  if (!cmd) return;
  output.textContent += '> ' + cmd + '\n';
  const result = await call('sendAT', cmd);
  output.textContent += result + '\n\n';
  output.scrollTop = output.scrollHeight;
  input.value = '';
}

function quickAT(cmd) {
  document.getElementById('atInput').value = cmd;
  sendATCmd();
}

async function sendRootCmd() {
  const input = document.getElementById('rootInput');
  const output = document.getElementById('rootOutput');
  const cmd = input.value.trim();
  if (!cmd) return;
  output.textContent += '# ' + cmd + '\n';
  const result = await call('execRoot', cmd);
  output.textContent += result + '\n\n';
  output.scrollTop = output.scrollHeight;
  input.value = '';
}
