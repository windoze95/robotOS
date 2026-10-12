import {escapeHTML as h, previewPrompt, missingTranscripts, draftContent, sameContent, reconcileDraft, reconcileSavedDraft, recoveryAfterSave, operationIsCurrent, transcriptTargetUnchanged, captureAdmission, statusLabel, isRunActive, workspaceRoute, promptSubmissionLink, promptSubmissions, isDangerous, commandLabel, commandSupport, locationDescription, describeSync, recoveryPolicy, securityPosture} from './model.mjs';

const $ = selector => document.querySelector(selector);
const uid = () => crypto.randomUUID();
const stamp = value => value ? new Date(value).toLocaleString(undefined,{dateStyle:'medium',timeStyle:'short'}) : 'Not reported';
const relative = value => {
  if (!value) return 'Never connected';
  const seconds = Math.max(0,(Date.now()-Number(value))/1000);
  if (seconds < 60) return 'Just now';
  if (seconds < 3600) return `${Math.floor(seconds/60)} min ago`;
  if (seconds < 86400) return `${Math.floor(seconds/3600)} hr ago`;
  return stamp(value);
};
const icon = name => `<span class="nav-icon" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">${({notes:'<path d="M5 3h14v18H5zM8 8h8M8 12h8M8 16h5"/>',prompts:'<path d="M4 5h16v14H4zM8 9l3 3-3 3M13 15h3"/>',devices:'<rect x="6" y="2" width="12" height="20" rx="3"/><path d="M9 6h6v7H9z"/><circle cx="14" cy="17" r="1"/>',audit:'<path d="M8 4H5v17h14V4h-3M9 2h6v4H9zM8 11h8M8 15h8"/>'})[name] || ''}</svg></span>`;
const badge = (status,label) => `<span class="state ${h(status)}">${h(label || statusLabel(status))}</span>`;
const link = (view,id) => `#${view}${id ? '/'+encodeURIComponent(id) : ''}`;
const s = {session:null, view:'prompts', id:null, notes:[], prompts:[], runs:[], devices:[], audit:[], selectedNotes:new Set(), draft:null, saved:null, lease:null, dirty:false, conflict:false, offline:false, cursor:0, resume:null, activeBlock:null, loading:0, filter:'', kind:'all', device:null, commands:[], recovery:[], recoveryState:'pending', pendingRun:null, selectedRunId:null, leaseDenied:false};
let draftTimer, resumeTimer, pollTimer, toastTimer, mediaRecorder, recordingStream, recordingChunks=[], recordingClock, recordingStart, dialogContext=null;
const savingPrompts=new Set();
const pendingTranscripts=new Map();

class ApiError extends Error {constructor(message,status,code){super(message);this.status=status;this.code=code;}}
async function api(path,{method='GET',body,headers={},raw=false}={}) {
  const requestHeaders = {...headers};
  if (method !== 'GET') requestHeaders['X-CSRF-Token'] = s.session?.csrf_token || '';
  if (body != null && !raw) requestHeaders['Content-Type']='application/json';
  let response;const controller=new AbortController(),timeout=setTimeout(()=>controller.abort(),path.endsWith('/transcribe')?120000:30000);
  try {response=await fetch('/api/v1'+path,{method,body:body == null ? undefined : raw ? body : JSON.stringify(body),headers:requestHeaders,credentials:'same-origin',cache:'no-store',signal:controller.signal});} catch (_) {setOnline(false);throw new ApiError('Cannot reach your host. Keep this tab open until your work is saved.',0,'offline');}finally{clearTimeout(timeout);}
  const text=await response.text(); let data={};
  try {data=text ? JSON.parse(text) : {};} catch (_) {throw new ApiError('The host returned an unreadable response. Try again.',response.status,'invalid_response');}
  if (!response.ok) {
    if (response.status === 401 && path !== '/session') {s.session=null;clearInterval(pollTimer);await saveDraftLocal();showLogin('Your session ended. Sign in to continue; unsaved work is kept in this browser.');}
    throw new ApiError(data.error || `Request failed (${response.status}).`,response.status,data.code);
  }
  setOnline(true);return data;
}
function setOnline(online) {s.offline=!online;const el=$('#connection');if(el){el.classList.toggle('offline',!online);el.textContent=online?'Host connected':'Host unavailable';}}
function announce(message) {$('#announcements').textContent=message;}
function toast(message) {clearTimeout(toastTimer);const el=$('#toast');el.textContent=message;el.hidden=false;announce(message);toastTimer=setTimeout(()=>el.hidden=true,5000);}
function pageError(message) {let el=$('#page-error');if(!el)return;el.hidden=false;el.textContent=message;announce(message);}
function dialogError(message) {const el=$('#dialog-error');if(el){el.textContent=message;el.hidden=false;el.setAttribute('tabindex','-1');el.focus();}else pageError(message);}

const dbPromise = new Promise(resolve => {
  if(!window.indexedDB){resolve(null);return;}
  const request=indexedDB.open('robotos-workspace',1);
  request.onupgradeneeded=()=>request.result.createObjectStore('recovery',{keyPath:'key'});
  request.onsuccess=()=>resolve(request.result);
  request.onerror=()=>resolve(null);
});
async function localPut(key,value) {const db=await dbPromise;if(!db){toast('Browser recovery storage is unavailable. Keep this tab open until saved to the host.');return false;}return new Promise(resolve=>{const tx=db.transaction('recovery','readwrite');tx.objectStore('recovery').put({key,value});tx.oncomplete=()=>resolve(true);tx.onerror=()=>{toast('Browser recovery storage is full. Keep this tab open until saved.');resolve(false);};});}
async function localGet(key) {const db=await dbPromise;if(!db)return null;return new Promise(resolve=>{const r=db.transaction('recovery').objectStore('recovery').get(key);r.onsuccess=()=>resolve(r.result?.value || null);r.onerror=()=>resolve(null);});}
async function localDelete(key) {const db=await dbPromise;if(!db)return;return new Promise(resolve=>{const tx=db.transaction('recovery','readwrite');tx.objectStore('recovery').delete(key);tx.oncomplete=tx.onerror=()=>resolve();});}
async function localEntries() {const db=await dbPromise;if(!db)return [];return new Promise(resolve=>{const r=db.transaction('recovery').objectStore('recovery').getAll();r.onsuccess=()=>resolve(r.result || []);r.onerror=()=>resolve([]);});}
async function localClear() {const db=await dbPromise;if(!db)return;return new Promise(resolve=>{const tx=db.transaction('recovery','readwrite');tx.objectStore('recovery').clear();tx.oncomplete=tx.onerror=()=>resolve();});}
async function saveDraftLocal() {if(s.draft && s.dirty){const id=s.draft.id;const saved=await localPut('draft:'+id,{...draftContent(s.draft),id,revision:s.saved.revision});if(s.draft?.id===id){s.recoveryState=saved?'saved':'unavailable';updateEditorStatus();}}}

