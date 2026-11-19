import assert from 'node:assert/strict';
import { mkdtemp, mkdir, readFile, rm, symlink, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { assembleAgents, composeAgents, agentsFragment, normalizeAgents } from './agents-document.mjs';

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
  await writeFile(path.join(root, 'base/AGENTS.md'), base);
  await assert.rejects(assembleAgents(root, [{ id: 'login' }]));
  const fragment = path.join(root, 'extensions/login/AGENTS.fragment.md');
  await symlink(path.join(fixtures, 'login.fragment.md'), fragment);
  await assert.rejects(assembleAgents(root, [{ id: 'login' }]), /unsafe source/);
  await rm(fragment);
  await writeFile(fragment, fragments[0].content);
  await assert.rejects(assembleAgents(root, [{ id: '../login' }]), /invalid capability id/);
  assert.equal(await assembleAgents(root, [{ id: 'login' }]), await readFile(path.join(fixtures, 'login.expected.md'), 'utf8'));
} finally { await rm(root, { recursive: true, force: true }); }
process.stdout.write('AGENTS golden and safety scenarios passed.\n');
