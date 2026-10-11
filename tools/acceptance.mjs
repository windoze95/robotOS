#!/usr/bin/env node
// Offline analysis and strictly filtered, read-only ADB evidence collection. No dependencies.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { execFileSync, spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const EVENTS = new Set([
  'turn_start', 'connect_start', 'socket_open', 'connect_ready', 'hold_start', 'release',
  'release_gate_closed', 'input_end_sent', 'first_reply', 'playback_started',
  'interrupt_start', 'playback_flushed', 'network_wait', 'network_available',
  'session_closed', 'session_failed', 'exchange_complete', 'service_started', 'service_stopped',
]);
const COUNTERS = new Set(['mode', 'warm', 'ready', 'connection', 'queued_bytes', 'max_queue_ms']);
const SCENARIOS = new Set([
  'voice_cold', 'voice_warm', 'dictation', 'interruption', 'network_loss', 'service_restart', 'standby',
]);
const BATTERY_HEADER = 'wall_ms,elapsed_ms,event,level,plugged,wifi';
const TURN_FIELDS = ['scope', 'turn', 'mode', 'connection_state', 'connection', 'connection_ms',
  'readiness_after_turn_start_ms', 'max_queue_age_ms', 'queued_bytes_at_input_end',
  'release_gate_close_ms', 'release_to_input_end_ms', 'release_to_first_reply_received_ms',
  'release_to_playback_head_ms', 'playback_before_release', 'interrupt_local_flush_ms',
  'interrupt_audible_silence_ms', 'failed', 'exchange_complete'];
class MeasurementError extends Error {}
const integer = (value) => Number.isSafeInteger(value) && value >= 0;
const label = (value) => typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/.test(value);

export function validateMetric(value) {
  if (!value || Array.isArray(value) || typeof value !== 'object') return null;
  if (value.schema !== 1 || !EVENTS.has(value.event) || !integer(value.t_ms) || !integer(value.turn)) return null;
  for (const [key, number] of Object.entries(value)) {
    if (['schema', 'event', 't_ms', 'turn'].includes(key)) continue;
    if (key !== 'pid' && !COUNTERS.has(key)) return null;
    if (!integer(number) || (['warm', 'ready'].includes(key) && number > 1) || (key === 'mode' && number > 2)) return null;
  }
  return value;
}

// Accept only the r1ptt INFO tag and the complete safe grammar, never arbitrary log messages.
export function parseMetricLine(line) {
  if (line.length > 1024) return null;
  const match = line.match(/^I\/r1ptt\s*(?:\(\s*(\d+)\s*\))?\s*:\s*(metric event=\w+ t_ms=\d+ turn=\d+(?: \w+=\d+)*)\s*$/);
  if (!match) return null;
  const parts = match[2].split(' ').slice(1);
  const value = { schema: 1 };
  if (match[1] !== undefined) value.pid = Number(match[1]);
  for (const part of parts) {
    const [key, raw] = part.split('=');
    if (Object.hasOwn(value, key)) return null;
    value[key] = key === 'event' ? raw : Number(raw);
  }
  return validateMetric(value);
}

export function parseBattery(text) {
  const lines = text.trim().split(/\r?\n/);
  if (lines.shift() !== BATTERY_HEADER) throw new MeasurementError('Battery CSV header does not match the safe schema.');
  const rows = [];
  let rejected = 0;
  for (const line of lines) {
    const fields = line.split(',');
    if (fields.length !== 6 || !/^\d+$/.test(fields[0]) || !/^\d+$/.test(fields[1]) ||
        !['screen_on', 'screen_off', 'service_start'].includes(fields[2]) || !/^\d+$/.test(fields[3]) ||
        !['true', 'false'].includes(fields[4]) || !['true', 'false'].includes(fields[5])) {
      rejected++;
      continue;
    }
    const row = {
      wall_ms: Number(fields[0]), elapsed_ms: Number(fields[1]), event: fields[2],
      level: Number(fields[3]), plugged: fields[4] === 'true', wifi: fields[5] === 'true',
    };
    if (![row.wall_ms, row.elapsed_ms, row.level].every(integer) || row.level > 100) { rejected++; continue; }
    rows.push(row);
  }
  return { rows, rejected };
}

