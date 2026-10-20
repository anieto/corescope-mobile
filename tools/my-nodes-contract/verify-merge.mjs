// Verify shared golden cases against the pinned upstream web implementation.
// Pass a locally downloaded public/settings-sync.js; never evaluates app/server credentials.
import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import vm from 'node:vm';
import assert from 'node:assert/strict';

const fixtureURL = new URL('../../shared/fixtures/my-nodes/account-merge-cases.json', import.meta.url);
const fixture = JSON.parse(readFileSync(fixtureURL, 'utf8'));
if (!process.argv[2]) throw new Error('Usage: node verify-merge.mjs /path/to/pinned/settings-sync.js');
const source = readFileSync(process.argv[2], 'utf8');
assert.equal(createHash('sha256').update(source).digest('hex'), fixture.upstreamScriptSHA256,
    'Upstream script differs from the audited release; re-audit before changing golden results');

// Auth remains disabled, so no browser behavior or network work starts. Only _test.mergeDocs runs.
class Storage {
    getItem() { return null; }
    setItem() {}
    removeItem() {}
}
const window = {
    Storage,
    localStorage: new Storage(),
    addEventListener() {},
    CSAuth: { ready: () => Promise.resolve(null), isEnabled: () => false },
};
const context = vm.createContext({ window, console });
vm.runInContext(source, context, { timeout: 1000 });
for (const entry of fixture.cases) {
    // Run inside the timed context; stringify to compare values across VM realms.
    context.input = entry;
    const raw = vm.runInContext(
        'JSON.stringify(window.CSSettingsSync._test.mergeDocs(input.local, input.remote, input.base, input.allowlist).keys)',
        context, { timeout: 1000 });
    assert.deepEqual(JSON.parse(raw), entry.expected, entry.name);
}
console.log(`Passed ${fixture.cases.length} shared merge cases against CoreScope ${fixture.upstreamCommit}`);