function showLogin(error='') {
  $('#app').innerHTML=`<main id="main" class="login"><div class="login-intro"><span class="robot-mark" aria-hidden="true"></span><h1>Your thoughts.<br>Your devices.</h1><p>One workspace for your R1s. Pick up where you last saved.</p></div><div class="login-form"><form id="login-form"><h2>Open your workspace</h2><p>Sign in to your robotOS host.</p><div class="dialog-error" id="login-error" role="alert" ${error?'':'hidden'}>${h(error)}</div><label class="field"><span>Owner password</span><input type="password" name="password" autocomplete="current-password" required autofocus></label><button class="primary full" type="submit">Sign in</button><p class="login-footer">Your recordings and prompts stay on your host.</p></form></div></main>`;
  $('#login-form').addEventListener('submit',async event=>{event.preventDefault();const button=event.target.querySelector('button');button.disabled=true;button.textContent='Signing in…';try{s.session=await api('/session',{method:'POST',body:{password:event.target.password.value}});event.target.password.value='';await enterWorkspace();}catch(error){$('#login-error').textContent=error.message;$('#login-error').hidden=false;button.disabled=false;button.textContent='Sign in';}});
}
function shell() {
  $('#app').innerHTML=`<div class="shell"><aside class="rail"><a class="brand" href="#prompts"><span class="robot-mark" aria-hidden="true"></span><span>robotOS</span></a><nav class="nav" aria-label="Workspace">${[['prompts','Prompt Builder'],['notes','Notes'],['devices','Devices'],['audit','Device activity']].map(([key,title])=>`<a href="#${key}" data-view="${key}" ${s.view===key?'aria-current="page"':''}>${icon(key)}${title}</a>`).join('')}</nav><div class="rail-foot"><span class="connection ${s.offline?'offline':''}" id="connection">${s.offline?'Host unavailable':'Host connected'}</span><button class="ghost small" data-action="logout">Sign out</button></div></aside><main id="main" tabindex="-1"><div class="content" id="content"></div></main></div>`;
}
function head(title,subtitle='',actions='') {return `<header class="page-head"><div><h1>${h(title)}</h1>${subtitle?`<p>${h(subtitle)}</p>`:''}</div>${actions?`<div class="actions">${actions}</div>`:''}</header><div id="page-error" class="notice error" role="alert" hidden></div>`;}
function empty(title,copy,actions='') {return `<section class="empty"><h2>${h(title)}</h2><p>${h(copy)}</p>${actions?`<div class="actions">${actions}</div>`:''}</section>`;}
async function enterWorkspace() {shell();s.recovery=await localEntries();await navigate();clearInterval(pollTimer);pollTimer=setInterval(poll,5000);}
async function navigate() {
  await saveDraftLocal();
  const previous=s.draft?.id, token=s.lease?.lease_token;
  let route=workspaceRoute(location.hash);
  const turn=++s.loading;
  if(route.legacyRunId){
    try {
      const submission=await api('/runs/'+encodeURIComponent(route.legacyRunId));
      if(turn!==s.loading)return;
      route={view:'prompts',id:submission.prompt_id||null,submissionId:submission.id};
    }catch(error){
      if(turn!==s.loading||!s.session)return;
      if(error.status!==404){$('#content').innerHTML=head('Could not open this prompt')+empty('Your host needs attention',error.message,'<button data-action="refresh">Try again</button>');return;}
      route={view:'prompts',id:null,submissionId:null};
    }
    history.replaceState(null,'',promptSubmissionLink(route.id,route.submissionId));
  }else if(location.hash==='#runs'){history.replaceState(null,'','#prompts');}
  s.view=route.view;s.id=route.id;s.selectedRunId=route.submissionId;
  if(previous && (s.view!=='prompts'||s.id!==previous) && token){api(`/prompts/${encodeURIComponent(previous)}/release`,{method:'POST',body:{lease_token:token}}).catch(()=>{});}
  s.draft=null;s.saved=null;s.lease=null;s.dirty=false;s.conflict=false;s.filter='';s.kind='all';s.activeBlock=null;s.recoveryState='pending';s.leaseDenied=false;
  document.querySelectorAll('.nav a').forEach(a=>a.dataset.view===s.view?a.setAttribute('aria-current','page'):a.removeAttribute('aria-current'));
  if(!$('#content'))return;
  $('#content').innerHTML=head(({prompts:'Prompt Builder',notes:'Notes',devices:'Devices',audit:'Device activity'})[s.view])+`<p class="muted" role="status">Loading…</p>`;
  try {
    if(s.view==='prompts') {
      const [prompts,notes,runs,resume]=await Promise.all([api('/prompts'),api('/notes'),s.id?api('/prompts/'+encodeURIComponent(s.id)+'/submissions'):Promise.resolve({items:[]}),api('/resume')]);
      if(turn!==s.loading)return;s.prompts=prompts.items;s.notes=notes.items;s.runs=runs.items;s.resume=resume;
      if(s.id){const saved=await api('/prompts/'+encodeURIComponent(s.id));if(turn!==s.loading)return;s.saved=saved;const recovered=reconcileDraft(saved,await localGet('draft:'+s.id));Object.assign(s,recovered);s.recoveryState=recovered.dirty?'saved':'pending';const appended=await localGet('append:'+s.id);if(appended?.length){s.draft.blocks.push(...appended);s.dirty=true;await saveDraftLocal();await localDelete('append:'+s.id);}s.pendingRun=await localGet('run:'+s.id);if(turn!==s.loading)return;renderBuilder();}else renderPrompts();
    } else if(s.view==='notes') {const notes=await api('/notes');if(turn!==s.loading)return;s.notes=notes.items;s.recovery=await localEntries();renderNotes();if(s.id){const note=s.notes.find(n=>n.id===s.id);if(note)showNote(note);}}
    else if(s.view==='devices') {const devices=await api('/devices');if(turn!==s.loading)return;s.devices=devices.items;s.id=s.id||s.devices[0]?.id;await loadDevice();if(turn!==s.loading)return;renderDevices();}
    else {s.audit=(await api('/audit')).items;if(turn!==s.loading)return;renderAudit();}
    $('#main')?.focus({preventScroll:true});
  }catch(error){if(turn===s.loading && s.session){$('#content').innerHTML=head('Could not open this view')+empty('Your host needs attention',error.message,'<button data-action="refresh">Try again</button>');}}
}
function renderPrompts() {
  const resume=s.prompts.find(p=>p.id===s.resume?.prompt_id);
  const visible=s.prompts.filter(p=>(p.title+' '+p.instruction).toLowerCase().includes(s.filter.toLowerCase()));
  $('#content').innerHTML=head('Prompt Builder','Collect your notes, save a prompt, and send it to Hermes.','<button class="primary" data-action="new-prompt">New prompt</button>')+
    (resume?`<div class="notice inline spread"><span>Continue <strong>${h(resume.title||'Untitled prompt')}</strong></span><a href="${link('prompts',resume.id)}">Resume prompt</a></div>`:'')+
    `<div class="search-bar"><label class="sr-only" for="prompt-search">Search prompts</label><input id="prompt-search" placeholder="Search saved prompts" value="${h(s.filter)}"><span class="note-count">${s.prompts.length} saved</span></div>`+
    (visible.length?`<div class="collection">${visible.map(p=>`<article class="collection-row"><div class="row-content"><a class="row-title" href="${link('prompts',p.id)}">${h(p.title||'Untitled prompt')}</a><div class="row-sub">${badge(p.state)}${p.changed_since_run?badge('changed','Changed since last run'):''}<span>${p.blocks?.length||0} pieces</span><span>Saved ${h(relative(p.updated_at))}</span></div>${p.instruction?`<p class="excerpt">${h(p.instruction)}</p>`:''}</div><a href="${link('prompts',p.id)}" aria-label="Open ${h(p.title||'prompt')}">Open</a></article>`).join('')}</div>`:empty(s.filter?'No matching prompts':'Give your next idea some room',s.filter?'Try another title or instruction.':'Bring notes and recordings together, add an instruction, then save the prompt for later.','<button class="primary" data-action="new-prompt">Build a prompt</button>'));
}
function renderNotes() {
  const visible=s.notes.filter(n=>(s.kind==='all'||n.kind===s.kind)&&(n.title+' '+n.text).toLowerCase().includes(s.filter.toLowerCase()));
  const captures=s.recovery.filter(r=>r.key.startsWith('capture:'));
  $('#content').innerHTML=head('Notes','A place for the pieces that will become your next prompt.','<button data-action="new-audio">Record or upload</button><button class="primary" data-action="new-note">Write a note</button>')+
    (captures.length?`<section class="notice"><strong>Saved in this browser</strong><p>These captures have not been confirmed by your host. Finish saving to make them available on your R1s.</p>${captures.map(r=>`<div class="recovery-item inline spread"><span>${h(r.value.title||'Unsaved capture')}</span><button class="small" data-action="recover-capture" data-id="${h(r.value.id)}">Recover</button></div>`).join('')}</section>`:'')+
    `<div class="search-bar"><label class="sr-only" for="note-search">Search notes</label><input id="note-search" placeholder="Search notes and transcripts" value="${h(s.filter)}"><label class="sr-only" for="note-kind">Note type</label><select id="note-kind"><option value="all">All notes</option><option value="text" ${s.kind==='text'?'selected':''}>Written notes</option><option value="audio" ${s.kind==='audio'?'selected':''}>Voice notes</option></select></div><div id="selection"></div>`+
    (visible.length?`<div class="collection">${visible.map(n=>`<article class="collection-row"><input type="checkbox" data-select-note="${h(n.id)}" aria-label="Select ${h(n.title||'Untitled note')}" ${s.selectedNotes.has(n.id)?'checked':''}><div class="row-content"><button class="row-title link-button" data-action="edit-note" data-id="${h(n.id)}">${h(n.title||'Untitled note')}</button><div class="row-sub"><span>${n.kind==='audio'?'Voice note':'Written note'}</span><span>Saved ${h(relative(n.updated_at))}</span>${n.kind==='audio'&&!n.text?.trim()?badge('changed','Transcript needed'):''}</div><p class="excerpt">${h(n.text||'Recording saved. Add a transcript before running a prompt.')}</p></div><div class="actions"><button class="small" data-action="note-to-prompt" data-id="${h(n.id)}">Add to prompt</button></div></article>`).join('')}</div>`:empty(s.filter?'No matching notes':'Capture something worth keeping',s.filter?'Try another word or switch the note type.':'Write a note or record your thoughts. You can select several and add them to one prompt.','<button class="primary" data-action="new-note">Write a note</button><button data-action="new-audio">Record a thought</button>'));
  renderSelection();
}
function renderSelection() {const el=$('#selection');if(el)el.innerHTML=s.selectedNotes.size?`<div class="selection-bar"><span>${s.selectedNotes.size} selected</span><div class="actions"><button class="small ghost" data-action="clear-selection">Clear</button><button class="primary small" data-action="selection-to-prompt">Add to prompt</button></div></div>`:'';}
function blockView(block,index) {return `<section class="block" data-block="${h(block.id)}"><div class="block-head"><span class="kind">${index+1}. ${block.kind==='audio'?'Voice note':'Written note'}</span><div class="actions"><button class="icon-button small" data-action="move-up" data-id="${h(block.id)}" aria-label="Move piece ${index+1} up" ${index===0?'disabled':''}>↑</button><button class="icon-button small" data-action="move-down" data-id="${h(block.id)}" aria-label="Move piece ${index+1} down" ${index===s.draft.blocks.length-1?'disabled':''}>↓</button><button class="icon-button small" data-action="remove-block" data-id="${h(block.id)}" aria-label="Remove piece ${index+1}">×</button></div></div><div class="block-body">${block.media_id?`<audio controls preload="none" src="/api/v1/media/${encodeURIComponent(block.media_id)}" aria-label="Recording for piece ${index+1}"></audio>`:''}<label class="sr-only" for="block-${h(block.id)}">${block.kind==='audio'?'Transcript':'Text'} for piece ${index+1}</label><textarea id="block-${h(block.id)}" data-block-text="${h(block.id)}" placeholder="${block.kind==='audio'?'Add or correct the transcript…':'Your thought…'}">${h(block.text)}</textarea></div>${blockTranscriptReview(block)}${block.note_id?`<div class="block-footer"><span class="muted small-copy">Source note kept separately</span>${block.kind==='audio'?`<button class="small ghost" data-action="transcribe-block" data-id="${h(block.id)}">Transcribe</button>`:''}</div>`:''}</section>`;}
function blockTranscriptReview(block) {
  const result=pendingTranscripts.get(s.id+':'+block.id);
  return result?`<div class="notice"><strong>New transcript available</strong><p>Your edited text is preserved. Review the generated version before replacing it.</p><details><summary>Review generated transcript</summary><div class="preview-text">${h(result.text)}</div><button class="small" data-action="apply-block-transcript" data-id="${h(block.id)}">Use generated transcript</button></details></div>`:'';
}
function renderBuilder() {
  const p=s.draft;
  if(!p)return;
  $('#content').innerHTML=head('Prompt Builder','Saved prompts follow you between the browser and your R1s.','<button data-action="edit-prompt" id="edit-prompt">Edit prompt</button><a href="#prompts">All prompts</a>')+
    `<div id="edit-notice"></div><div class="workbench"><section aria-label="Prompt editor"><div class="builder-toolbar"><div class="inline" id="prompt-state">${badge(s.saved.state)}${s.saved.changed_since_run?badge('changed','Changed since last run'):''}</div><span class="muted" id="revision-label">Revision ${s.saved.revision}</span></div><label class="field"><span>Prompt title</span><input id="prompt-title" class="editor-title" value="${h(p.title)}" placeholder="Give this prompt a name" maxlength="200"></label><label class="field"><span>What should Hermes do with these notes?</span><textarea id="prompt-instruction" placeholder="For example: turn these observations into an implementation plan.">${h(p.instruction)}</textarea></label><div class="inline spread"><h2>Pieces</h2><span class="note-count" id="piece-count">${p.blocks.length} attached</span></div><div id="blocks">${p.blocks.map(blockView).join('')}</div><div class="add-block"><button data-action="builder-note">Write note</button><button data-action="builder-audio">Record voice note</button><button data-action="builder-existing">Add existing</button></div><div class="save-bar"><span class="small-copy" id="save-status"></span><div class="actions"><button data-action="save-prompt" id="save-prompt">Save prompt</button><button class="primary" data-action="run-prompt" id="run-prompt">Send to Hermes</button></div></div></section><aside class="preview-pane"><div class="pane-heading"><h2>What Hermes will receive</h2><button class="small ghost" data-action="copy-preview">Copy</button></div><div class="preview-text" id="preview-text"></div><div class="preview-count" id="preview-count"></div><div id="run-gate"></div><div id="prompt-result" tabindex="-1"></div></aside></div>`;
  updateEditorStatus();renderPromptResult();
  if(s.resume?.prompt_id===p.id && s.resume?.block_id){const block=document.getElementById('block-'+s.resume.block_id);if(block)setTimeout(()=>block.scrollIntoView({block:'center',behavior:'instant'}),0);}
}
function updateEditorStatus() {
  if(!s.draft||!$('#preview-text'))return;
  const readOnly=!s.lease||s.conflict;
  $('#edit-prompt').hidden=Boolean(s.lease)||s.conflict;
  document.querySelectorAll('#prompt-title,#prompt-instruction,#blocks textarea').forEach(field=>field.readOnly=readOnly);
  document.querySelectorAll('[data-action="builder-note"],[data-action="builder-audio"],[data-action="builder-existing"],[data-action="remove-block"],[data-action="transcribe-block"],[data-action="apply-block-transcript"],[data-action="move-up"],[data-action="move-down"]').forEach(button=>{
    const index=s.draft.blocks.findIndex(block=>block.id===button.dataset.id);
    button.disabled=readOnly||(button.dataset.action==='move-up'&&index===0)||(button.dataset.action==='move-down'&&index===s.draft.blocks.length-1);
  });
  const text=previewPrompt(s.draft),missing=missingTranscripts(s.draft);
  $('#preview-text').textContent=text||'Your instruction and notes will appear here, in order.';$('#preview-count').textContent=`${text.length.toLocaleString()} characters`;
  $('#piece-count').textContent=`${s.draft.blocks.length} attached`;
  $('#save-status').textContent=s.dirty?`Unsaved changes · ${s.recoveryState==='saved'?'saved in this browser':s.recoveryState==='unavailable'?'keep this tab open':'saving a browser copy'}`:'Synced with host';
  if($('#prompt-state'))$('#prompt-state').innerHTML=badge(s.saved.state)+((s.saved.changed_since_run||(s.dirty&&s.saved.last_run_revision))?badge('changed','Changed since last run'):'');
  $('#save-prompt').disabled=!s.dirty||!s.lease||s.conflict||savingPrompts.has(s.id);
  $('#save-prompt').textContent=savingPrompts.has(s.id)?'Saving…':'Save prompt';
  $('#run-prompt').disabled=s.dirty||missing.length>0||!text.trim()||s.conflict||savingPrompts.has(s.id)||Boolean(s.pendingRun);
  $('#run-prompt').textContent=s.runs.some(r=>r.prompt_id===s.draft.id&&r.prompt_revision===s.saved.revision)?'View Hermes reply':'Send to Hermes';
  $('#run-gate').innerHTML=missing.length?`<p class="notice">${missing.length} voice ${missing.length===1?'note needs a transcript':'notes need transcripts'} before this prompt can run. Transcribe it or type what Hermes should receive.</p>`:s.dirty?'<p class="progress-copy">Save your changes before running. Hermes receives the saved revision.</p>':'';
  $('#edit-notice').innerHTML=s.conflict?`<div class="notice error"><strong>This prompt changed on another device.</strong><p>Your browser draft is preserved. Compare both versions before deciding what to save.</p><div class="actions"><button data-action="resolve-conflict">Review versions</button><button data-action="copy-as-prompt">Save browser draft as a new prompt</button></div></div>`:!s.lease&&s.leaseDenied?`<div class="notice"><strong>Editing control is elsewhere.</strong><p>You can keep your draft here. Take over to save; the other device’s delayed saves will be rejected.</p><button data-action="takeover">Continue editing here</button></div>`:s.pendingRun?`<div class="notice"><strong>A Hermes submission needs confirmation.</strong><p>The previous request did not return a confirmed result. Checking keeps its original identity and will not send a second copy.</p><button data-action="retry-run">Check original request</button></div>`:!s.lease&&s.dirty?'<div class="notice"><strong>Your browser draft is preserved.</strong><p>Choose Edit prompt to continue and save your changes.</p></div>':'';
}
function dirty() {s.dirty=!sameContent(s.draft,s.saved);s.recoveryState='pending';clearTimeout(draftTimer);if(s.dirty)draftTimer=setTimeout(saveDraftLocal,250);else localDelete('draft:'+s.draft.id);updateEditorStatus();}
function redrawBlocks() {$('#blocks').innerHTML=s.draft.blocks.map(blockView).join('');dirty();}
async function acquireLease(takeover=false,quiet=false) {
  const operation={promptId:s.id,generation:s.loading};
  try {const lease=await api(`/prompts/${encodeURIComponent(operation.promptId)}/lease`,{method:'POST',body:{takeover}});if(!operationIsCurrent(operation,s.id,s.loading))return false;s.lease=lease;s.leaseDenied=false;return true;}catch(error){if(operationIsCurrent(operation,s.id,s.loading)){s.lease=null;s.leaseDenied=error.status===409;}if(!quiet && error.status!==409)throw error;return false;}
}
async function savePrompt() {
  if(!s.lease)throw new Error('Take over editing before saving this prompt.');
  if(s.conflict)throw new Error('Review the competing versions before saving.');
  const operation={promptId:s.id,generation:s.loading},submitted=structuredClone(s.draft),baseRevision=s.saved.revision,leaseToken=s.lease.lease_token;
  if(savingPrompts.has(operation.promptId))return;
  savingPrompts.add(operation.promptId);updateEditorStatus();
  await saveDraftLocal();
  try {
    const saved=await api(`/prompts/${encodeURIComponent(operation.promptId)}`,{method:'PUT',body:{base_revision:baseRevision,lease_token:leaseToken,...draftContent(submitted)}});
    if(operationIsCurrent(operation,s.id,s.loading)&&s.draft){
      Object.assign(s,reconcileSavedDraft(s.draft,submitted,saved));s.conflict=false;
      if(s.dirty)await saveDraftLocal();else await localDelete('draft:'+operation.promptId);
      if(!operationIsCurrent(operation,s.id,s.loading))return;
      $('#revision-label').textContent=`Revision ${saved.revision}`;updateEditorStatus();await saveResume();
      toast(s.dirty?'Submitted version synced. Your newer edits are still unsaved.':'Prompt synced. Available on your R1s.');
    }else{
      const key='draft:'+operation.promptId,local=await localGet(key),recovered=recoveryAfterSave(local,submitted,saved,baseRevision);
      if(recovered)await localPut(key,recovered);else await localDelete(key);
      toast('Prompt synced: '+(saved.title||'Untitled prompt'));
    }
  }catch(error){if(operationIsCurrent(operation,s.id,s.loading)&&(error.status===409||error.status===423)){s.conflict=true;s.lease=null;updateEditorStatus();}throw error;}
  finally{savingPrompts.delete(operation.promptId);if(operationIsCurrent(operation,s.id,s.loading))updateEditorStatus();}
}
async function saveResume(blockId) {
  if(!s.draft||s.dirty||!s.lease)return;
  try{s.resume=await api('/resume',{method:'PUT',body:{prompt_id:s.id,block_id:blockId||s.activeBlock||null,scroll_y:Math.round(window.scrollY),base_revision:s.resume?.revision||0}});}catch(error){if(error.status===409)s.resume=await api('/resume');}
}
function submissionView(run,{primary=false}={}) {
  const approval=run.approval,hermes=run.hermes;
  return `<section class="run-result"><div class="inline spread"><h3>${primary?'Hermes reply':'Saved revision '+h(run.prompt_revision)}</h3>${badge(run.status)}</div><p class="muted small-copy">Revision ${h(run.prompt_revision)} · ${h(stamp(run.created_at))}</p>${run.status==='submission_unknown'?'<p class="notice">Hermes has not confirmed whether it accepted this request. The host keeps the original identity and checks for its result.</p>':''}${run.error?`<p class="notice error">${h(typeof run.error==='string'?run.error:JSON.stringify(run.error))}</p>`:''}${run.output?`<div class="run-output">${h(typeof run.output==='string'?run.output:JSON.stringify(run.output,null,2))}</div>`:`<p class="muted small-copy">${run.status==='completed'?'Completed; no text output was returned.':isRunActive(run)?'Waiting for a confirmed result from Hermes.':'No saved reply.'}</p>`}${approval?`<div class="notice"><strong>Hermes needs permission</strong><p>${h(approval.message||approval.description||approval.summary||'Review this request before allowing Hermes to continue.')}</p>${approval.tool||approval.command?`<p><code>${h(typeof (approval.command||approval.tool)==='string'?(approval.command||approval.tool):JSON.stringify(approval.command||approval.tool))}</code></p>`:''}<div class="actions"><button class="primary small" data-action="approve-run" data-id="${h(run.id)}" data-approval="${h(approval.request_id||approval.id||approval.approval_id)}">Allow once</button><button class="small" data-action="deny-run" data-id="${h(run.id)}" data-approval="${h(approval.request_id||approval.id||approval.approval_id)}">Deny</button></div></div>`:''}${run.snapshot||hermes?.session_id||hermes?.run_id?`<details class="run-history"><summary>What was sent to Hermes</summary>${run.snapshot?`<div class="preview-text">${h(run.snapshot.preview||previewPrompt(run.snapshot))}</div>`:''}${hermes?.session_id?`<p class="muted small-copy">Hermes session: <code>${h(hermes.session_id)}</code></p>`:''}${hermes?.run_id?`<p class="muted small-copy">Hermes request: <code>${h(hermes.run_id)}</code></p>`:''}</details>`:''}<div class="actions">${run.status==='submission_unknown'?`<button class="small" data-action="reconcile-run" data-id="${h(run.id)}">Check submission</button>`:''}${isRunActive(run)?`<button class="small" data-action="stop-run" data-id="${h(run.id)}" ${run.status==='stopping'?'disabled':''}>${run.status==='stopping'?'Stop requested':'Stop Hermes request'}</button>`:''}</div></section>`;
}
function renderPromptResult() {
  const el=$('#prompt-result');if(!el||!s.draft)return;
  const {selected,earlier}=promptSubmissions(s.runs,s.draft.id,s.selectedRunId);
  if(!selected){el.innerHTML='<p class="progress-copy">Send this saved prompt to Hermes. Its progress and reply will appear here.</p>';return;}
  el.innerHTML=submissionView(selected,{primary:true})+(earlier.length?`<details class="run-history"><summary>${earlier.some(run=>run.created_at>selected.created_at)?'Other':'Earlier'} submissions of this prompt (${earlier.length})</summary>${earlier.map(run=>submissionView(run)).join('')}</details>`:'')+(!s.dirty&&!s.pendingRun&&s.runs.some(r=>r.prompt_id===s.draft.id&&r.prompt_revision===s.saved.revision)&&!s.runs.some(r=>r.prompt_id===s.draft.id&&r.prompt_revision===s.saved.revision&&isRunActive(r))?'<button class="small ghost" data-action="run-again">Send to Hermes again</button>':'');
}