export function summarizeBattery(parsed, { minHours = 8, unpluggedAttested = false } = {}) {
  const intervals = [];
  const exclusions = { plugged: 0, short: 0, clock_or_reboot: 0, level_increased: 0, unbracketed: 0 };
  // Rejected rows break adjacency. Do not bridge a malformed record with a convenient interval.
  if (parsed.rejected) return {
    status: 'invalid_rows', rejected_rows: parsed.rejected, intervals: [], exclusions,
    hours: 0, drop_percentage_points: 0, drain_percentage_points_per_hour: null,
    continuous_unplugged_attested: unpluggedAttested, min_hours: minHours,
  };
  for (let i = 0; i + 1 < parsed.rows.length; i++) {
    const a = parsed.rows[i], b = parsed.rows[i + 1];
    if (a.event !== 'screen_off') continue;
    if (b.event !== 'screen_on') { exclusions.unbracketed++; continue; }
    if (a.plugged || b.plugged) { exclusions.plugged++; continue; }
    const elapsed = b.elapsed_ms - a.elapsed_ms;
    const wall = b.wall_ms - a.wall_ms;
    if (elapsed <= 0 || wall <= 0 || Math.abs(wall - elapsed) > 120000) { exclusions.clock_or_reboot++; continue; }
    const hours = elapsed / 3600000;
    if (hours < minHours) { exclusions.short++; continue; }
    if (b.level > a.level) { exclusions.level_increased++; continue; }
    intervals.push({ start_wall_ms: a.wall_ms, end_wall_ms: b.wall_ms, hours,
      start_level: a.level, end_level: b.level, drop_percentage_points: a.level - b.level,
      wifi_at_sleep: a.wifi, wifi_at_wake: b.wifi });
  }
  const hours = intervals.reduce((sum, value) => sum + value.hours, 0);
  const drop = intervals.reduce((sum, value) => sum + value.drop_percentage_points, 0);
  return {
    status: !intervals.length ? 'pending' : unpluggedAttested ? 'observed_with_human_attestation' : 'candidate_endpoints_only',
    rejected_rows: parsed.rejected, min_hours: minHours, continuous_unplugged_attested: unpluggedAttested,
    intervals, exclusions, hours, drop_percentage_points: drop,
    drain_percentage_points_per_hour: hours ? drop / hours : null,
  };
}

const first = (events, name) => events.find((event) => event.event === name);
const duration = (end, start) => end && start && end.t_ms >= start.t_ms ? end.t_ms - start.t_ms : null;
function distribution(values) {
  const measured = values.filter((value) => value !== null).sort((a, b) => a - b);
  const percentile = (p) => measured.length ? measured[Math.ceil(measured.length * p) - 1] : null;
  return { n: measured.length, p50_ms: percentile(0.5), p95_ms: percentile(0.95), max_ms: measured.at(-1) ?? null };
}

