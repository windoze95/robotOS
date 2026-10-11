import test from 'node:test';
import assert from 'node:assert/strict';
import {previewPrompt, missingTranscripts, reconcileDraft, reconcileSavedDraft, recoveryAfterSave, operationIsCurrent, transcriptTargetUnchanged, captureAdmission, commandSupport, isRunActive, locationDescription, describeSync, escapeHTML, recoveryPolicy, securityPosture} from '../model.mjs';

test('preview preserves user text and omits empty blocks; audio requires a transcript', () => {
  const prompt = {instruction:'Plan this.',blocks:[{kind:'text',text:'First thought\nnext line'},{kind:'text',text:' '},{kind:'audio',text:''},{kind:'audio',text:'Second thought'}]};
  assert.equal(previewPrompt(prompt),'Plan this.\n\nFirst thought\nnext line\n\nSecond thought');
  assert.equal(missingTranscripts(prompt).length,1);
});
test('unsaved browser changes survive a competing device save and are marked for review', () => {
  const server = {id:'p',revision:3,title:'Server title',instruction:'From R1 B',blocks:[]};
  const local = {...server,revision:2,instruction:'Unsent browser work'};
  const result = reconcileDraft(server, local);
  assert.equal(result.draft.instruction,'Unsent browser work');
  assert.equal(server.instruction,'From R1 B');
  assert.equal(result.dirty,true);
  assert.equal(result.conflict,true);
  assert.deepEqual(reconcileDraft(server, server),{draft:server,dirty:false,conflict:false});
});
test('unknown submission stays active, never appears as a completed result', () => {
  assert.equal(isRunActive({status:'submission_unknown'}),true);
  assert.equal(isRunActive({status:'completed'}),false);
});
test('management requires explicit reported capability and secure lock is respected', () => {
  assert.equal(commandSupport({},'factory_reset').enabled,false);
  assert.equal(commandSupport({capabilities:{factory_reset:false}},'factory_reset').enabled,false);
  assert.equal(commandSupport({capabilities:{lock:true},applied_settings:{remote_management:true,secure_lock:false}},'lock').enabled,false);
  assert.equal(commandSupport({capabilities:{ring:true}},'ring').enabled,false);
  assert.equal(commandSupport({capabilities:{ring:true},applied_settings:{remote_management:true}},'ring').enabled,true);
});
test('lost policy confirmation requires the exact policy and local restriction evidence', () => {
  const lost={lost:true,lost_applied:true,policy_revision:3,applied_policy_revision:2,security:{workspace_restricted:true}};
  assert.equal(recoveryPolicy(lost).confirmed,false);
  assert.match(recoveryPolicy(lost).detail,/no expiry/);
  assert.equal(recoveryPolicy({...lost,applied_policy_revision:3}).confirmed,true);
  assert.equal(recoveryPolicy({...lost,applied_policy_revision:3,security:{}}).confirmed,false);
  assert.equal(recoveryPolicy({lost:true,lost_applied:true}).confirmed,false);
  assert.equal(recoveryPolicy({...lost,lost:false,revoked:true}).restricted,true);
});
test('security reporting distinguishes app restrictions from Android lock and local consent', () => {
  assert.equal(securityPosture({}).title,'Protection not yet reported');
  const device={security:{app_restriction_available:true,secure_lock_configured:false}};
  assert.equal(securityPosture(device).title,'App restrictions available');
  device.security.secure_lock_configured=true;
  assert.equal(securityPosture(device).remoteLock,'Unavailable');
  device.security.secure_lock_available=true;
  assert.equal(securityPosture(device).title,'App restrictions and secure Android lock');
  assert.equal(securityPosture(device).appliedLock,'No lock action confirmed');
});
test('quarantined devices cannot be updated or restarted but recovery stays available', () => {
  const device={lost:true,capabilities:{reboot:true,update_app:true,ring:true},applied_settings:{remote_management:true,allow_updates:true,allow_reboot:true}};
  assert.equal(commandSupport(device,'reboot').enabled,false);
  assert.equal(commandSupport(device,'update_app').enabled,false);
  assert.equal(commandSupport(device,'ring').enabled,true);
});
test('missing device evidence never becomes a location or applied settings claim', () => {
  assert.equal(locationDescription(null),null);
  assert.equal(locationDescription({latitude:0,longitude:0,accuracy:3}).coordinates,'0.00000, 0.00000');
  assert.equal(describeSync({settings_revision:4}),'Waiting for device confirmation');
  assert.match(describeSync({settings_revision:4,applied_settings_revision:3}),/waiting/);
  assert.equal(describeSync({settings_revision:4,applied_settings_revision:4}),'Settings confirmed by device');
});
test('stored note and device text cannot inject markup into console templates', () => {
  assert.equal(escapeHTML('<img src=x onerror="x">'), '&lt;img src=x onerror=&quot;x&quot;&gt;');
});
test('a delayed save keeps keystrokes entered after submission dirty against the new revision', async () => {
  const submitted={id:'p',revision:2,title:'Plan',instruction:'First instruction',blocks:[]};
  let complete;
  const pending=new Promise(resolve=>complete=resolve);
  const current={...submitted,instruction:'First instruction plus a later thought'};
  complete({...submitted,revision:3});
  const result=reconcileSavedDraft(current,submitted,await pending);
  assert.equal(result.saved.instruction,'First instruction');
  assert.equal(result.draft.instruction,'First instruction plus a later thought');
  assert.equal(result.draft.revision,3);
  assert.equal(result.dirty,true);
});
test('a save finishing after navigation only reconciles its original recovery record', () => {
  const operation={promptId:'first',generation:1};
  const submitted={id:'first',revision:2,title:'First',instruction:'Sent',blocks:[]};
  const nextDraft={id:'second',revision:7,title:'Second',instruction:'Other work',blocks:[]};
  assert.equal(operationIsCurrent(operation,nextDraft.id,2),false);
  assert.equal(operationIsCurrent(operation,'first',3),false,'returning later is a different editor generation');
  const pendingLocal={...submitted,instruction:'Typed while waiting'};
  const recovered=recoveryAfterSave(pendingLocal,submitted,{...submitted,revision:3},2);
  assert.equal(recovered.instruction,'Typed while waiting');
  assert.equal(recovered.revision,3);
  assert.equal(nextDraft.instruction,'Other work');
  assert.equal(recoveryAfterSave(submitted,submitted,{...submitted,revision:3},2),null);
});
test('audio capture retries retain the exact admission body after a lost response', () => {
  const capture={id:'note',title:'Original',kind:'audio',text:'Original transcript',media_id:'media'};
  capture.admission=captureAdmission(capture);
  capture.title='Edited after the response was lost';
  capture.text='Corrected transcript';
  assert.equal(captureAdmission(capture).title,'Original');
  assert.equal(captureAdmission(capture).text,'Original transcript');
  assert.equal(captureAdmission(capture).media_id,'media');
});
test('equal settings revisions do not hide a later local setting change', () => {
  assert.equal(describeSync({settings_revision:4,applied_settings_revision:4,desired_settings:{quiet:true,brightness:100},applied_settings:{quiet:true,brightness:150}}),'Device settings differ from saved settings');
  assert.equal(describeSync({settings_revision:4,applied_settings_revision:4,desired_settings:{quiet:true,brightness:100},applied_settings:{quiet:true,brightness:100,remote_management:true}}),'Settings confirmed by device');
});
test('delayed transcription never overwrites typing, a replacement block, or a different editor', async () => {
  const original={ownerId:'prompt-one',generation:1,itemId:'audio-one',text:'Manual transcript'};
  let complete;
  const pending=new Promise(resolve=>complete=resolve);
  const edited={...original,text:'Manual transcript corrected while waiting'};
  complete('Generated transcript');
  const transcript=await pending;
  if(transcriptTargetUnchanged(original,edited))edited.text=transcript;
  assert.equal(edited.text,'Manual transcript corrected while waiting');
  assert.equal(transcriptTargetUnchanged(original,{...original,ownerId:'prompt-two'}),false);
  assert.equal(transcriptTargetUnchanged(original,{...original,generation:2}),false);
  assert.equal(transcriptTargetUnchanged(original,{...original,itemId:'replacement-audio'}),false);
  assert.equal(transcriptTargetUnchanged(original,null),false);
  assert.equal(transcriptTargetUnchanged(original,{...original}),true);
});
test('note transcription preserves edits to the title as well as the transcript and closed dialogs', () => {
  const original={ownerId:'note-one',generation:'dialog-one',itemId:'note-one',title:'Recording',text:'Draft'};
  assert.equal(transcriptTargetUnchanged(original,{...original,title:'Title typed while waiting'}),false);
  assert.equal(transcriptTargetUnchanged(original,{...original,text:'Transcript typed while waiting'}),false);
  assert.equal(transcriptTargetUnchanged(original,{...original,generation:'dialog-two'}),false);
  assert.equal(transcriptTargetUnchanged(original,null),false);
});