async function runPrompt(runAgain=false,retry=false) {
  if(s.dirty)throw new Error('Save this prompt before sending it to Hermes.');
  if(savingPrompts.has(s.id))throw new Error('Wait for this prompt to finish saving.');
  if(missingTranscripts(s.draft).length)throw new Error('Add the missing voice transcripts before running.');
  const operation={promptId:s.id,generation:s.loading};
  const existing=s.runs.find(r=>r.prompt_id===s.id&&r.prompt_revision===s.saved.revision);
  if(existing&&!runAgain&&!retry){s.selectedRunId=existing.id;renderPromptResult();$('#prompt-result')?.focus();$('#prompt-result')?.scrollIntoView({block:'nearest'});return;}
  if(s.pendingRun&&!retry)throw new Error('Check the original pending request before sending another copy to Hermes.');
  const request=retry?s.pendingRun:{revision:s.saved.revision,request_id:uid(),run_again:runAgain};
  if(!request)throw new Error('No pending Hermes submission. Refresh to see the latest state.');
  s.pendingRun=request;updateEditorStatus();await localPut('run:'+operation.promptId,request);
  try {const run=await api(`/prompts/${encodeURIComponent(operation.promptId)}/run`,{method:'POST',body:request});await localDelete('run:'+operation.promptId);s.runs=[run,...s.runs.filter(r=>r.id!==run.id)];if(operationIsCurrent(operation,s.id,s.loading)){s.pendingRun=null;s.selectedRunId=run.id;const saved=await api(`/prompts/${encodeURIComponent(operation.promptId)}`);if(operationIsCurrent(operation,s.id,s.loading)){s.saved={...s.saved,state:saved.state,last_run_id:saved.last_run_id,last_run_revision:saved.last_run_revision,changed_since_run:saved.changed_since_run};updateEditorStatus();renderPromptResult();}}toast('Submission saved. Hermes progress and its reply stay with this prompt.');}
  catch(error){if(error.status>=400&&error.status<500){if(operationIsCurrent(operation,s.id,s.loading))s.pendingRun=null;await localDelete('run:'+operation.promptId);}if(operationIsCurrent(operation,s.id,s.loading))updateEditorStatus();throw error;}
}