export function analyzeMetrics(metrics) {
  const turns = new Map(), connections = new Map(), waits = [], services = [];
  const lifecycle = new Map();
  let rejected = 0;
  for (const raw of metrics) {
    const event = validateMetric(raw);
    if (!event || event.pid === undefined) { rejected++; continue; }
    // A PID is retained by -v brief. Service starts split lifetimes even if Android reuses a PID.
    // Unidentified logs cannot support cross-turn/session correlations.
    const pid = event.pid;
    let life = lifecycle.get(pid);
    if (!life || event.event === 'service_started' || event.t_ms < life.lastTime) {
      life = { generation: (life?.generation ?? 0) + 1, lastTime: event.t_ms, waits: [] };
      lifecycle.set(pid, life);
    }
    life.lastTime = Math.max(life.lastTime, event.t_ms);
    const scope = `${pid}:${life.generation}`;
    const scoped = { ...event, scope };
    if (event.event.startsWith('service_')) services.push(scoped);
    if (event.event === 'network_wait') life.waits.push(scoped);
    const waitIndex = event.event === 'network_available' ? life.waits.findIndex((wait) =>
      wait.turn === event.turn && (wait.connection === undefined || event.connection === undefined || wait.connection === event.connection)) : -1;
    if (waitIndex !== -1) {
      const start = life.waits.splice(waitIndex, 1)[0];
      waits.push({ scope, turn: start.turn, connection: start.connection ?? null,
        wait_ms: duration(scoped, start) });
    }
    if (event.connection !== undefined) {
      const key = `${scope}:${event.connection}`;
      if (!connections.has(key)) connections.set(key, []);
      connections.get(key).push(scoped);
    }
    if (event.turn > 0) {
      const key = `${scope}:${event.turn}`;
      if (!turns.has(key)) turns.set(key, []);
      turns.get(key).push(scoped);
    }
  }
  const rows = [];
  for (const events of turns.values()) {
    const start = first(events, 'turn_start');
    if (!start) continue; // Do not invent a turn from an orphan/stale callback.
    const release = first(events, 'release');
    const playback = first(events, 'playback_started');
    // Cold turn_start may precede session allocation (connection=0). Resolve only from a
    // single positive ID present on that same turn; never borrow a nearby turn's session.
    const turnConnections = new Set(events.filter((event) => event.connection > 0).map((event) => event.connection));
    const connection = start.connection > 0 ? start.connection
      : turnConnections.size === 1 ? [...turnConnections][0] : null;
    const connectionEvents = connection !== null ? connections.get(`${start.scope}:${connection}`) ?? [] : [];
    const connectStart = first(connectionEvents, 'connect_start');
    const ready = first(connectionEvents, 'connect_ready');
    const interrupted = first(events, 'interrupt_start');
    const flush = events.find((event) => event.event === 'playback_flushed' && (!interrupted || event.t_ms >= interrupted.t_ms));
    rows.push({ scope: start.scope, turn: start.turn,
      // 0 was the retired live speech-to-speech mode; 1 is any transcribed press (a turn for Hermes or dictation).
      mode: ['live', 'voice', 'typed'][start.mode] ?? 'unknown',
      connection_state: start.warm === 1 ? 'warm' : start.warm === 0 ? 'cold' : 'unknown',
      connection,
      connection_ms: duration(ready, connectStart),
      readiness_after_turn_start_ms: start.ready === 1 ? 0 : duration(ready, start),
      max_queue_age_ms: events.reduce((maximum, event) => event.event !== 'input_end_sent' || event.max_queue_ms === undefined ? maximum
        : Math.max(maximum ?? 0, event.max_queue_ms), null),
      queued_bytes_at_input_end: first(events, 'input_end_sent')?.queued_bytes ?? null,
      release_gate_close_ms: duration(first(events, 'release_gate_closed'), release),
      release_to_input_end_ms: duration(first(events, 'input_end_sent'), release),
      release_to_first_reply_received_ms: duration(first(events, 'first_reply'), release),
      release_to_playback_head_ms: duration(playback, release),
      playback_before_release: playback && release ? playback.t_ms < release.t_ms : null,
      interrupt_local_flush_ms: duration(flush, interrupted),
      interrupt_audible_silence_ms: null,
      failed: events.some((event) => event.event === 'session_failed'),
      exchange_complete: events.some((event) => event.event === 'exchange_complete'),
    });
  }
  const summary = {};
  const measuredFields = ['connection_ms', 'readiness_after_turn_start_ms', 'max_queue_age_ms',
    'release_gate_close_ms', 'release_to_input_end_ms', 'release_to_first_reply_received_ms',
    'release_to_playback_head_ms', 'interrupt_local_flush_ms'];
  const cohorts = new Set(rows.map((row) => `${row.mode}/${row.connection_state}`));
  for (const cohort of cohorts) {
    const matching = rows.filter((row) => `${row.mode}/${row.connection_state}` === cohort);
    if (!matching.length) continue;
    summary[cohort] = { turns: matching.length,
      failed: matching.filter((row) => row.failed).length,
      completed: matching.filter((row) => row.exchange_complete).length,
      ...Object.fromEntries(measuredFields.map((name) => [name, distribution(matching.map((row) => row[name]))])) };
  }
  return { schema: 1, status: rows.length ? 'telemetry_observed' : 'pending', rejected_records: rejected,
    turns: rows, summary, network_waits: waits,
    services: services.map(({ event, t_ms, scope }) => ({ event, t_ms, scope })),
    pending: ['Acoustic reply onset and interruption silence require an external microphone/video clock.',
      'Network-loss onset, workload correctness, and service restart actions require human observations.',
      'A playback head marker samples AudioTrack progress; it does not prove speaker acoustic onset.'] };
}

