import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {webcrypto} from 'node:crypto';
import vm from 'node:vm';
import * as model from '../model.mjs';

// Exercise the real navigation and editing handlers without a browser or a live Hermes.
// DOM rendering is recorded; all network traffic is explicit in each fixture.
const source=readFileSync(new URL('../app.js',import.meta.url),'utf8');
function consoleFixture(hash, responses) {
  const nodes=new Map(),calls=[];
  const node=selector=>{
    if(!nodes.has(selector))nodes.set(selector,{innerHTML:'',textContent:'',hidden:false,dataset:{},classList:{toggle(){}},addEventListener(){},focus(){},scrollIntoView(){},showModal(){this.open=true;},close(){this.open=false;},querySelector(){return null;}});
    return nodes.get(selector);
  };
  const context=vm.createContext({
    ...model,h:model.escapeHTML,URLSearchParams,structuredClone,AbortController,crypto:webcrypto,setTimeout:(callback,delay)=>setTimeout(callback,delay).unref(),clearTimeout,setInterval,clearInterval,
    window:{addEventListener(){},scrollY:0},
    document:{querySelector:node,querySelectorAll:selector=>selector==='#prompt-title,#prompt-instruction,#blocks textarea'?[node('#prompt-title'),node('#prompt-instruction')]:[],addEventListener(){},hidden:false},
    location:{hash},
    history:{replaceState(_state,_title,next){context.location.hash=next;}},
    fetch:async(path,options)=>{
      const request={method:options.method,path:path.replace('/api/v1',''),body:options.body?JSON.parse(options.body):null};calls.push(request);
      let response=responses[request.method+' '+request.path];
      if(!response)throw new Error('Unexpected request: '+JSON.stringify(request));
      if(typeof response==='function')response=await response(request);
      const status=typeof response.status==='number'?response.status:200;
      return {ok:status<400,status,text:async()=>JSON.stringify(response.body??response)};
    }
  });
  vm.runInContext(source.slice(source.indexOf('\nconst $'),source.lastIndexOf('\ntry{s.session'))+'\nglobalThis.testConsole={s,shell,navigate,handleAction,poll};',context);
  context.testConsole.s.session={csrf_token:'fixture'};
  context.testConsole.shell();
  return {...context.testConsole,nodes,calls,location:context.location};
}
const prompt={id:'p',revision:2,state:'draft',title:'Saved idea',instruction:'Current edited version',blocks:[]};
function promptResponses() {
  return {
    'GET /prompts':{items:[prompt]},'GET /notes':{items:[]},'GET /resume':{},
    'GET /prompts/p':prompt,
    'POST /prompts/p/lease':{lease_token:'edit-token',expires_at:Date.now()+90000}
  };
}
test('a legacy unknown submission opens its source editor without exposing execution controls or resubmitting',async()=>{
  const receipt={id:'uncertain',prompt_id:'p',prompt_revision:2,status:'submission_unknown',created_at:1,snapshot:{preview:'Exact original submitted text'}};
  const original=structuredClone(receipt);
  const app=consoleFixture('#runs/uncertain',{'GET /runs/uncertain':receipt,...promptResponses()});
  await app.navigate();
  assert.equal(app.location.hash,'#prompts/p');
  assert.equal(app.s.view,'prompts');
  assert.equal(app.s.id,'p');
  assert.doesNotMatch(app.nodes.get('#app').innerHTML,/href="#runs"/);
  const html=app.nodes.get('#content').innerHTML;
  assert.match(html,/Prompt preview/);
  assert.match(html,/Current edited version/);
  assert.doesNotMatch(html,/prompt-result|Hermes reply|Check submission|Stop Hermes|Send to Hermes|data-action=".*run/);
  for(const action of ['run-prompt','retry-run','run-again','reconcile-run','approve-run','deny-run','stop-run'])await app.handleAction({dataset:{action,id:'uncertain'}});
  assert.equal(app.calls.filter(call=>call.method!=='GET').length,0,'old execution actions cannot dispatch requests');
  assert.equal(app.calls.some(call=>call.path==='/runs'||call.path.endsWith('/submissions')),false);
  assert.deepEqual(receipt,original,'reading a legacy link does not change the receipt');
});
test('a missing old run link falls back to saved prompts with no execution or global history request',async()=>{
  const app=consoleFixture('#runs/missing',{'GET /runs/missing':{status:404,body:{error:'Not found'}},...promptResponses()});
  await app.navigate();
  assert.equal(app.location.hash,'#prompts');
  assert.equal(app.s.id,null);
  assert.match(app.nodes.get('#content').innerHTML,/Saved idea/);
  assert.equal(app.calls.some(call=>call.method!=='GET'||call.path==='/runs'||call.path.endsWith('/submissions')),false);
});
test('saved prompts and previews ignore legacy execution metadata and never fetch replies',async()=>{
  const saved={...prompt,state:'ran',last_run_id:'old-reply',last_run_revision:1,changed_since_run:true};
  const responses={...promptResponses(),'GET /prompts':{items:[saved]},'GET /prompts/p':saved,'GET /changes?after=0':{cursor:1,items:[{type:'run'}]}};
  const app=consoleFixture('#prompts',responses);
  await app.navigate();
  assert.doesNotMatch(app.nodes.get('#content').innerHTML,/Ran|Changed since last run|Hermes reply|Send to Hermes/);
  app.location.hash='#prompts/p?submission=old-reply';
  await app.navigate();
  await app.poll();
  assert.doesNotMatch(app.nodes.get('#content').innerHTML,/Ran|Changed since last run|Hermes reply|prompt-result|run-prompt/);
  assert.equal(app.calls.some(call=>call.path.includes('/runs')||call.path.endsWith('/submissions')||call.method!=='GET'),false);
});
test('shared chat records stay out of saved prompt lists and note destination choices',async()=>{
  const chat={...prompt,id:'shared-chat',title:'Private chat turn'};
  const app=consoleFixture('#prompts',{...promptResponses(),'GET /prompts':{items:[prompt,chat]},'GET /resume':{prompt_id:chat.id}});
  await app.navigate();
  assert.equal(app.s.prompts.length,1);
  assert.doesNotMatch(app.nodes.get('#content').innerHTML,/Private chat turn|shared-chat|Resume prompt/);
  app.s.notes=[{id:'note',kind:'text',title:'A note',text:'Text'}];
  await app.handleAction({dataset:{action:'note-to-prompt',id:'note'}});
  assert.match(app.nodes.get('#dialog').innerHTML,/Saved idea/);
  assert.doesNotMatch(app.nodes.get('#dialog').innerHTML,/Private chat turn|shared-chat/);
});
test('a direct or legacy link to chat storage cannot open it in the editor',async()=>{
  const responses={...promptResponses(),'GET /runs/chat-reply':{id:'chat-reply',prompt_id:'shared-chat'}};
  for(const hash of ['#prompts/shared-chat','#runs/chat-reply']){
    const app=consoleFixture(hash,responses);
    await app.navigate();
    assert.equal(app.s.id,null);
    assert.equal(app.s.draft,null);
    assert.equal(app.calls.some(call=>call.path.startsWith('/prompts/shared-')||call.method!=='GET'),false);
  }
});
test('opening a prompt is read-only until Edit prompt, and takeover is separately explicit',async()=>{
  const responses=promptResponses();
  responses['POST /prompts/p/lease']={status:409,body:{error:'Editing control is elsewhere'}};
  const app=consoleFixture('#prompts/p',responses);
  await app.navigate();
  assert.equal(app.nodes.get('#prompt-instruction').readOnly,true);
  assert.equal(app.calls.some(call=>call.method==='POST'),false);
  await app.handleAction({dataset:{action:'edit-prompt'}});
  assert.equal(app.calls.find(call=>call.method==='POST').body.takeover,false);
  assert.equal(app.nodes.get('#prompt-instruction').readOnly,true);
  assert.match(app.nodes.get('#edit-notice').innerHTML,/Continue editing here/);
  responses['POST /prompts/p/lease']={lease_token:'edit-token',expires_at:Date.now()+90000};
  await app.handleAction({dataset:{action:'takeover'}});
  assert.equal(app.calls.filter(call=>call.method==='POST')[1].body.takeover,true);
  assert.equal(app.nodes.get('#prompt-instruction').readOnly,false);
});
test('a delayed edit refresh cannot overwrite a different prompt after navigation',async()=>{
  const responses=promptResponses(),app=consoleFixture('#prompts/p',responses);
  await app.navigate();
  let finish,started;
  const waiting=new Promise(resolve=>started=resolve);
  responses['GET /prompts/p']=()=>{started();return new Promise(resolve=>finish=resolve);};
  const edit=app.handleAction({dataset:{action:'edit-prompt'}});
  await waiting;
  const other={id:'other',revision:10,instruction:'Other prompt',blocks:[]};
  app.s.id=other.id;app.s.loading++;app.s.saved=other;app.s.draft=other;app.s.lease={lease_token:'other-lease'};
  finish({...prompt,revision:4,instruction:'Delayed first prompt'});
  await edit;
  assert.equal(app.s.saved.id,'other');
  assert.equal(app.s.draft.instruction,'Other prompt');
  assert.equal(app.s.lease.lease_token,'other-lease');
});
test('a delayed lease cannot apply after leaving and returning to the same prompt',async()=>{
  const responses=promptResponses(),app=consoleFixture('#prompts/p',responses);
  await app.navigate();
  let finish,started;
  const waiting=new Promise(resolve=>started=resolve);
  responses['POST /prompts/p/lease']=()=>{started();return new Promise(resolve=>finish=resolve);};
  const edit=app.handleAction({dataset:{action:'edit-prompt'}});
  await waiting;
  app.s.loading++;app.s.lease=null;
  const before=app.calls.filter(call=>call.path==='/prompts/p').length;
  finish({lease_token:'stale-lease',expires_at:Date.now()+90000});
  await edit;
  assert.equal(app.s.lease,null);
  assert.equal(app.calls.filter(call=>call.path==='/prompts/p').length,before,'a stale edit does not start its follow-up refresh');
});

test('polling a competing prompt revision preserves the browser draft and revokes local editing control',async()=>{
  const responses={...promptResponses(),'GET /changes?after=0':{cursor:1,items:[{type:'prompt'}]}};
  const app=consoleFixture('#prompts/p',responses);
  await app.navigate();
  await app.handleAction({dataset:{action:'edit-prompt'}});
  app.s.draft.instruction='Unsaved browser edit';app.s.dirty=true;
  responses['GET /prompts/p']={...prompt,revision:3,instruction:'Saved from the R1'};
  await app.poll();
  assert.equal(app.s.draft.instruction,'Unsaved browser edit');
  assert.equal(app.s.saved.revision,2);
  assert.equal(app.s.conflict,true);
  assert.equal(app.s.lease,null);
  assert.equal(app.nodes.get('#prompt-instruction').readOnly,true);
  assert.match(app.nodes.get('#edit-notice').innerHTML,/Review versions/);
});
