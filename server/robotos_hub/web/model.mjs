export const escapeHTML = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c]));
export const previewPrompt = prompt => [prompt.instruction || '', ...(prompt.blocks || []).map(b => b.text || '')].filter(s => s.trim()).join('\n\n');
export const missingTranscripts = prompt => (prompt.blocks || []).filter(b => b.kind === 'audio' && !b.text?.trim());
export const draftContent = prompt => ({title: prompt.title || '', instruction: prompt.instruction || '', blocks: (prompt.blocks || []).map(b => ({id:b.id, ...(b.note_id ? {note_id:b.note_id} : {}), kind:b.kind, text:b.text || '', ...(b.media_id ? {media_id:b.media_id} : {})}))});
export const sameContent = (a, b) => JSON.stringify(draftContent(a)) === JSON.stringify(draftContent(b));
export function reconcileDraft(saved, local) {
  if (!local || sameContent(saved, local)) return {draft: structuredClone(saved), dirty:false, conflict:false};
  return {draft: {...structuredClone(saved), ...draftContent(local)}, dirty:true, conflict:Number(local.revision) !== Number(saved.revision)};
}
export function reconcileSavedDraft(currentDraft, submitted, saved) {
  const draft = sameContent(currentDraft, submitted) ? structuredClone(saved) : {...structuredClone(saved), ...draftContent(currentDraft)};
  return {draft, saved:structuredClone(saved), dirty:!sameContent(draft,saved)};
}
export function recoveryAfterSave(local, submitted, saved, baseRevision) {
  if (!local) return null;
  if (local.revision !== baseRevision) return local;
  return sameContent(local,submitted) ? null : {...local, revision:saved.revision};
}
export const operationIsCurrent = (operation, activePromptId, generation) => operation.promptId===activePromptId && operation.generation===generation;
export const transcriptTargetUnchanged = (original, current) => Boolean(current) && ['ownerId','generation','itemId','text','title'].every(key=>original[key]===current[key]);
export function captureAdmission(capture) {
  return capture.admission || {id:capture.id,title:capture.title,kind:capture.kind,text:capture.text,...(capture.media_id?{media_id:capture.media_id}:{})};
}
export const statusLabel = status => ({draft:'Draft', ran:'Ran', needs_attention:'Needs attention', queued:'Queued', submitting:'Submitting', submission_unknown:'Submission unknown', running:'Running', waiting_for_approval:'Approval needed', stopping:'Stopping', completed:'Completed', failed:'Failed', cancelled:'Cancelled', interrupted:'Interrupted', received:'Received', applied:'Applied', unknown:'Outcome unknown', expired:'Expired'}[status] || String(status || 'Unknown').replaceAll('_', ' '));
export const isRunActive = run => ['queued','submitting','submission_unknown','running','waiting_for_approval','stopping'].includes(run.status);
export const isDangerous = type => ['lock','clear_workspace','factory_reset','update_app','reboot'].includes(type);
export const commandLabel = type => ({ring:'Ring device',stop_ring:'Stop ringing',show_message:'Show return message',locate:'Request location',lock:'Lock device',clear_workspace:'Erase local workspace',factory_reset:'Factory reset',update_app:'Install verified update',reboot:'Restart device'}[type] || type);
export function commandSupport(device, type) {
  const caps = device.capabilities || {};
  const settings = device.applied_settings || {};
  if (caps[type] !== true) return {enabled:false, reason:'Enable this capability on the R1 first.'};
  if (settings.remote_management !== true) return {enabled:false, reason:'Enable remote management on the R1 first.'};
  if (type === 'lock' && settings.secure_lock !== true) return {enabled:false, reason:'Set a secure Android lock on the R1 first.'};
  const permission = {locate:'location_reporting',clear_workspace:'allow_clear_workspace',factory_reset:'allow_factory_reset',update_app:'allow_updates',reboot:'allow_reboot'}[type];
  if (permission && settings[permission] !== true) return {enabled:false, reason:'Allow this action in the R1 device permissions first.'};
  return {enabled:true, reason:''};
}
export function locationDescription(location) {
  if (!location || !Number.isFinite(location.latitude) || !Number.isFinite(location.longitude)) return null;
  return {coordinates:`${location.latitude.toFixed(5)}, ${location.longitude.toFixed(5)}`, accuracy:Number.isFinite(location.accuracy) ? `${Math.round(location.accuracy)} m accuracy` : 'Accuracy not reported', observed_at:location.observed_at};
}
export function describeSync(device) {
  const desired = device.settings_revision;
  const applied = device.applied_settings_revision ?? device.reported_settings_revision;
  if (applied == null) return 'Waiting for device confirmation';
  if (applied !== desired) return `Settings waiting for device (saved ${desired}, applied ${applied})`;
  const expected=device.desired_settings || {}, observed=device.applied_settings || {};
  if (Object.keys(expected).some(key=>expected[key]!==observed[key])) return 'Device settings differ from saved settings';
  return 'Settings confirmed by device';
}