function options(args) {
  const result = {};
  for (let i = 0; i < args.length; i++) {
    if (!args[i].startsWith('--') || Object.hasOwn(result, args[i].slice(2))) throw new MeasurementError('Use unique named options.');
    const key = args[i].slice(2);
    if (['with-battery', 'attest-unplugged'].includes(key)) result[key] = true;
    else {
      if (!args[i + 1] || args[i + 1].startsWith('--')) throw new MeasurementError('Missing option value.');
      result[key] = args[++i];
    }
  }
  return result;
}
function only(args, names) {
  if (Object.keys(args).some((key) => !names.includes(key))) throw new MeasurementError('Unknown option; see --help.');
}
function writeJson(destination, value) {
  fs.writeFileSync(destination, `${JSON.stringify(value, null, 2)}\n`, { mode: 0o600 });
}
function readMetrics(filename) {
  return fs.readFileSync(filename, 'utf8').split(/\r?\n/).filter(Boolean).map((line) => {
    try { return JSON.parse(line); } catch { return null; }
  });
}
function readManifest(filename) {
  const manifest = JSON.parse(fs.readFileSync(filename, 'utf8'));
  if (manifest.schema !== 1 || !SCENARIOS.has(manifest.scenario) || !label(manifest.config_label) ||
      !label(manifest.build_label) || !/^[a-f0-9]{64}$/.test(manifest.device_id_sha256) ||
      typeof manifest.completed !== 'boolean') throw new MeasurementError('Invalid run manifest.');
  // Never echo unexpected input fields from a manually edited manifest.
  return { schema: 1, scenario: manifest.scenario, config_label: manifest.config_label,
    build_label: manifest.build_label, device_id_sha256: manifest.device_id_sha256,
    capture_completed: manifest.completed };
}