async function loadDevice() {const id=s.id;if(!id){s.device=null;s.commands=[];return;}const [device,commands]=await Promise.all([api(`/devices/${encodeURIComponent(id)}`),api(`/devices/${encodeURIComponent(id)}/commands`)]);if(s.id!==id||s.view!=='devices')return;s.device=device;s.commands=commands.items;const entries=await localEntries();for(const entry of entries.filter(e=>e.key.startsWith('command:'))){if(s.commands.some(c=>c.id===entry.value.request.id))await localDelete(entry.key);}s.recovery=await localEntries();}
function renderDevices() {
  $('#content').innerHTML=head('Devices','Your R1s, connected to one workspace.','<button data-action="refresh">Refresh</button><button class="primary" data-action="enroll">Pair an R1</button>')+
    (s.devices.length?`<div class="device-layout"><nav class="device-list" aria-label="Your devices">${s.devices.map(d=>`<button class="device-item ${s.id===d.id?'active':''}" data-action="select-device" data-id="${h(d.id)}" ${s.id===d.id?'aria-current="true"':''}><span class="robot-mark" aria-hidden="true"></span><span><strong>${h(d.name||'R1')}</strong><small>${h(relative(d.last_seen))}${d.lost?' · Marked lost':d.revoked?' · Access revoked':''}</small></span></button>`).join('')}</nav><div id="device-detail">${deviceDetail()}</div></div>`:empty('Give each R1 a place here','Pair your first device using a short-lived code. Each R1 receives its own identity and can resume the same workspace.','<button class="primary" data-action="enroll">Pair an R1</button>'));
}
function deviceDetail() {
  const d=s.device;if(!d)return '';
  const location=locationDescription(d.location), permissions=d.applied_settings||{}, policy=recoveryPolicy(d), security=securityPosture(d);
  const commands=['ring','stop_ring','show_message','locate','lock'];
  return `<header class="device-header"><div><h2>${h(d.name||'R1')}</h2><code>${h(d.id)}</code></div><div class="stack">${d.lost?badge('lost','Marked lost'):d.revoked?badge('lost','Access revoked'):badge('applied','Workspace access enabled')}<button class="small ghost" data-action="rename-device">Rename</button></div></header><div class="notice"><strong>${h(policy.title)}</strong><p>${h(policy.detail)}</p>${policy.confirmed&&d.policy_applied_at?`<p>Confirmed ${h(stamp(d.policy_applied_at))}</p>`:''}${d.lost?`<p>Return message: ${h(d.lost_message||'No message set.')}</p>`:''}</div><dl class="facts"><div class="fact"><dt>Battery</dt><dd>${Number.isFinite(d.battery)?h(d.battery)+'%':'Not reported'}${d.charging?' · Charging':''}</dd></div><div class="fact"><dt>Last connection</dt><dd title="${h(stamp(d.last_seen))}">${h(relative(d.last_seen))}</dd></div><div class="fact"><dt>robotOS version</dt><dd>${h(d.app_version||'Not reported')}</dd></div></dl><section class="device-section"><h3>Device protection</h3><p><strong>${h(security.title)}</strong></p><p>${h(security.detail)}</p><div class="setting-line"><span>Android lock</span><span>${h(security.lock)}</span></div><div class="setting-line"><span>Remote secure lock</span><span>${h(security.remoteLock)}</span></div><div class="setting-line"><span>Lock acknowledgement</span><span>${h(security.appliedLock)}</span></div><p class="progress-copy">Reported at the last connection. App restrictions do not lock Android or prevent access outside robotOS. Local permissions are never enabled remotely.</p></section><section class="device-section"><h3>Recovery controls</h3><p>Actions wait for a device connection. Capability and permission must be enabled on that R1 first.</p><div class="command-grid">${commands.map(type=>{const support=commandSupport(d,type);return `<button data-action="device-command" data-type="${type}" ${support.enabled?'':'disabled'}>${commandLabel(type)}${!support.enabled?`<small>${h(support.reason)}</small>`:''}</button>`;}).join('')}</div><div class="setting-line"><span>Location</span><span>${location?`${h(location.coordinates)}<br>${h(location.accuracy)}<br>Observed ${h(stamp(location.observed_at))}`:'No location reported'}</span></div><div class="setting-line"><span>Recovery check-ins</span><span>${permissions.recovery_checkins?'Enabled on device':'Off or not reported'}</span></div><p class="progress-copy">A device with its radios off cannot receive recovery commands until it reconnects.</p><div class="actions"><button class="${d.lost?'':'danger'}" data-action="toggle-lost">${d.lost?'Mark as found':'Mark device lost'}</button><button class="ghost" data-action="toggle-revoked">${d.revoked?'Restore workspace access':'Revoke workspace access'}</button></div></section><section class="device-section"><div class="inline spread"><h3>Device settings</h3><button class="small" data-action="device-settings" ${policy.restricted?'disabled':''}>Edit settings</button></div><p>${h(describeSync(d))}</p>${[['quiet','Quiet mode'],['brightness','Brightness'],['text_size_sp','Text size'],['screen_timeout_sec','Screen timeout']].map(([key,label])=>`<div class="setting-line"><span>${label}</span><span>Saved: ${h(settingValue(key,d.desired_settings?.[key]))}<br>Applied: ${h(settingValue(key,d.applied_settings?.[key]))}</span></div>`).join('')}<details class="run-history"><summary>Permissions reported by the R1</summary>${['remote_management','location_reporting','secure_lock','allow_clear_workspace','allow_factory_reset','allow_updates','allow_reboot'].map(key=>`<div class="setting-line"><span>${h(key.replaceAll('_',' '))}</span><span>${permissions[key]===true?'Allowed':permissions[key]===false?'Off':'Not reported'}</span></div>`).join('')}</details></section><section class="device-section"><h3>Maintenance</h3><p>These actions require your password again and confirmation of the target device.</p><div class="command-grid">${['update_app','reboot','clear_workspace','factory_reset'].map(type=>{const support=commandSupport(d,type);return `<button class="${['clear_workspace','factory_reset'].includes(type)?'danger':''}" data-action="device-command" data-type="${type}" ${support.enabled?'':'disabled'}>${commandLabel(type)}${!support.enabled?`<small>${h(support.reason)}</small>`:''}</button>`;}).join('')}</div></section><section class="device-section"><h3>Command receipts</h3>${s.recovery.filter(r=>r.key.startsWith('command:')&&r.value.device_id===d.id).map(r=>`<div class="notice"><strong>Request not yet confirmed by host</strong><p>${h(commandLabel(r.value.request.type))}</p><button class="small" data-action="pending-command" data-id="${h(r.value.request.id)}">Check original request</button></div>`).join('')}<p>Queued means saved on the host. Applied means the device reported completion.</p>${s.commands.length?`<div class="table-scroll"><table class="receipt-table"><thead><tr><th>Command</th><th>Status</th><th>Requested</th></tr></thead><tbody>${[...s.commands].sort((a,b)=>b.created_at-a.created_at).map(c=>`<tr><td>${h(commandLabel(c.type))}${c.result&&(typeof c.result==='string'?c.result:Object.keys(c.result).length)?`<div class="receipt-detail">${h(typeof c.result==='string'?c.result:JSON.stringify(c.result))}</div>`:''}</td><td>${badge(c.status)}</td><td>${h(stamp(c.created_at))}</td></tr>`).join('')}</tbody></table></div>`:'<p class="muted small-copy">No commands sent to this device yet.</p>'}</section>`;
}
function settingValue(key,value) {if(value==null)return 'Not reported';if(typeof value==='boolean')return value?'On':'Off';return key==='screen_timeout_sec'?`${value} seconds`:key==='text_size_sp'?`${value} sp`:String(value);}
function renderAudit() {$('#content').innerHTML=head('Device activity','Administrative history for your workspace and devices.','<button data-action="refresh">Refresh</button>')+(s.audit.length?`<div class="table-scroll"><table class="receipt-table"><thead><tr><th>Action</th><th>Actor</th><th>Device</th><th>Time</th></tr></thead><tbody>${[...s.audit].sort((a,b)=>b.created_at-a.created_at).map(a=>`<tr><td class="audit-detail">${h((a.action||'Event').replaceAll('_',' '))}</td><td>${h(a.actor||'Host')}</td><td>${h(a.device_id||'—')}</td><td>${h(stamp(a.created_at))}</td></tr>`).join('')}</tbody></table></div>`:empty('No administrative activity yet','Pair a device or change its settings and the event will appear here.'));
}

