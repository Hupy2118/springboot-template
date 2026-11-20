import assert from 'node:assert/strict';
import { mkdtemp, mkdir, readFile, rm, symlink, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { assembleAgents, composeAgents, agentsFragment, normalizeAgents, composeFrontend, frontendProjectTree } from './agents-document.mjs';

const fixtures = fileURLToPath(new URL('../../../../scripts/ci/fixtures/agents-document/', import.meta.url));
const base = await readFile(path.join(fixtures, 'base.md'));
const fragments = [];
assert.equal(composeAgents(base, fragments), await readFile(path.join(fixtures, 'base.expected.md'), 'utf8'));
for (const id of ['login', 'authorization']) {
  const content = await readFile(path.join(fixtures, `${id}.fragment.md`));
  fragments.push({ id, content });
  assert.equal(composeAgents(base, fragments), await readFile(path.join(fixtures, `${id}.expected.md`), 'utf8'));
  assert.throws(() => agentsFragment(id, Buffer.from(content.toString().replace(':END', ':BROKEN'))));
  assert.throws(() => agentsFragment(id, Buffer.concat([content, content])));
}
assert.throws(() => composeAgents(base, [...fragments, fragments[0]]));
assert.throws(() => composeAgents(fragments[0].content, fragments));
assert.throws(() => normalizeAgents(Buffer.from([0xff])));
assert.throws(() => normalizeAgents(Buffer.from([0])));
const root = await mkdtemp(path.join(tmpdir(), 'agents-source-'));
try {
  await mkdir(path.join(root, 'base'));
  await mkdir(path.join(root, 'extensions/login'), { recursive: true });
  await writeFile(path.join(root, 'base/AGENTS.md'), await readFile(path.join(fixtures, 'frontend.md')));
  await assert.rejects(assembleAgents(root, [{ id: 'login' }]));
  const fragment = path.join(root, 'extensions/login/AGENTS.fragment.md');
  await symlink(path.join(fixtures, 'login.fragment.md'), fragment);
  await assert.rejects(assembleAgents(root, [{ id: 'login' }]), /unsafe source/);
  await rm(fragment);
  await writeFile(fragment, fragments[0].content);
  await assert.rejects(assembleAgents(root, [{ id: '../login' }]), /invalid capability id/);
  assert.equal(await assembleAgents(root, [{ id: 'login' }], []), composeFrontend(await readFile(path.join(fixtures, 'frontend.md')), [fragments[0]], []));
} finally { await rm(root, { recursive: true, force: true }); }
process.stdout.write('AGENTS golden and safety scenarios passed.\n');

const frontendBase = await readFile(path.join(fixtures, 'frontend.md'));
for (const [index, profile] of ['base', 'login', 'authorization'].entries()) {
  const files = (await readFile(path.join(fixtures, `frontend-${profile}.files.txt`), 'utf8')).trim().split('\n');
  const selected = fragments.slice(0, index);
  const actual = composeFrontend(frontendBase, selected, files);
  assert.equal(actual, await readFile(path.join(fixtures, `frontend-${profile}.expected.md`), 'utf8'));
  assert.equal(actual, composeFrontend(frontendBase, selected, [...files].reverse()));
  assert.doesNotMatch(actual, /DEVAGENTSTUDIO|ignored|example.test/);
}
for (const invalid of [base, Buffer.concat([frontendBase, frontendBase])]) assert.throws(() => composeFrontend(invalid, [], []));
for (const invalid of ['../escape', '/absolute', 'src//bad', 'src/./bad', 'src/../bad', 'src\\bad', 'src/bad\npath']) assert.throws(() => frontendProjectTree([invalid]));
assert.throws(() => composeFrontend(frontendBase, [fragments[0], fragments[0]], []));
assert.throws(() => composeFrontend(frontendBase, [{ id: 'login', content: Buffer.from('invalid') }], []));
