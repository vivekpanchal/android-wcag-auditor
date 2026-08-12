// SQLite-backed issue store. Persists across restarts (see server/data.db,
// gitignored). Path overridable via A11Y_DB_PATH for test isolation.
'use strict';

const path = require('node:path');
const { DatabaseSync } = require('node:sqlite');

const dbPath = process.env.A11Y_DB_PATH || path.join(__dirname, '..', 'data.db');
const db = new DatabaseSync(dbPath);

db.exec(`
  CREATE TABLE IF NOT EXISTS issues (
    id INTEGER PRIMARY KEY,
    packageName TEXT,
    screen TEXT,
    timestamp INTEGER,
    severity TEXT,
    wcagSC TEXT,
    wcagLevel TEXT,
    elementDescription TEXT,
    description TEXT,
    suggestedFix TEXT,
    screenshot TEXT,
    bounds TEXT
  );
  CREATE TABLE IF NOT EXISTS state (
    key TEXT PRIMARY KEY,
    value TEXT
  );
`);

// control/appList/deviceLastSeen/nextId are singletons -- one row each in
// `state`, keyed by name, JSON-encoded. Not worth their own tables.
const getStateStmt = db.prepare('SELECT value FROM state WHERE key = ?');
function getState(key, fallback) {
  const row = getStateStmt.get(key);
  if (!row) return fallback;
  try {
    return JSON.parse(row.value);
  } catch {
    // Corrupted/truncated row (manual edit, crash mid-write) -- treat as
    // missing rather than taking the whole server down on the next read.
    return fallback;
  }
}

const setStateStmt = db.prepare(
  'INSERT INTO state (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value'
);
function setState(key, value) {
  setStateStmt.run(key, JSON.stringify(value));
}

// Reserves a contiguous block of `count` ids in one read+write instead of
// one round trip per issue -- avoids advancing nextId ahead of ids that
// might still fail to insert.
function reserveIdBlock(count) {
  const start = getState('nextId', 1);
  setState('nextId', start + count);
  return start;
}

const insertIssueStmt = db.prepare(`
  INSERT INTO issues (id, packageName, screen, timestamp, severity, wcagSC, wcagLevel, elementDescription, description, suggestedFix, screenshot, bounds)
  VALUES (:id, :packageName, :screen, :timestamp, :severity, :wcagSC, :wcagLevel, :elementDescription, :description, :suggestedFix, :screenshot, :bounds)
`);

function rowToIssue(row) {
  if (!row.bounds) return { ...row, bounds: null };
  try {
    return { ...row, bounds: JSON.parse(row.bounds) };
  } catch {
    return { ...row, bounds: null };
  }
}

function touchDevice() {
  setState('deviceLastSeen', Date.now());
}

function getDeviceLastSeen() {
  return getState('deviceLastSeen', null);
}

function addReport(report) {
  const { packageName, screen, timestamp, screenshot, issues: reportIssues = [] } = report;
  if (reportIssues.length === 0) return [];

  const startId = reserveIdBlock(reportIssues.length);
  const stored = reportIssues.map((issue, i) => ({
    id: startId + i,
    packageName: packageName ?? null,
    screen: screen ?? null,
    timestamp: timestamp ?? null,
    severity: issue.severity ?? null,
    wcagSC: issue.wcagSC ?? null,
    wcagLevel: issue.wcagLevel ?? null,
    elementDescription: issue.elementDescription ?? null,
    description: issue.description ?? null,
    suggestedFix: issue.suggestedFix ?? null,
    screenshot: screenshot || null,
    bounds: issue.bounds || null,
  }));

  db.exec('BEGIN');
  try {
    for (const row of stored) {
      insertIssueStmt.run({ ...row, bounds: row.bounds ? JSON.stringify(row.bounds) : null });
    }
    db.exec('COMMIT');
  } catch (e) {
    db.exec('ROLLBACK');
    throw e;
  }
  return stored;
}

const getIssuesStmt = db.prepare('SELECT * FROM issues ORDER BY id');
function getIssues() {
  return getIssuesStmt.all().map(rowToIssue);
}

function clearIssues() {
  db.exec('DELETE FROM issues');
  setState('nextId', 1);
}

function getControl() {
  return getState('control', { targetPackage: null, auditing: false, updatedAt: null });
}

function setControl(patch) {
  const control = { ...getControl(), ...patch, updatedAt: Date.now() };
  setState('control', control);
  return control;
}

function getAppList() {
  return getState('appList', []);
}

function setAppList(apps) {
  setState('appList', apps);
  return apps;
}

function close() {
  db.close();
}

module.exports = {
  addReport, getIssues, clearIssues,
  getControl, setControl,
  getAppList, setAppList,
  touchDevice, getDeviceLastSeen,
  close,
};
