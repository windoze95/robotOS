import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {webcrypto} from 'node:crypto';
import vm from 'node:vm';
import * as model from '../model.mjs';

// Exercise the real navigation and submit handlers without a browser or a live Hermes.
// DOM rendering is recorded; all network traffic is explicit in each fixture.
const source=readFileSync(new URL('../app.js',import.meta.url),'utf8');
function consoleFixture(hash, responses) {
  const nodes=new Map(),calls=[];
  const node=selector=>{
    if(!nodes.has(selector))nodes.set(selector,{innerHTML:'',textContent:'',hidden:false,dataset:{},classList:{toggle(){}},addEventListener(){},focus(){},scrollIntoView(){}});
    return nodes.get(selector);
  };
  const context=vm.createContext({
    ...model,h:model.escapeHTML,URLSearchParams,structuredClone,AbortController,crypto:webcrypto,setTimeout:(callback,delay)=>setTimeout(callback,delay).unref(),clearTimeout,setInterval,clearInterval,
    window:{addEventListener(){}},
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
  vm.runInContext(source.slice(source.indexOf('\nconst $'),source.lastIndexOf('\ntry{s.session'))+'\nglobalThis.testConsole={s,shell,navigate,handleAction};',context);
  context.testConsole.s.session={csrf_token:'fixture'};
  context.testConsole.shell();
  return {...context.testConsole,nodes,calls,location:context.location};
}
const prompt={id:'p',revision:2,state:'draft',title:'Saved idea',instruction:'Current edited version',blocks:[]};
function promptResponses(submissions) {
  return {
    'GET /prompts':{items:[prompt]},'GET /notes':{items:[]},'GET /resume':{},
    'GET /prompts/p':prompt,'GET /prompts/p/submissions':{items:submissions},
    'POST /prompts/p/lease':{lease_token:'edit-token',expires_at:Date.now()+90000}
  };
}
test('a legacy unknown submission opens its prompt, preserving the original outcome without submitting again',async()=>{
  const receipt={id:'uncertain',prompt_id:'p',prompt_revision:2,status:'submission_unknown',created_at:1,snapshot:{preview:'Exact original submitted text'}};
  const app=consoleFixture('#runs/uncertain',{'GET /runs/uncertain':receipt,...promptResponses([receipt])});
  await app.navigate();
  assert.equal(app.location.hash,'#prompts/p?submission=uncertain');
  assert.equal(app.s.view,'prompts');
  assert.equal(app.s.selectedRunId,'uncertain');
  assert.doesNotMatch(app.nodes.get('#app').innerHTML,/href="#runs"/);
  const result=app.nodes.get('#prompt-result').innerHTML;
  assert.match(result,/Hermes has not confirmed/);
  assert.match(result,/Exact original submitted text/);
  assert.match(result,/Check submission/);
  assert.match(result,/Stop Hermes request/);
  assert.doesNotMatch(result,/Send to Hermes again/);
  await app.handleAction({dataset:{action:'run-prompt'}});
  assert.equal(app.calls.filter(call=>call.method==='POST').length,0,'reading the reply neither takes editing control nor submits another execution');
  assert.equal(app.calls.some(call=>call.path==='/runs'),false,'no global execution-history fetch');
});
test('a missing old run link falls back to saved prompts with no execution or global history request',async()=>{
  const app=consoleFixture('#runs/missing',{'GET /runs/missing':{status:404,body:{error:'Not found'}},...promptResponses([])});
  await app.navigate();
  assert.equal(app.location.hash,'#prompts');
  assert.equal(app.s.id,null);
  assert.match(app.nodes.get('#content').innerHTML,/Saved idea/);
  assert.equal(app.calls.some(call=>call.method!=='GET'||call.path==='/runs'||call.path.endsWith('/submissions')),false);
});
test('Hermes approvals, saved replies and immutable prompt text remain inside their prompt',async()=>{
  const current={id:'current',prompt_id:'p',prompt_revision:2,status:'waiting_for_approval',created_at:3,approval:{request_id:'permission',message:'Read the selected file?'},snapshot:{preview:'Sent revision two'},hermes:{session_id:'hermes-session',run_id:'hermes-request'}};
  const earlier={id:'earlier',prompt_id:'p',prompt_revision:1,status:'completed',created_at:1,output:'Saved Hermes reply',snapshot:{preview:'Sent revision one'}};
  const app=consoleFixture('#prompts/p',promptResponses([current,earlier]));
  await app.navigate();
  const html=app.nodes.get('#prompt-result').innerHTML;
  assert.match(html,/Hermes needs permission/);
  assert.match(html,/data-action="approve-run" data-id="current" data-approval="permission"/);
  assert.match(html,/data-action="deny-run"/);
  assert.match(html,/<details class="run-history"><summary>Earlier submissions of this prompt \(1\)/);
  assert.match(html,/Saved Hermes reply/);
  assert.match(html,/Sent revision one/);
  assert.match(html,/hermes-session/);
  assert.doesNotMatch(html,/#runs|https?:\/\//);
});
test('opening a prompt is read-only until Edit prompt, and takeover is separately explicit',async()=>{
  const responses=promptResponses([]);
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
  const responses=promptResponses([]),app=consoleFixture('#prompts/p',responses);
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
  const responses=promptResponses([]),app=consoleFixture('#prompts/p',responses);
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
