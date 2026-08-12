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
  return row ? JSON.parse(row.value) : fallback;
}

const setStateStmt = db.prepare(
  'INSERT INTO state (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value'
);
function setState(key, value) {
  setStateStmt.run(key, JSON.stringify(value));
}

function takeNextId() {
  const id = getState('nextId', 1);
  setState('nextId', id + 1);
  return id;
}

const insertIssueStmt = db.prepare(`
  INSERT INTO issues (id, packageName, screen, timestamp, severity, wcagSC, wcagLevel, elementDescription, description, suggestedFix, screenshot, bounds)
  VALUES (:id, :packageName, :screen, :timestamp, :severity, :wcagSC, :wcagLevel, :elementDescription, :description, :suggestedFix, :screenshot, :bounds)
`);

function rowToIssue(row) {
  return { ...row, bounds: row.bounds ? JSON.parse(row.bounds) : null };
}

function touchDevice() {
  setState('deviceLastSeen', Date.now());
}

function getDeviceLastSeen() {
  return getState('deviceLastSeen', null);
}

function addReport(report) {
  const { packageName, screen, timestamp, screenshot, issues: reportIssues = [] } = report;
  const stored = reportIssues.map((issue) => {
    const row = {
      id: takeNextId(),
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
    };
    insertIssueStmt.run({ ...row, bounds: row.bounds ? JSON.stringify(row.bounds) : null });
    return row;
  });
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

module.exports = {
  addReport, getIssues, clearIssues,
  getControl, setControl,
  getAppList, setAppList,
  touchDevice, getDeviceLastSeen,
};
