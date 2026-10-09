// Pure helpers extracted from App.jsx so they can be unit tested and so
// react/only-export-components (fast refresh) doesn't warn about mixing
// non-component exports into a component file.

export function severityRank(s) {
  return { critical: 0, serious: 1, moderate: 2, minor: 3 }[s] ?? 4;
}

export function filterIssues(issues, { severityFilter, levelFilter, screenFilter, search }) {
  const q = search.trim().toLowerCase();
  return issues
    .filter((i) => severityFilter === 'all' || i.severity === severityFilter)
    .filter((i) => levelFilter === 'all' || i.wcagLevel === levelFilter)
    .filter((i) => screenFilter === 'all' || i.screen === screenFilter)
    .filter((i) => !q || `${i.elementDescription} ${i.description}`.toLowerCase().includes(q));
}

// Groups (pre-filtered) issues by screen, sorting each group by severity.
export function groupIssues(filtered) {
  const map = new Map();
  for (const issue of filtered) {
    const key = issue.screen || 'Unknown screen';
    if (!map.has(key)) map.set(key, []);
    map.get(key).push(issue);
  }
  for (const list of map.values()) list.sort((a, b) => severityRank(a.severity) - severityRank(b.severity));
  return map;
}

// suggestedFix is a JSON string {summary, views, compose, docUrl, framework}
// from the device. Older rows hold plain text, shown as the summary.
export function parseFix(raw) {
  if (!raw) return null;
  try {
    const fix = JSON.parse(raw);
    // Keep only string fields: anything on localhost can send reports, and a
    // non-string here would crash rendering.
    if (fix && typeof fix.summary === 'string' && fix.summary) {
      return Object.fromEntries(
        ['summary', 'views', 'compose', 'docUrl', 'framework']
          .filter((k) => typeof fix[k] === 'string')
          .map((k) => [k, fix[k]])
      );
    }
  } catch {
    // plain text, handled below
  }
  return { summary: String(raw) };
}