function openDialog(title,content,onSubmit=null,{wide=false,onClose=null}={}) {
  closeDialog();
  const dialog=$('#dialog');dialogContext={id:uid(),onClose};
  dialog.innerHTML=`<div class="dialog-head"><h2 id="dialog-title">${h(title)}</h2><button class="icon-button ghost" data-action="close-dialog" aria-label="Close dialog">×</button></div><div id="dialog-error" class="dialog-error" role="alert" hidden></div>${onSubmit?'<form id="dialog-form">':''}${content}${onSubmit?'</form>':''}`;
  dialog.dataset.busy='false';dialog.showModal();dialog.querySelector('input,textarea,select')?.focus();
  if(onSubmit)$('#dialog-form').addEventListener('submit',async event=>{event.preventDefault();if(dialog.dataset.busy==='true')return;dialog.dataset.busy='true';const form=event.currentTarget,data=new FormData(form),button=event.submitter,label=button?.textContent;const fields=[...form.elements].map(el=>[el,el.disabled]);fields.forEach(([el])=>el.disabled=true);if(button)button.textContent='Working…';try{await onSubmit(data,form,event.submitter);}catch(error){dialogError(error.message);}finally{dialog.dataset.busy='false';fields.forEach(([el,disabled])=>{if(el.isConnected)el.disabled=disabled;});if(button?.isConnected)button.textContent=label;if(dialogContext?.capture?.admission){const record=$('#record-toggle'),file=$('#audio-file');if(record)record.disabled=true;if(file)file.disabled=true;}}});
}
function stopRecording() {clearInterval(recordingClock);if(mediaRecorder?.state==='recording')mediaRecorder.stop();recordingStream?.getTracks().forEach(track=>track.stop());recordingStream=null;}
function closeDialog() {stopRecording();dialogContext?.onClose?.();dialogContext=null;const dialog=$('#dialog');if(dialog.open)dialog.close();dialog.innerHTML='';}
const dialogButtons=(label='Save',danger=false)=>`<div class="dialog-actions"><button type="button" class="ghost" data-action="close-dialog">Cancel</button><button type="submit" class="${danger?'danger':'primary'}">${h(label)}</button></div>`;
function noteBlock(note) {return {id:uid(),note_id:note.id,kind:note.kind,text:note.text||'',...(note.media_id?{media_id:note.media_id}:{})};}
function showNote(note=null,{kind='text',builder=false,recovery=null}={}) {
  let capture=recovery||{id:note?.id||uid(),uploadId:uid(),title:note?.title||'',kind:note?.kind||kind,text:note?.text||'',media_id:note?.media_id||null,blob:null};
  let objectUrl=null;
  const persist=()=>{capture.title=$('#note-title')?.value??capture.title;capture.text=$('#note-text')?.value??capture.text;if(!note&&!capture.synced&&(capture.title||capture.text||capture.blob))localPut('capture:'+capture.id,capture);};
  const audioUI=capture.kind==='audio'?`<div class="recording-control">${note?'':`<div class="inline spread"><button type="button" id="record-toggle">Start recording</button><span id="record-state" class="small-copy muted">Microphone off</span></div><p>Audio stays in this browser until the host confirms its upload. Recordings need a transcript before running a prompt.</p><label class="field"><span>Or choose an audio file</span><input type="file" id="audio-file" accept="audio/*"></label>`}<audio controls id="capture-preview" class="note-audio" ${capture.media_id?`src="/api/v1/media/${encodeURIComponent(capture.media_id)}"`:''} ${capture.media_id||capture.blob?'':'hidden'}></audio></div>`:'';
  openDialog(note?'Edit note':builder?'Add a note to this prompt':capture.kind==='audio'?'Capture a voice note':'Write a note',`<label class="field"><span>Title</span><input id="note-title" name="title" maxlength="200" value="${h(capture.title)}" placeholder="A name you’ll recognize" required></label>${audioUI}<label class="field"><span>${capture.kind==='audio'?'Transcript':'Note'}</span><textarea id="note-text" name="text" rows="7" placeholder="${capture.kind==='audio'?'Add a transcript now, or transcribe after saving.':'Write your thought…'}" ${capture.kind==='text'?'required':''}>${h(capture.text)}</textarea></label><div id="transcript-review"></div>${note?`<p class="muted small-copy">Editing this source note does not change pieces already attached to saved prompts.</p><div class="actions">${note.kind==='audio'?'<button type="button" data-action="transcribe-note" data-id="'+h(note.id)+'">Transcribe recording</button>':''}<button type="button" data-action="duplicate-note">Save as new copy</button><button type="button" class="danger" data-action="delete-note" data-id="${h(note.id)}">Delete note</button></div>`:''}<div id="capture-recovery-actions">${!note&&capture.admission?'<button type="button" data-action="duplicate-note">Save as new copy</button>':''}</div>${dialogButtons(builder?'Save and add to prompt':'Save note')}`,
    async data=>{
      if(mediaRecorder?.state==='recording')throw new Error('Stop the recording before saving.');
      capture.title=String(data.get('title')).trim();capture.text=String(data.get('text'));
      if(!capture.title)throw new Error('Give this note a title.');
      if(!note)await localPut('capture:'+capture.id,capture);
      if(capture.kind==='audio'&&!capture.media_id){if(!capture.blob)throw new Error('Record audio or choose an audio file first.');const media=await api('/media',{method:'POST',raw:true,body:capture.blob,headers:{'Content-Type':capture.blob.type||'audio/webm','X-Upload-Id':capture.uploadId}});capture.media_id=media.id;await localPut('capture:'+capture.id,capture);}
      let saved;
      try{
        if(note)saved=await api('/notes/'+encodeURIComponent(note.id),{method:'PUT',body:{base_revision:note.revision,title:capture.title,text:capture.text}});
        else {
          capture.admission=captureAdmission(capture);await localPut('capture:'+capture.id,capture);
          saved=await api('/notes',{method:'POST',body:capture.admission});
          if(saved.title!==capture.title||saved.text!==capture.text){
            if(saved.title!==capture.admission.title||saved.text!==capture.admission.text)throw new ApiError('This note changed elsewhere. Save your recovery as a new copy.',409,'note_changed');
            saved=await api('/notes/'+encodeURIComponent(saved.id),{method:'PUT',body:{base_revision:saved.revision,title:capture.title,text:capture.text}});
          }
        }
      }catch(error){if(!note){const recoveryActions=$('#capture-recovery-actions');if(recoveryActions)recoveryActions.innerHTML='<p class="muted small-copy">Your capture and original audio are kept here. Retry checks the original request; use a new copy if the source changed.</p><button type="button" data-action="duplicate-note">Save as new copy</button>';}if(error.status===409)throw new Error('This note changed elsewhere. Your edits and audio are still here. Use “Save as new copy” to keep them separately.');throw error;}
      capture.synced=true;await localDelete('capture:'+capture.id);s.notes=[saved,...s.notes.filter(n=>n.id!==saved.id)];closeDialog();
      if(builder&&s.draft){s.draft.blocks.push(noteBlock(saved));redrawBlocks();toast('Note synced and added. Save the prompt to sync its changes.');}
      else if(s.view==='notes'){s.recovery=await localEntries();renderNotes();toast('Note synced.');}
    },{onClose:()=>{persist();if(objectUrl)URL.revokeObjectURL(objectUrl);}});
  dialogContext.note=note;dialogContext.capture=capture;dialogContext.builder=builder;dialogContext.persist=persist;
  $('#note-title').addEventListener('input',persist);$('#note-text').addEventListener('input',persist);
  if(capture.kind==='audio'&&!note){
    const preview=blob=>{if(objectUrl)URL.revokeObjectURL(objectUrl);objectUrl=URL.createObjectURL(blob);$('#capture-preview').src=objectUrl;$('#capture-preview').hidden=false;};
    if(capture.blob)preview(capture.blob);if(capture.admission){$('#record-toggle').disabled=true;$('#audio-file').disabled=true;}
    $('#audio-file').addEventListener('change',event=>{const file=event.target.files[0];if(!file)return;capture.blob=file;capture.media_id=null;capture.uploadId=uid();preview(file);persist();});
    $('#record-toggle').addEventListener('click',async()=>{
      if(mediaRecorder?.state==='recording'){stopRecording();$('#record-toggle').textContent='Record again';return;}
      try{
        if(!navigator.mediaDevices?.getUserMedia||!window.MediaRecorder)throw new Error('Recording is unavailable in this browser. Use HTTPS or localhost, or choose an audio file.');
        recordingStream=await navigator.mediaDevices.getUserMedia({audio:true});recordingChunks=[];
        const mime=['audio/webm;codecs=opus','audio/mp4','audio/ogg;codecs=opus'].find(t=>MediaRecorder.isTypeSupported(t));
        mediaRecorder=new MediaRecorder(recordingStream,mime?{mimeType:mime}:undefined);
        mediaRecorder.ondataavailable=event=>{if(event.data.size){recordingChunks.push(event.data);capture.blob=new Blob(recordingChunks,{type:event.data.type||'audio/webm'});capture.title=$('#note-title')?.value??capture.title;capture.text=$('#note-text')?.value??capture.text;localPut('capture:'+capture.id,capture);}};
        mediaRecorder.onstop=async()=>{capture.blob=new Blob(recordingChunks,{type:mediaRecorder.mimeType||'audio/webm'});capture.media_id=null;capture.uploadId=uid();await localPut('capture:'+capture.id,capture);if(dialogContext?.capture===capture&&$('#capture-preview')){preview(capture.blob);$('#record-state').className='small-copy muted';$('#record-state').textContent='Recording saved in this browser';$('#record-toggle').textContent='Record again';}};
        mediaRecorder.start(1000);recordingStart=Date.now();$('#record-toggle').textContent='Stop recording';$('#record-state').className='small-copy recording';
        const tick=()=>{if($('#record-state')){const seconds=Math.floor((Date.now()-recordingStart)/1000);$('#record-state').textContent=`Recording ${Math.floor(seconds/60)}:${String(seconds%60).padStart(2,'0')}`;}};tick();recordingClock=setInterval(tick,1000);
      }catch(error){dialogError(error.message);}
    });
  }
}
function showNewPrompt(blocks=[]) {const id=uid();openDialog('New prompt',`<label class="field"><span>Prompt title</span><input name="title" maxlength="200" placeholder="What are you putting together?" required></label><p class="muted small-copy">${blocks.length?`${blocks.length} selected pieces will be attached.`:'Add notes and recordings after creating your prompt.'}</p>${dialogButtons('Create prompt')}`,async data=>{const prompt=await api('/prompts',{method:'POST',body:{id,title:String(data.get('title')).trim(),instruction:'',blocks}});closeDialog();s.selectedNotes.clear();location.hash=link('prompts',prompt.id);});}
async function showAddToPrompt(ids) {
  if(!ids.length)return;
  const selected=s.notes.filter(n=>ids.includes(n.id));s.prompts=(await api('/prompts')).items;
  openDialog('Add notes to a prompt',`<p class="muted">${selected.length} ${selected.length===1?'note':'notes'} selected. Source notes stay in your notes collection.</p><label class="field"><span>Destination</span><select name="destination"><option value="new">Create a new prompt</option>${s.prompts.map(p=>`<option value="${h(p.id)}">${h(p.title||'Untitled prompt')}</option>`).join('')}</select></label>${dialogButtons('Continue')}`,async data=>{const destination=String(data.get('destination'));closeDialog();if(destination==='new'){showNewPrompt(selected.map(noteBlock));return;}const pending=selected.map(noteBlock);await localPut('append:'+destination,pending);if(location.hash===link('prompts',destination))await navigate();else location.hash=link('prompts',destination);});
}
async function showExistingNotes() {
  s.notes=(await api('/notes')).items;
  openDialog('Add existing notes',`${s.notes.length?'<p class="muted small-copy">Choose several notes. Their current text is copied into this prompt; the sources remain reusable.</p><div class="pick-list">'+s.notes.map(n=>`<label class="pick-row"><input type="checkbox" name="note" value="${h(n.id)}"><span><strong>${h(n.title||'Untitled note')}</strong><small>${n.kind==='audio'?'Voice note':'Written note'} · ${h(n.text||'Transcript needed')}</small></span></label>`).join('')+'</div>':'<p class="muted">No saved notes yet. Write or record a note directly in the builder.</p>'}${dialogButtons('Add selected')}`,async data=>{const ids=data.getAll('note');if(!ids.length)throw new Error('Select at least one note.');s.draft.blocks.push(...s.notes.filter(n=>ids.includes(n.id)).map(noteBlock));closeDialog();redrawBlocks();await saveDraftLocal();});
}
async function showConflict() {
  const latest=await api('/prompts/'+encodeURIComponent(s.id));
  openDialog('Review both versions',`<p class="muted">Your draft has been preserved. Choose which content to keep, or save your draft as a separate prompt.</p><div class="conflict-compare"><section><h3>Saved on host · revision ${h(latest.revision)}</h3><pre>${h(latest.title+'\n\n'+previewPrompt(latest))}</pre></section><section><h3>Your browser draft</h3><pre>${h(s.draft.title+'\n\n'+previewPrompt(s.draft))}</pre></section></div><div class="dialog-actions"><button type="button" data-action="use-server-version">Use host version</button><button type="button" data-action="copy-as-prompt">Save as new prompt</button><button type="button" class="primary" data-action="keep-local-version">Keep browser draft</button></div>`);
  dialogContext.latest=latest;
}
async function showEnroll() {const requestId=uid();openDialog('Pair an R1',`<p class="muted">Create a code, then enter it in the R1’s shared workspace settings. The code expires and can be used once.</p><label class="field"><span>Device name</span><input name="name" required maxlength="100" placeholder="For example, Desk R1"></label>${dialogButtons('Create pairing code')}`,async data=>{const enrollment=await api('/enrollments',{method:'POST',body:{name:String(data.get('name')).trim(),request_id:requestId}});openDialog('Enter this code on your R1',`<p>Open <strong>Settings → Shared workspace</strong> on the R1 and enter your host address and this code.</p><div class="pair-code">${h(enrollment.code)}</div><p class="muted small-copy">Expires ${h(stamp(enrollment.expires_at))}. Anyone with this code and access to your host can pair a device.</p><div class="dialog-actions"><button class="primary" data-action="finish-enroll">Done</button></div>`);});}
function showDeviceSettings() {
  const d=s.device,settings={...d.applied_settings,...d.desired_settings};
  openDialog('Settings for '+d.name,`<p class="muted small-copy">Saved settings take effect when this R1 next connects. The console shows its confirmation separately.</p><label class="check"><input type="checkbox" name="quiet" ${settings.quiet?'checked':''}>Quiet mode</label><hr><label class="field"><span>Brightness (1–255)</span><input name="brightness" type="number" min="1" max="255" value="${h(settings.brightness??128)}" required></label><label class="field"><span>Text size (14–26 sp)</span><input name="text_size_sp" type="number" min="14" max="26" value="${h(settings.text_size_sp??18)}" required></label><label class="field"><span>Screen timeout</span><select name="screen_timeout_sec">${[10,15,30,60,120].map(n=>`<option value="${n}" ${settings.screen_timeout_sec===n?'selected':''}>${n} seconds</option>`).join('')}</select></label>${dialogButtons('Save settings')}`,async data=>{await api(`/devices/${encodeURIComponent(d.id)}`,{method:'PATCH',body:{base_revision:d.revision,settings:{quiet:data.has('quiet'),brightness:Number(data.get('brightness')),text_size_sp:Number(data.get('text_size_sp')),screen_timeout_sec:Number(data.get('screen_timeout_sec'))}}});closeDialog();await refreshDevice();toast('Settings saved. Waiting for device confirmation.');});
}
async function refreshDevice() {await loadDevice();s.devices=(await api('/devices')).items;if(s.view==='devices')renderDevices();}
function showDevicePolicy(type) {
  const d=s.device,isLost=type==='lost',enabled=isLost?d.lost:d.revoked;
  const title=isLost?(enabled?'Mark this device as found?':'Mark this device lost?'):(enabled?'Restore workspace access?':'Revoke workspace access?');
  openDialog(title,`<p><strong>${h(d.name)}</strong></p><p class="muted">${enabled?'This allows the device to access your workspace again once all lost and revoked restrictions are cleared.':'Shared notes, recordings and agent access will be blocked immediately on the host. Recovery commands remain available. The saved policy has no expiry. Local restrictions take priority when the device next connects; pending ordinary device actions are cancelled.'}</p>${isLost&&!enabled?'<label class="field"><span>Return message</span><textarea name="message" maxlength="500" placeholder="If found, please contact…">'+h(d.lost_message||'')+'</textarea></label>':''}${dialogButtons(enabled?'Restore access':isLost?'Mark lost':'Revoke access',!enabled)}`,async data=>{await api(`/devices/${encodeURIComponent(d.id)}`,{method:'PATCH',body:{base_revision:d.revision,[type]:!enabled,...(isLost&&!enabled?{lost_message:String(data.get('message'))}:{})}});closeDialog();await refreshDevice();toast(enabled?'Device policy updated.':'Access blocked on the host. Local confirmation is shown separately.');});
}
function showCommand(type) {
  const d=s.device,support=commandSupport(d,type);if(!support.enabled){toast(support.reason);return;}
  const dangerous=isDangerous(type),id=uid();let stableRequest=null;
  const description={ring:'Ring the R1 for up to 30 seconds. Stop it from this console or on the device.',stop_ring:'Request that the R1 stop its recovery sound.',show_message:'Display owner-provided return information on this device.',locate:'Request a location fix using the permission already granted on the R1. Accuracy and observation time will be shown.',lock:'Request the secure Android lock. The console waits for the device to confirm it.',clear_workspace:'Erase shared notes, prompts, recordings and cached workspace credentials from this R1. Your host workspace and other devices are preserved. Recovery access remains; this R1 must be paired again for workspace access.',factory_reset:'Erase this entire R1 using its Android device policy. This cannot be undone. The device must be enrolled and locally permitted to reset.',update_app:'Install a verified, signed robotOS release from the device’s official release feed. The existing installer checks the release and may require confirmation on the R1. Completion stays unconfirmed until the installed version is reported.',reboot:'Restart this R1. Unsaved activity on the device may be interrupted.'}[type];
  openDialog(commandLabel(type),`<p><strong>${h(d.name)}</strong></p><p class="muted">${h(description)}</p>${type==='show_message'?'<label class="field"><span>Message</span><textarea name="message" maxlength="500" required>'+h(d.lost_message||'')+'</textarea></label>':''}${type==='update_app'?'<label class="field"><span>Target release version code</span><input name="version_code" type="number" min="1" step="1" required></label>':''}${dangerous?`<label class="field"><span>Type “${h(d.name)}” to confirm this R1</span><input name="confirmation" autocomplete="off" required></label><label class="field"><span>Owner password</span><input name="password" type="password" autocomplete="current-password" required></label>`:''}<label class="field"><span>Expire request if the device does not connect within</span><select name="expiry"><option value="300000">5 minutes</option><option value="3600000">1 hour</option>${type==='ring'||type==='stop_ring'?'':'<option value="86400000">24 hours</option>'}</select></label>${dialogButtons('Send request',dangerous)}`,async(data,form)=>{
    if(dangerous){if(String(data.get('confirmation'))!==d.name)throw new Error('Type the device name exactly to confirm the target.');s.session=await api('/session',{method:'POST',body:{password:String(data.get('password'))}});form.password.value='';}
    const payload=type==='ring'?{duration_seconds:30}:type==='show_message'?{message:String(data.get('message'))}:type==='update_app'?{version_code:Number(data.get('version_code'))}:{};
    const request=stableRequest||{id,type,payload,expires_at:Date.now()+Number(data.get('expiry')),...(dangerous?{confirmation:d.id}:{})};stableRequest=request;
    await localPut('command:'+id,{device_id:d.id,request});
    const command=await api(`/devices/${encodeURIComponent(d.id)}/commands`,{method:'POST',body:request});await localDelete('command:'+id);closeDialog();await refreshDevice();toast(`${commandLabel(type)}: ${statusLabel(command.status)}. Device confirmation appears in command receipts.`);
  });
}
function showPendingCommand(id) {
  const entry=s.recovery.find(r=>r.key==='command:'+id);if(!entry)return;
  const {device_id,request}=entry.value,d=s.device;
  if(d.id!==device_id)throw new Error('Open the original target device to check this command.');
  const dangerous=isDangerous(request.type);
  openDialog('Check original command',`<p><strong>${h(commandLabel(request.type))} · ${h(d.name)}</strong></p><p class="muted">The host did not confirm this request. Retrying sends its original target, payload, identity and expiry so an accepted command cannot be duplicated.</p><p class="muted small-copy">Request expires ${h(stamp(request.expires_at))}.</p>${dangerous?`<label class="field"><span>Type “${h(d.name)}” to confirm this R1</span><input name="confirmation" autocomplete="off" required></label><label class="field"><span>Owner password</span><input name="password" type="password" autocomplete="current-password" required></label>`:''}${dialogButtons('Check original request',dangerous)}`,async(data,form)=>{if(dangerous){if(String(data.get('confirmation'))!==d.name)throw new Error('Type the device name exactly.');s.session=await api('/session',{method:'POST',body:{password:String(data.get('password'))}});form.password.value='';}const result=await api(`/devices/${encodeURIComponent(d.id)}/commands`,{method:'POST',body:request});await localDelete('command:'+id);closeDialog();await refreshDevice();toast('Original request: '+statusLabel(result.status));});
}

