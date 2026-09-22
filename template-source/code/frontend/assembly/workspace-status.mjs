import { readWorkspaceState, workspaceDirty } from './workspace-state.mjs';
const state = await readWorkspaceState();
if (!state) process.stdout.write('No managed workspace state.\n');
else {
  const status = await workspaceDirty();
  process.stdout.write(`${status.dirty ? 'DIRTY' : 'CLEAN'} profile=${state.profile}${status.dirty ? `\n${status.drift.join('\n')}` : ''}\n`);
  if (status.dirty) process.exitCode = 1;
}