async function capture(args) {
  only(args, ['serial', 'expect-model', 'scenario', 'config-label', 'build-label', 'seconds', 'out', 'with-battery']);
  if (!args.serial || !/^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/.test(args.serial)) throw new MeasurementError('Explicit --serial is required.');
  if (!args['expect-model'] || !/^[A-Za-z0-9][A-Za-z0-9 ._-]{0,127}$/.test(args['expect-model'])) throw new MeasurementError('Explicit --expect-model is required.');
  if (!SCENARIOS.has(args.scenario) || !label(args['config-label']) || !label(args['build-label'])) {
    throw new MeasurementError('Supply --scenario, --config-label, and --build-label (safe labels, no config files).');
  }
  const seconds = Number(args.seconds ?? 60);
  if (!integer(seconds) || seconds < 1 || seconds > 3600) throw new MeasurementError('--seconds must be an integer from 1 to 3600.');
  const adb = (...command) => {
    try { return execFileSync('adb', ['-s', args.serial, ...command], { encoding: 'utf8', timeout: 10000,
      maxBuffer: 1024 * 1024, stdio: ['ignore', 'pipe', 'pipe'] }).trim(); }
    catch { throw new MeasurementError('Read-only ADB verification/read failed; check the selected device manually.'); }
  };
  if (adb('get-state') !== 'device') throw new MeasurementError('Selected serial is not in the ADB device state.');
  if (adb('shell', 'getprop', 'ro.product.model') !== args['expect-model']) throw new MeasurementError('Device model does not exactly match --expect-model.');
  const packageLocation = adb('shell', 'pm', 'path', 'dev.r1ptt');
  if (!/^package:\S+\.apk(?:\r?\npackage:\S+\.apk)*$/.test(packageLocation)) throw new MeasurementError('dev.r1ptt is not installed on the verified target.');
  const uptime = adb('shell', 'cat', '/proc/uptime').split(/\s+/)[0];
  if (!/^\d+(?:\.\d+)?$/.test(uptime)) throw new MeasurementError('Cannot establish the device monotonic capture boundary.');
  const startMs = Math.ceil(Number(uptime) * 1000);
  if (!integer(startMs)) throw new MeasurementError('Invalid device monotonic capture boundary.');
  const root = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
  const directory = path.resolve(args.out ?? path.join(root, 'out', 'acceptance',
    `${new Date().toISOString().replace(/[:.]/g, '-')}-${crypto.randomBytes(3).toString('hex')}`));
  fs.mkdirSync(path.dirname(directory), { recursive: true, mode: 0o700 });
  fs.mkdirSync(directory, { mode: 0o700 }); // Refuse to overwrite an existing evidence run.
  const manifest = { schema: 1, scenario: args.scenario, config_label: args['config-label'],
    build_label: args['build-label'], device_id_sha256: crypto.createHash('sha256').update(args.serial).digest('hex'),
    capture_start_t_ms: startMs, requested_seconds: seconds, captured_records: 0, completed: false,
    target_model_verified: true, source: 'adb_read_only_structured_metrics' };
  writeJson(path.join(directory, 'run.json'), manifest);
  const fd = fs.openSync(path.join(directory, 'telemetry.jsonl'), 'wx', 0o600);
  const stream = spawn('adb', ['-s', args.serial, 'logcat', '-v', 'brief', '-T', '1', 'r1ptt:I', '*:S'],
    { stdio: ['ignore', 'pipe', 'ignore'] });
  let buffer = '', earlyExit = false, timedOut = false, signalled = false;
  const saveLine = (line) => {
    const metric = parseMetricLine(line.replace(/\r$/, ''));
    if (metric && metric.t_ms >= startMs && metric.pid !== undefined) {
      fs.writeSync(fd, `${JSON.stringify(metric)}\n`);
      manifest.captured_records++;
    }
  };
  stream.stdout.setEncoding('utf8');
  stream.stdout.on('data', (chunk) => {
    buffer += chunk;
    let end;
    while ((end = buffer.indexOf('\n')) !== -1) { saveLine(buffer.slice(0, end)); buffer = buffer.slice(end + 1); }
    if (buffer.length > 65536) buffer = ''; // Discard oversized/non-telemetry input without persisting it.
  });
  const timer = setTimeout(() => { timedOut = true; stream.kill('SIGTERM'); }, seconds * 1000);
  const stop = () => { signalled = true; stream.kill('SIGTERM'); };
  process.once('SIGINT', stop);
  process.once('SIGTERM', stop);
  process.stdout.write(`Verified target; collecting safe telemetry for ${seconds}s. Perform the documented workload by hand.\n`);
  await new Promise((resolve) => {
    stream.once('error', () => { earlyExit = true; resolve(); });
    stream.once('close', () => { if (!timedOut && !signalled) earlyExit = true; resolve(); });
  });
  clearTimeout(timer);
  process.removeListener('SIGINT', stop);
  process.removeListener('SIGTERM', stop);
  if (buffer) saveLine(buffer);
  fs.closeSync(fd);
  manifest.completed = timedOut && !earlyExit;
  manifest.stopped_by_operator = signalled;
  writeJson(path.join(directory, 'run.json'), manifest);
  if (args['with-battery']) {
    if (adb('shell', 'getprop', 'ro.product.model') !== args['expect-model']) throw new MeasurementError('Device identity changed before battery collection.');
    const parsed = parseBattery(adb('shell', "su -c 'cat /data/data/dev.r1ptt/files/battery.csv'"));
    if (parsed.rejected) throw new MeasurementError('Battery snapshot contains invalid rows; no battery file was saved.');
    const safe = parsed.rows.map((r) => `${r.wall_ms},${r.elapsed_ms},${r.event},${r.level},${r.plugged},${r.wifi}`);
    fs.writeFileSync(path.join(directory, 'battery.csv'), `${BATTERY_HEADER}\n${safe.join('\n')}${safe.length ? '\n' : ''}`, { mode: 0o600 });
  }
  process.stdout.write(`Saved ${manifest.captured_records} structured records to ${directory}\n`);
  if (earlyExit) throw new MeasurementError('Telemetry stream ended before the requested duration; the run is marked incomplete.');
}