async function handleAction(button) {
  const action=button.dataset.action,id=button.dataset.id;
  if(action==='close-dialog'){if($('#dialog').dataset.busy!=='true')closeDialog();return;}
  if(action==='refresh'){await navigate();return;}
  if(action==='logout'){
    await saveDraftLocal();
    const entries=await localEntries();
    if(s.dirty||entries.length){openDialog('Sign out with unsaved work?',`<p class="muted">${entries.length} recovery ${entries.length===1?'item is':'items are'} saved only in this browser. Signing out clears these copies. Choose “Keep working” to finish saving notes and prompts or check uncertain requests first.</p><div class="pick-list">${entries.map(entry=>`<div class="pick-row"><span><strong>${h(entry.value.title||s.prompts.find(p=>entry.key.endsWith(p.id))?.title||(entry.key.startsWith('command:')?commandLabel(entry.value.request.type):'Pending request'))}</strong><small>${h(entry.key.startsWith('capture:')?'Note or recording not confirmed by host':entry.key.startsWith('draft:')?'Unsaved prompt changes':entry.key.startsWith('append:')?'Notes waiting to be added to a prompt':'Request outcome needs confirmation')}</small></span></div>`).join('')}</div><div class="dialog-actions"><button class="primary" data-action="close-dialog">Keep working</button><button class="danger" data-action="confirm-logout">Discard browser copies and sign out</button></div>`);return;}
    await logout();return;
  }
  if(action==='confirm-logout'){s.dirty=false;closeDialog();await logout();return;}
  if(action==='new-prompt'){showNewPrompt();return;}
  if(action==='new-note'){showNote();return;}
  if(action==='new-audio'){showNote(null,{kind:'audio'});return;}
  if(action==='edit-note'){showNote(s.notes.find(n=>n.id===id));return;}
  if(action==='recover-capture'){const capture=await localGet('capture:'+id);if(capture)showNote(null,{kind:capture.kind,recovery:capture});return;}
  if(action==='clear-selection'){s.selectedNotes.clear();document.querySelectorAll('[data-select-note]').forEach(e=>e.checked=false);renderSelection();return;}
  if(action==='selection-to-prompt'){await showAddToPrompt([...s.selectedNotes]);return;}
  if(action==='note-to-prompt'){await showAddToPrompt([id]);return;}
  if(action==='builder-note'){showNote(null,{builder:true});return;}
  if(action==='builder-audio'){showNote(null,{kind:'audio',builder:true});return;}
  if(action==='builder-existing'){await showExistingNotes();return;}
  if(action==='move-up'||action==='move-down'){const index=s.draft.blocks.findIndex(b=>b.id===id),next=index+(action==='move-up'?-1:1);if(index>=0&&next>=0&&next<s.draft.blocks.length){[s.draft.blocks[index],s.draft.blocks[next]]=[s.draft.blocks[next],s.draft.blocks[index]];redrawBlocks();await saveDraftLocal();document.getElementById('block-'+id)?.focus();}return;}
  if(action==='remove-block'){s.draft.blocks=s.draft.blocks.filter(b=>b.id!==id);if(s.activeBlock===id)s.activeBlock=null;redrawBlocks();await saveDraftLocal();toast('Piece removed. Its source note is still saved.');return;}
  if(action==='copy-preview'){await navigator.clipboard.writeText(previewPrompt(s.draft));toast('Prompt preview copied.');return;}
  if(action==='save-prompt'){await savePrompt();return;}
  if(action==='edit-prompt'||action==='takeover'){
    const operation={promptId:s.id,generation:s.loading};
    const ok=await acquireLease(action==='takeover');if(!operationIsCurrent(operation,s.id,s.loading))return;if(!ok){updateEditorStatus();return;}
    const latest=await api('/prompts/'+encodeURIComponent(operation.promptId));
    if(!operationIsCurrent(operation,s.id,s.loading))return;
    if(latest.revision!==s.saved.revision){if(s.dirty)s.conflict=true;else{s.saved=latest;s.draft=structuredClone(latest);renderBuilder();}}
    updateEditorStatus();toast('Ready to edit this prompt.');return;
  }
  if(action==='resolve-conflict'){await showConflict();return;}
  if(action==='use-server-version'){const latest=dialogContext.latest;s.saved=latest;s.draft=structuredClone(latest);s.dirty=false;s.conflict=false;await localDelete('draft:'+s.id);closeDialog();await acquireLease(false,true);renderBuilder();return;}
  if(action==='keep-local-version'){const latest=dialogContext.latest;s.saved=latest;s.conflict=false;closeDialog();await acquireLease(true);s.dirty=true;await saveDraftLocal();renderBuilder();toast('Browser draft kept. Review it, then save to replace the host revision.');return;}
  if(action==='copy-as-prompt'){const original=s.draft,copy=await api('/prompts',{method:'POST',body:{id:uid(),...draftContent(original),title:(original.title||'Untitled prompt')+' (browser copy)'}});await localDelete('draft:'+original.id);s.dirty=false;closeDialog();location.hash=link('prompts',copy.id);return;}
  if(action==='run-prompt'){await runPrompt();return;}
  if(action==='retry-run'){await runPrompt(false,true);return;}
  if(action==='run-again'){openDialog('Send this saved prompt to Hermes again?',`<p class="muted">This sends another copy of revision ${h(s.saved.revision)}. Hermes may repeat any actions in the prompt.</p>${dialogButtons('Send again')}`,async()=>{closeDialog();await runPrompt(true);});return;}
  if(action==='reconcile-run'){const run=await api(`/runs/${encodeURIComponent(id)}/reconcile`,{method:'POST',body:{}});s.runs=[run,...s.runs.filter(r=>r.id!==run.id)];renderPromptResult();toast('Submission checked using its original identity.');return;}
  if(action==='stop-run'||action==='approve-run'||action==='deny-run'){
    const key=`operation:${id}:${action}:${button.dataset.approval||''}`;
    const requestId=await localGet(key)||uid();await localPut(key,requestId);
    const endpoint=action==='stop-run'?'stop':'approval';
    const updated=await api(`/runs/${encodeURIComponent(id)}/${endpoint}`,{method:'POST',body:{request_id:requestId,...(endpoint==='approval'?{approval_id:button.dataset.approval,choice:action==='approve-run'?'once':'deny'}:{})}});
    await localDelete(key);s.runs=[updated,...s.runs.filter(r=>r.id!==updated.id)];renderPromptResult();toast(action==='stop-run'?'Stop requested. Waiting for confirmation.':'Approval decision sent.');return;
  }
  if(action==='transcribe-block'){
    const block=s.draft.blocks.find(b=>b.id===id);
    if(!block)return;
    const original={ownerId:s.id,generation:s.loading,itemId:block.id,text:block.text},noteId=block.note_id;
    const note=await api('/notes/'+encodeURIComponent(noteId));
    const updated=await api(`/notes/${encodeURIComponent(noteId)}/transcribe`,{method:'POST',body:{base_revision:note.revision}});
    s.notes=[updated,...s.notes.filter(n=>n.id!==updated.id)];
    const current=s.draft?.blocks.find(b=>b.id===id),target=current?{ownerId:s.id,generation:s.loading,itemId:current.id,text:current.text}:null;
    if(transcriptTargetUnchanged(original,target)&&current.note_id===noteId){current.text=updated.text;pendingTranscripts.delete(original.ownerId+':'+id);redrawBlocks();await saveDraftLocal();toast('Transcript added. Review it before saving your prompt.');}
    else{pendingTranscripts.set(original.ownerId+':'+id,{noteId,text:updated.text});if(s.id===original.ownerId&&s.draft)preserveView(()=>{$('#blocks').innerHTML=s.draft.blocks.map(blockView).join('');});toast('Transcript saved in the source note. Your current edits are preserved; review the generated transcript in the original prompt.');}
    return;
  }
  if(action==='apply-block-transcript'){
    const result=pendingTranscripts.get(s.id+':'+id),block=s.draft?.blocks.find(b=>b.id===id);
    if(result&&block&&block.note_id===result.noteId){block.text=result.text;pendingTranscripts.delete(s.id+':'+id);redrawBlocks();await saveDraftLocal();toast('Generated transcript added. Save the prompt when ready.');}return;
  }
  if(action==='transcribe-note'){
    const ctx=dialogContext,note=ctx.note,original={ownerId:note.id,generation:ctx.id,itemId:note.id,title:$('#note-title').value,text:$('#note-text').value};
    if(original.text!==note.text)throw new Error('Save your transcript edits before requesting transcription, or save them as a new note.');
    const updated=await api(`/notes/${encodeURIComponent(note.id)}/transcribe`,{method:'POST',body:{base_revision:note.revision}});s.notes=[updated,...s.notes.filter(n=>n.id!==note.id)];
    const current=dialogContext===ctx?{ownerId:ctx.note.id,generation:ctx.id,itemId:ctx.note.id,title:$('#note-title')?.value,text:$('#note-text')?.value}:null;
    if(transcriptTargetUnchanged(original,current)){Object.assign(note,updated);$('#note-text').value=updated.text;ctx.persist();toast('Transcript saved. Review it for accuracy.');}
    else if(dialogContext===ctx){Object.assign(note,updated);ctx.generatedTranscript=updated.text;$('#transcript-review').innerHTML=`<div class="notice"><strong>New transcript available</strong><p>Your edits are preserved. Save them to keep your version, or review the generated transcript below.</p><details><summary>Review generated transcript</summary><div class="preview-text">${h(updated.text)}</div><button type="button" class="small" data-action="apply-note-transcript">Use generated transcript</button></details></div>`;toast('Transcript saved on the host. Your current edits are preserved.');}
    else{if(s.view==='notes'&&!$('#dialog').open)renderNotes();toast('Transcript saved in its source note. Open that note to review it.');}
    return;
  }
  if(action==='apply-note-transcript'){if(dialogContext?.generatedTranscript!=null){$('#note-text').value=dialogContext.generatedTranscript;dialogContext.persist();$('#transcript-review').innerHTML='';toast('Generated transcript selected. Save the note when ready.');}return;}
  if(action==='duplicate-note'){
    const ctx=dialogContext,copy={...ctx.capture,id:uid(),title:$('#note-title').value+' (copy)',text:$('#note-text').value,uploadId:uid(),admission:null};
    closeDialog();showNote(null,{kind:copy.kind,recovery:copy});return;
  }
  if(action==='delete-note'){
    const note=dialogContext.note;openDialog('Delete this source note?',`<p><strong>${h(note.title)}</strong></p><p class="muted">Existing pieces in saved prompts remain intact. This removes the source from your notes collection.</p>${dialogButtons('Delete note',true)}`,async()=>{await api('/notes/'+encodeURIComponent(note.id),{method:'DELETE',headers:{'If-Match':String(note.revision)}});s.notes=s.notes.filter(n=>n.id!==note.id);s.selectedNotes.delete(note.id);closeDialog();if(s.view==='notes')renderNotes();toast('Source note deleted.');});return;
  }
  if(action==='enroll'){await showEnroll();return;}
  if(action==='finish-enroll'){closeDialog();await navigate();return;}
  if(action==='select-device'){location.hash=link('devices',id);return;}
  if(action==='rename-device'){
    const d=s.device;openDialog('Rename device',`<label class="field"><span>Device name</span><input name="name" maxlength="100" required value="${h(d.name)}"></label>${dialogButtons('Save name')}`,async data=>{await api(`/devices/${encodeURIComponent(d.id)}`,{method:'PATCH',body:{base_revision:d.revision,name:String(data.get('name')).trim()}});closeDialog();await refreshDevice();});return;
  }
  if(action==='device-settings'){showDeviceSettings();return;}
  if(action==='toggle-lost'){showDevicePolicy('lost');return;}
  if(action==='toggle-revoked'){showDevicePolicy('revoked');return;}
  if(action==='device-command'){showCommand(button.dataset.type);return;}
  if(action==='pending-command'){showPendingCommand(id);return;}
}
async function logout() {await api('/logout',{method:'POST',body:{}});clearInterval(pollTimer);s.session=null;s.draft=null;s.saved=null;s.dirty=false;s.selectedNotes.clear();await localClear();showLogin();}

