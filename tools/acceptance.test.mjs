import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { analyzeMetrics, parseBattery, parseMetricLine, summarizeBattery, validateMetric } from './acceptance.mjs';

const toolDirectory = path.dirname(fileURLToPath(import.meta.url));
const fixture = (name) => fs.readFileSync(path.join(toolDirectory, 'fixtures', 'acceptance', name), 'utf8');
const metric = (event, t_ms, turn = 1, extra = {}) => ({ schema: 1, pid: 42, event, t_ms, turn, ...extra });
function sandbox(t) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'r1ptt-acceptance-test-'));
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  return directory;
}

test('only the exact INFO telemetry grammar is retained', () => {
  assert.deepEqual(parseMetricLine('I/r1ptt  ( 42): metric event=turn_start t_ms=100 turn=1 mode=0 warm=1 ready=0 connection=7'),
    metric('turn_start', 100, 1, { mode: 0, warm: 1, ready: 0, connection: 7 }));
  for (const line of [
    'I/other(42): metric event=release t_ms=100 turn=1',
    'W/r1ptt(42): metric event=release t_ms=100 turn=1',
    'I/r1ptt(42): live: transcript=FAKE_DO_NOT_SAVE',
    'I/r1ptt(42): metric event=release t_ms=100 turn=1 apiKey=FAKE_DO_NOT_SAVE',
    'I/r1ptt(42): metric event=release t_ms=100 turn=1 endpoint=123',
    'I/r1ptt(42): metric event=release t_ms=100 turn=1 bytes=123',
    'I/r1ptt(42): metric event=unknown t_ms=100 turn=1',
    'I/r1ptt(42): metric event=release t_ms=100 turn=1 turn=2',
    'I/r1ptt(42): metric event=release t_ms=9007199254740992 turn=1',
    'I/r1ptt(42): metric event=turn_start t_ms=100 turn=1 warm=2',
    'I/r1ptt(42): metric event=turn_start t_ms=100 turn=1 mode=3',
  ]) assert.equal(parseMetricLine(line), null);
  assert.equal(validateMetric({ ...metric('release', 100), transcript: 'FAKE_DO_NOT_SAVE' }), null);
});

test('cold, warm, dictation, and restarted process fixtures have separate metrics', () => {
  const report = analyzeMetrics(fixture('telemetry.jsonl').trim().split('\n').map(JSON.parse));
  assert.equal(report.turns.length, 4);
  const [cold, warm, dictation, restarted] = report.turns;
  assert.equal(cold.connection_ms, 200);
  assert.equal(cold.readiness_after_turn_start_ms, 220);
  assert.equal(cold.max_queue_age_ms, 200);
  assert.equal(cold.release_gate_close_ms, 4);
  assert.equal(cold.release_to_first_reply_received_ms, 300);
  assert.equal(cold.release_to_playback_head_ms, 350);
  assert.equal(warm.readiness_after_turn_start_ms, 0);
  assert.equal(warm.connection_ms, 200); // Original connection establishment, not a warm-turn reconnect.
  assert.equal(warm.interrupt_local_flush_ms, 3);
  assert.equal(warm.interrupt_audible_silence_ms, null);
  assert.equal(dictation.mode, 'voice');
  assert.equal(dictation.release_to_playback_head_ms, null);
  assert.equal(restarted.connection_ms, null); // Connection 7 in another PID is unrelated.
  assert.equal(restarted.failed, true);
  assert.deepEqual(report.network_waits.map((wait) => wait.wait_ms), [180]);
  assert.equal(report.summary['live/cold'].release_to_playback_head_ms.n, 1);
  assert.equal(report.summary['live/warm'].interrupt_local_flush_ms.p95_ms, 3);
});

test('missing, stale, mismatched, and rebooted markers never invent a duration', () => {
  const report = analyzeMetrics([
    metric('connect_start', 10, 0, { connection: 1 }),
    metric('connect_ready', 20, 0, { connection: 1 }),
    metric('turn_start', 30, 1, { connection: 1, mode: 0, ready: 1 }),
    metric('service_started', 40, 0),
    metric('turn_start', 50, 1, { connection: 1, mode: 0, ready: 0 }),
    metric('playback_started', 60),
    metric('network_wait', 70, 1, { connection: 1 }),
    metric('network_available', 80, 2, { connection: 2 }),
    metric('connect_ready', 5, 1, { connection: 1 }), // Uptime regressed (new lifetime).
    { invalid: true },
  ]);
  assert.equal(report.turns.length, 2);
  assert.equal(report.turns[0].connection_ms, 10);
  assert.equal(report.turns[1].connection_ms, null);
  assert.equal(report.turns[1].readiness_after_turn_start_ms, null);
  assert.equal(report.turns[1].release_to_playback_head_ms, null);
  assert.equal(report.turns[1].playback_before_release, null);
  assert.equal(report.network_waits.length, 0);
  assert.equal(report.rejected_records, 1);
  assert.equal(analyzeMetrics([{ schema: 1, event: 'turn_start', t_ms: 1, turn: 1 }]).turns.length, 0);
});