export async function main(argv) {
  const [command, ...rest] = argv;
  if (!command || command === '--help' || (rest.length === 1 && rest[0] === '--help')) {
    process.stdout.write(`Read-only capture (human workload):\n  tools/acceptance-capture.sh --serial SERIAL --expect-model MODEL --scenario voice_cold --config-label baseline --build-label COMMIT [--seconds 60] [--out NEW_DIR] [--with-battery]\nOffline report:\n  tools/acceptance-report.sh --run RUN_DIR [--attest-unplugged] [--min-hours 8]\nOffline battery analysis:\n  node tools/acceptance.mjs battery --input SAFE_BATTERY_CSV --config-label LABEL [--min-hours 8] [--attest-unplugged]\nSee docs/ACCEPTANCE.md. No capture action clears logs, changes settings, sends input, starts providers, installs, or flashes.\n`);
    return;
  }
  const args = options(rest);
  if (command === 'capture') return capture(args);
  const minHours = Number(args['min-hours'] ?? 8);
  if (!Number.isFinite(minHours) || minHours < 0.5 || minHours > 168) throw new MeasurementError('--min-hours must be between 0.5 and 168.');
  if (command === 'battery') {
    only(args, ['input', 'config-label', 'min-hours', 'attest-unplugged']);
    if (!args.input || !label(args['config-label'])) throw new MeasurementError('Supply --input and a safe --config-label.');
    process.stdout.write(`${JSON.stringify({ config_label: args['config-label'],
      ...summarizeBattery(parseBattery(fs.readFileSync(args.input, 'utf8')),
        { minHours, unpluggedAttested: args['attest-unplugged'] === true }) }, null, 2)}\n`);
    return;
  }
  if (command === 'report') {
    only(args, ['run', 'min-hours', 'attest-unplugged']);
    if (!args.run) throw new MeasurementError('Supply --run.');
    const directory = path.resolve(args.run);
    const manifest = readManifest(path.join(directory, 'run.json'));
    const report = { run: manifest, ...analyzeMetrics(readMetrics(path.join(directory, 'telemetry.jsonl'))) };
    const batteryFile = path.join(directory, 'battery.csv');
    if (fs.existsSync(batteryFile)) report.battery = summarizeBattery(parseBattery(fs.readFileSync(batteryFile, 'utf8')),
      { minHours, unpluggedAttested: args['attest-unplugged'] === true });
    writeJson(path.join(directory, 'report.json'), report);
    const fields = TURN_FIELDS;
    fs.writeFileSync(path.join(directory, 'turns.csv'), `${fields.join(',')}\n${report.turns.map((row) => fields.map((key) => row[key] ?? '').join(',')).join('\n')}${report.turns.length ? '\n' : ''}`, { mode: 0o600 });
    process.stdout.write(`Offline report: ${path.join(directory, 'report.json')}\nTurns: ${report.turns.length}; acoustic results remain pending.\n`);
    return;
  }
  throw new MeasurementError('Unknown command; see --help.');
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main(process.argv.slice(2)).catch((error) => {
    // Error messages are ours, not raw ADB/provider output or input records.
    const safe = error instanceof MeasurementError ? error.message : 'Measurement command failed; inspect local file access and tool availability.';
    process.stderr.write(`${safe}\n`);
    process.exitCode = 1;
  });
}