document.addEventListener('click',async event=>{
  const button=event.target.closest('[data-action]');if(!button||button.disabled)return;
  event.preventDefault();button.disabled=true;
  try{await handleAction(button);}catch(error){if($('#dialog').open)dialogError(error.message);else pageError(error.message);}finally{if(button.isConnected)button.disabled=false;if(s.draft)updateEditorStatus();}
});
document.addEventListener('input',event=>{
  const el=event.target;
  if(s.draft){if(el.id==='prompt-title'){s.draft.title=el.value;dirty();}else if(el.id==='prompt-instruction'){s.draft.instruction=el.value;dirty();}else if(el.dataset.blockText){const block=s.draft.blocks.find(b=>b.id===el.dataset.blockText);if(block){block.text=el.value;dirty();}}}
  if(el.id==='note-search'||el.id==='prompt-search'){const selection=[el.selectionStart,el.selectionEnd];s.filter=el.value;el.id==='note-search'?renderNotes():renderPrompts();const input=document.getElementById(el.id);input.focus();input.setSelectionRange(...selection);}
});
document.addEventListener('change',event=>{const el=event.target;if(el.dataset.selectNote){el.checked?s.selectedNotes.add(el.dataset.selectNote):s.selectedNotes.delete(el.dataset.selectNote);renderSelection();}if(el.id==='note-kind'){s.kind=el.value;renderNotes();}});
document.addEventListener('focusin',event=>{if(s.draft&&event.target.dataset.blockText){clearTimeout(resumeTimer);const blockId=event.target.dataset.blockText;s.activeBlock=blockId;resumeTimer=setTimeout(()=>saveResume(blockId),700);}});
$('#dialog').addEventListener('cancel',event=>{event.preventDefault();if($('#dialog').dataset.busy!=='true')closeDialog();});
window.addEventListener('hashchange',()=>{if(s.session){closeDialog();navigate();}});
window.addEventListener('beforeunload',event=>{if(s.dirty||mediaRecorder?.state==='recording'){saveDraftLocal();event.preventDefault();event.returnValue='';}});
window.addEventListener('online',()=>poll());
document.addEventListener('visibilitychange',()=>{if(!document.hidden)poll();else saveDraftLocal();});