test('release ordering detects playback before release and keeps negative latency pending', () => {
  const report = analyzeMetrics([
    metric('turn_start', 1), metric('playback_started', 5), metric('release', 10),
  ]);
  assert.equal(report.turns[0].playback_before_release, true);
  assert.equal(report.turns[0].release_to_playback_head_ms, null);
});

test('unassigned cold connection resolves from only an unambiguous same-turn ID', () => {
  const one = analyzeMetrics([
    metric('turn_start', 1, 1, { connection: 0, mode: 0, warm: 0, ready: 0 }),
    metric('connect_start', 2, 1, { connection: 8 }),
    metric('connect_ready', 12, 1, { connection: 8 }),
  ]).turns[0];
  assert.equal(one.connection, 8);
  assert.equal(one.connection_ms, 10);
  assert.equal(one.readiness_after_turn_start_ms, 11);
  const ambiguous = analyzeMetrics([
    metric('turn_start', 1, 1, { connection: 0 }),
    metric('connect_start', 2, 1, { connection: 8 }),
    metric('connect_start', 3, 1, { connection: 9 }),
    metric('connect_ready', 12, 1, { connection: 8 }),
  ]).turns[0];
  assert.equal(ambiguous.connection, null);
  assert.equal(ambiguous.connection_ms, null);
});

test('queue age requires input-end markers and ignores counters from connection events', () => {
  const prefix = [metric('turn_start', 1), metric('connect_ready', 2, 1, { max_queue_ms: 900 })];
  assert.equal(analyzeMetrics(prefix).turns[0].max_queue_age_ms, null);
  assert.equal(analyzeMetrics([...prefix, metric('input_end_sent', 3, 1, { max_queue_ms: 0 })])
    .turns[0].max_queue_age_ms, 0);
  assert.equal(analyzeMetrics([...prefix,
    metric('input_end_sent', 3, 1, { max_queue_ms: 20 }),
    metric('input_end_sent', 4, 1, { max_queue_ms: 30 }),
  ]).turns[0].max_queue_age_ms, 30);
});

test('battery intervals require brackets, monotonic clocks, valid levels and unplugged attestation', () => {
  const parsed = parseBattery(fixture('battery.csv'));
  assert.equal(parsed.rejected, 0);
  assert.equal(parsed.rows[0].event, 'service_start');
  const candidate = summarizeBattery(parsed);
  assert.equal(candidate.status, 'candidate_endpoints_only');
  assert.equal(candidate.hours, 8);
  assert.equal(candidate.drain_percentage_points_per_hour, 1);
  assert.equal(candidate.exclusions.plugged, 1);
  assert.equal(candidate.exclusions.short, 1);
  assert.equal(candidate.exclusions.unbracketed, 1); // Service restart breaks an eight-hour span.
  assert.equal(summarizeBattery(parsed, { unpluggedAttested: true }).status, 'observed_with_human_attestation');
  const firstOff = parsed.rows.findIndex((row) => row.event === 'screen_off');
  const off = parsed.rows[firstOff];
  const on = parsed.rows[firstOff + 1];
  assert.equal(summarizeBattery({ rows: [off, { ...on, elapsed_ms: 10 }], rejected: 0 }).intervals.length, 0);
  assert.equal(summarizeBattery({ rows: [off, { ...on, level: 100 }], rejected: 0 }).intervals.length, 0);
  assert.equal(summarizeBattery({ rows: [off, { ...on, event: 'screen_off' }], rejected: 0 }).intervals.length, 0);
  assert.equal(summarizeBattery({ rows: [off, { ...off, event: 'service_start' }, on], rejected: 0 }).intervals.length, 0);
  assert.equal(summarizeBattery({ rows: [off, on], rejected: 1 }).status, 'invalid_rows');
  assert.equal(parseBattery(`${fixture('battery.csv')}1,2,FAKE_DO_NOT_SAVE,3,false,true\n`).rejected, 1);
  assert.throws(() => parseBattery('apiKey,FAKE_DO_NOT_SAVE'), /safe schema/);
});

