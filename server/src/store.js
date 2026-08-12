// In-memory issue store. Swap to SQLite later by reimplementing these
// functions against a table with the same fields.
let issues = [];
let nextId = 1;

// Desired auditing state, set by the dashboard, polled by the device (the
// device can't be pushed to directly — adb reverse only lets it reach us,
// not the other way around).
let control = { targetPackage: null, auditing: false, updatedAt: null };

// Installed-app list, reported by the device (same one-directional reasoning
// as control) so the dashboard can offer a real picker instead of free text.
let appList = [];

// The device's control poll doubles as a heartbeat — no separate ping needed.
let deviceLastSeen = null;

function touchDevice() {
  deviceLastSeen = Date.now();
}

function getDeviceLastSeen() {
  return deviceLastSeen;
}

function addReport(report) {
  const { packageName, screen, timestamp, screenshot, issues: reportIssues = [] } = report;
  const stored = reportIssues.map((issue) => ({
    id: nextId++,
    packageName,
    screen,
    timestamp,
    severity: issue.severity,
    wcagSC: issue.wcagSC,
    wcagLevel: issue.wcagLevel,
    elementDescription: issue.elementDescription,
    description: issue.description,
    suggestedFix: issue.suggestedFix,
    screenshot: screenshot || null,
    bounds: issue.bounds || null,
  }));
  issues.push(...stored);
  return stored;
}

function getIssues() {
  return issues;
}

function clearIssues() {
  issues = [];
  nextId = 1;
}

function getControl() {
  return control;
}

function setControl(patch) {
  control = { ...control, ...patch, updatedAt: Date.now() };
  return control;
}

function getAppList() {
  return appList;
}

function setAppList(apps) {
  appList = apps;
  return appList;
}

module.exports = {
  addReport, getIssues, clearIssues,
  getControl, setControl,
  getAppList, setAppList,
  touchDevice, getDeviceLastSeen,
};