let polling=false;
function preserveView(render) {
  const focus=document.activeElement,descriptor=focus?.id?{id:focus.id}:focus?.dataset.action?{action:focus.dataset.action,id:focus.dataset.id,type:focus.dataset.type}:focus?.tagName==='A'?{href:focus.getAttribute('href')}:null;
  const openDetails=[...document.querySelectorAll('#content details')].map(el=>el.open);
  const scrollPositions=[...document.querySelectorAll('#content .run-output,#content .preview-text')].map(el=>el.scrollTop);
  render();
  document.querySelectorAll('#content details').forEach((el,index)=>{if(openDetails[index])el.open=true;});
  document.querySelectorAll('#content .run-output,#content .preview-text').forEach((el,index)=>{if(scrollPositions[index])el.scrollTop=scrollPositions[index];});
  let next=null;
  if(descriptor?.id&&!descriptor.action)next=document.getElementById(descriptor.id);
  else if(descriptor?.action)next=[...document.querySelectorAll('[data-action]')].find(el=>el.dataset.action===descriptor.action&&el.dataset.id===descriptor.id&&el.dataset.type===descriptor.type);
  else if(descriptor?.href)next=[...document.querySelectorAll('a')].find(el=>el.getAttribute('href')===descriptor.href);
  next?.focus({preventScroll:true});
}
async function poll() {
  if(polling||!s.session||document.hidden)return;polling=true;
  const turn=s.loading;
  try {
    const changes=await api('/changes?after='+s.cursor);s.cursor=changes.cursor;
    if(turn!==s.loading)return;
    const inDialog=$('#dialog').open;
    if(s.view==='prompts'&&s.draft){
      if(savingPrompts.has(s.id))return;
      const beforeRuns=JSON.stringify(s.runs),beforeLease=Boolean(s.lease);
      const [latest,runs]=await Promise.all([api('/prompts/'+encodeURIComponent(s.id)),api('/prompts/'+encodeURIComponent(s.id)+'/submissions')]);
      if(turn!==s.loading||!s.draft)return;
      s.runs=runs.items;
      if(latest.revision!==s.saved.revision){if(s.dirty){s.conflict=true;s.lease=null;updateEditorStatus();}else if(!inDialog){s.saved=latest;s.draft=structuredClone(latest);s.lease=null;renderBuilder();}}
      else{s.saved={...s.saved,state:latest.state,last_run_revision:latest.last_run_revision,last_run_id:latest.last_run_id,changed_since_run:latest.changed_since_run};}
      if(s.lease&&Number(s.lease.expires_at)-Date.now()<30000)await acquireLease(false,true);
      if(!inDialog){if(beforeRuns!==JSON.stringify(s.runs))preserveView(()=>{renderPromptResult();updateEditorStatus();});else if(beforeLease!==Boolean(s.lease))updateEditorStatus();}
    }
    else if(s.view==='devices'&&!inDialog){const before=JSON.stringify([s.devices,s.device,s.commands,s.recovery]);const devices=(await api('/devices')).items;if(turn!==s.loading)return;s.devices=devices;await loadDevice();if(turn!==s.loading)return;if(before!==JSON.stringify([s.devices,s.device,s.commands,s.recovery]))preserveView(renderDevices);}
    else if(changes.items?.length&&!inDialog&&s.view==='notes'){s.notes=(await api('/notes')).items;if(!$('#note-search')?.matches(':focus'))renderNotes();}
    else if(changes.items?.length&&!inDialog&&s.view==='prompts'){s.prompts=(await api('/prompts')).items;if(!$('#prompt-search')?.matches(':focus'))renderPrompts();}
  }catch(error){if(error.status!==401)setOnline(false);}finally{polling=false;}
}

try{s.session=await api('/session');await enterWorkspace();}catch(error){showLogin(error.status===401?'':error.message);}