function mockAdb(t, { wrongModel = false, missingPackage = false } = {}) {
  const directory = sandbox(t);
  const commandLog = path.join(directory, 'commands.jsonl');
  const script = `#!${process.execPath}\nimport fs from 'node:fs';
const args = process.argv.slice(2);
fs.appendFileSync(process.env.MOCK_ADB_COMMAND_LOG, JSON.stringify(args) + '\\n');
if (args[0] !== '-s' || args[1] !== 'fixture-serial') process.exit(10);
const command = args.slice(2).join(' ');
if (command === 'get-state') console.log('device');
else if (command === 'shell getprop ro.product.model') console.log('${wrongModel ? 'wrong' : 'R1'}');
else if (command === 'shell pm path dev.r1ptt') console.log('${missingPackage ? '' : 'package:/data/app/dev.r1ptt/base.apk'}');
else if (command === 'shell cat /proc/uptime') console.log('100.0 20.0');
else if (command === "shell su -c 'cat /data/data/dev.r1ptt/files/battery.csv'") console.log(${JSON.stringify(fixture('battery.csv'))});
else if (command === 'logcat -v brief -T 1 r1ptt:I *:S') {
  console.log('I/r1ptt(42): live: transcript=FAKE_DO_NOT_SAVE');
  console.log('I/r1ptt(42): metric event=release t_ms=99999 turn=1');
  console.log('I/r1ptt(42): metric event=turn_start t_ms=100001 turn=1 mode=0 warm=0 ready=0 connection=7');
  console.log('I/r1ptt(42): metric event=release t_ms=100100 turn=1 endpoint=123');
  console.log('I/r1ptt(42): metric event=release t_ms=100101 turn=1');
  setInterval(() => {}, 1000);
} else process.exit(11);
`;
  // A package.json marks this extensionless mock executable as ESM. No actual ADB is called.
  fs.writeFileSync(path.join(directory, 'package.json'), '{"type":"module"}');
  fs.writeFileSync(path.join(directory, 'adb'), script, { mode: 0o700 });
  return { directory, commandLog, env: { ...process.env, PATH: `${directory}:${process.env.PATH}`,
    MOCK_ADB_COMMAND_LOG: commandLog } };
}
function runCapture(mock, output, extra = []) {
  return spawnSync(process.execPath, [path.join(toolDirectory, 'acceptance.mjs'), 'capture',
    '--serial', 'fixture-serial', '--expect-model', 'R1', '--scenario', 'voice_cold',
    '--config-label', 'fixture', '--build-label', 'fixture', '--seconds', '1', '--out', output, ...extra],
  { env: mock.env, encoding: 'utf8', timeout: 10000 });
}

test('capture mock proves explicit target verification, read-only commands and no raw log persistence', (t) => {
  const mock = mockAdb(t);
  const output = path.join(mock.directory, 'run');
  const result = runCapture(mock, output, ['--with-battery']);
  assert.equal(result.status, 0, result.stderr);
  const manifest = JSON.parse(fs.readFileSync(path.join(output, 'run.json')));
  assert.equal(manifest.completed, true);
  assert.equal(manifest.captured_records, 2);
  assert.equal(manifest.device_id_sha256.length, 64);
  const allEvidence = fs.readdirSync(output).map((name) => fs.readFileSync(path.join(output, name), 'utf8')).join('\n');
  assert.ok(!allEvidence.includes('FAKE_DO_NOT_SAVE'));
  assert.ok(!allEvidence.includes('fixture-serial'));
  assert.ok(!allEvidence.includes('endpoint'));
  assert.ok(fs.readFileSync(path.join(output, 'telemetry.jsonl'), 'utf8').trim().split('\n')
    .map(JSON.parse).every((record) => record.t_ms >= 100000));
  const commands = fs.readFileSync(mock.commandLog, 'utf8').trim().split('\n').map(JSON.parse);
  assert.ok(commands.every((args) => args[0] === '-s' && args[1] === 'fixture-serial'));
  assert.deepEqual(commands.map((args) => args.slice(2).join(' ')), [
    'get-state', 'shell getprop ro.product.model', 'shell pm path dev.r1ptt', 'shell cat /proc/uptime',
    'logcat -v brief -T 1 r1ptt:I *:S', 'shell getprop ro.product.model',
    "shell su -c 'cat /data/data/dev.r1ptt/files/battery.csv'",
  ]);
  const report = spawnSync(process.execPath, [path.join(toolDirectory, 'acceptance.mjs'), 'report', '--run', output],
    { encoding: 'utf8', timeout: 5000 });
  assert.equal(report.status, 0, report.stderr);
  assert.equal(JSON.parse(fs.readFileSync(path.join(output, 'report.json'))).turns.length, 1);
});

test('wrong target or absent package stops before capture and evidence creation', (t) => {
  for (const settings of [{ wrongModel: true }, { missingPackage: true }]) {
    const mock = mockAdb(t, settings);
    const output = path.join(mock.directory, 'run');
    assert.equal(runCapture(mock, output).status, 1);
    assert.equal(fs.existsSync(output), false);
    assert.ok(!fs.readFileSync(mock.commandLog, 'utf8').includes('logcat'));
  }
});

test('invalid offline input never leaks parser contents in diagnostics', (t) => {
  const directory = sandbox(t);
  fs.writeFileSync(path.join(directory, 'run.json'), 'FAKE_DO_NOT_SAVE');
  const result = spawnSync(process.execPath, [path.join(toolDirectory, 'acceptance.mjs'), 'report', '--run', directory],
    { encoding: 'utf8', timeout: 5000 });
  assert.equal(result.status, 1);
  assert.ok(!`${result.stdout}${result.stderr}`.includes('FAKE_DO_NOT_SAVE'));
});
