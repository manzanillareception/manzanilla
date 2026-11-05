import { readFileSync } from 'fs';
const src = readFileSync('manzanilla-web/src/index.js', 'utf8');
const m = src.match(/\/\*WATCHDOG-BEGIN\*\/([\s\S]*?)\/\*WATCHDOG-END\*\//);
if (!m) throw new Error('markers missing');
const { makeNoReplyWatchdog, NOREPLY_TIMEOUT_MS, TEXT_GRACE_MS } = new Function(m[1] + '; return { makeNoReplyWatchdog, NOREPLY_TIMEOUT_MS, TEXT_GRACE_MS };')();

function clock(){ let id=0; const q=new Map(); return {
  arm:(ms,cb)=>{const i=++id; q.set(i,{ms,cb}); return i;},
  disarm:(i)=>{q.delete(i);},
  fire:(i)=>{const t=q.get(i); if(t){q.delete(i); t.cb();}},
  fireAll:()=>{ for (const [i] of [...q.entries()]) { const t=q.get(i); q.delete(i); t.cb(); } },
  pending:()=>q.size, ids:()=>[...q.keys()] }; }

let pass=0, fail=0;
function t(name, fn){ try { fn(); pass++; console.log('PASS', name); } catch(e){ fail++; console.log('FAIL', name, '-', e.message); } }
function eq(a,b,what){ if (JSON.stringify(a)!==JSON.stringify(b)) throw new Error(what+': got '+JSON.stringify(a)+' want '+JSON.stringify(b)); }
function mk(){ const c=clock(), ev=[]; const w=makeNoReplyWatchdog({arm:c.arm, disarm:c.disarm, onDetect:i=>ev.push(['detect',i]), onResolve:i=>ev.push(['resolve',i])}); return {c,ev,w}; }

t('healthy turn: text then audio resolves audio, no detect', () => { const {c,ev,w}=mk();
  w.userTurnEnded(1); w.textDelta(); w.audio();
  eq(ev, [['resolve',{outcome:'audio',turn:1,hasText:true}]], 'events');
  c.fireAll(); eq(ev.length,1,'no more events'); });

t('no reply: detect fires, flags false (no observed text/audio)', () => { const {c,ev,w}=mk();
  w.userTurnEnded(1); c.fireAll();
  eq(ev, [['detect',{outcome:'no_reply',turn:1,hasAudio:false,hasText:false}]], 'events'); });

t('text-first ordering: turn_ended assistant, then audio inside grace resolves audio', () => { const {c,ev,w}=mk();
  w.userTurnEnded(1); w.textDelta(); w.assistantTurnEnded();
  eq(ev, [], 'nothing resolved before grace');
  w.audio();
  eq(ev, [['resolve',{outcome:'audio',turn:1,hasText:true}]], 'events');
  c.fireAll(); eq(ev.length,1,'grace timer cleared'); });

t('grace expiry with text: text_only_provisional, hasText true', () => { const {c,ev,w}=mk();
  w.userTurnEnded(1); w.textDelta(); w.assistantTurnEnded(); c.fireAll();
  eq(ev, [['resolve',{outcome:'text_only_provisional',turn:1,hasText:true}]], 'events'); });

t('grace expiry without text: provisional, hasText false (not labeled empty generation)', () => { const {c,ev,w}=mk();
  w.userTurnEnded(1); w.assistantTurnEnded(); c.fireAll();
  eq(ev, [['resolve',{outcome:'text_only_provisional',turn:1,hasText:false}]], 'events'); });

t('late audio after text_only_provisional tracked as late_audio', () => { const {c,ev,w}=mk();
  w.userTurnEnded(1); w.assistantTurnEnded(); c.fireAll(); w.audio();
  eq(ev, [['resolve',{outcome:'text_only_provisional',turn:1,hasText:false}],
          ['resolve',{outcome:'late_audio',turn:1}]], 'events');
  w.audio(); eq(ev.length,2,'no double late_audio'); });

t('late audio after no_reply tracked as late_audio', () => { const {c,ev,w}=mk();
  w.userTurnEnded(1); c.fireAll(); w.audio();
  eq(ev, [['detect',{outcome:'no_reply',turn:1,hasAudio:false,hasText:false}],
          ['resolve',{outcome:'late_audio',turn:1}]], 'events'); });

t('barge-in disarms both windows, no detect', () => { const {c,ev,w}=mk();
  w.userTurnEnded(1); w.interrupted(); c.fireAll();
  eq(ev, [['resolve',{outcome:'interrupted',turn:1}]], 'events'); });

t('barge-in during grace window disarms grace', () => { const {c,ev,w}=mk();
  w.userTurnEnded(1); w.assistantTurnEnded(); w.interrupted(); c.fireAll();
  eq(ev, [['resolve',{outcome:'interrupted',turn:1}]], 'events'); });

t('new user turn supersedes grace window and re-arms', () => { const {c,ev,w}=mk();
  w.userTurnEnded(1); w.assistantTurnEnded(); w.userTurnEnded(2); c.fireAll();
  eq(ev, [['resolve',{outcome:'superseded',turn:1,hasAudio:false,hasText:false}],
          ['detect',{outcome:'no_reply',turn:2,hasAudio:false,hasText:false}]], 'events'); });

t('stop while armed: silent, no detect, correlation reset', () => { const {c,ev,w}=mk();
  w.userTurnEnded(1); w.stop(); c.fireAll(); eq(ev, [], 'events');
  w.audio(); eq(ev, [], 'audio after stop ignored'); });

t('greeting audio before any user turn ignored', () => { const {c,ev,w}=mk();
  w.audio(); eq(ev, [], 'events'); eq(c.pending(), 0, 'no timer'); });

t('second audio chunk after healthy resolve no-op', () => { const {c,ev,w}=mk();
  w.userTurnEnded(1); w.audio(); w.audio(); eq(ev.length, 1, 'one resolve'); });

t('assistant turn_ended after audio already resolved is no-op', () => { const {c,ev,w}=mk();
  w.userTurnEnded(1); w.audio(); w.assistantTurnEnded(); eq(ev.length, 1, 'no double resolve'); });

t('cross-session independence: two watchdogs do not share state', () => { const a=mk(), b=mk();
  a.w.userTurnEnded(1); b.w.userTurnEnded(1); a.c.fireAll();
  eq(a.ev.length, 1, 'a detected'); eq(b.ev.length, 0, 'b unaffected'); });

t('constants exposed for review', () => { eq([NOREPLY_TIMEOUT_MS, TEXT_GRACE_MS], [5000, 2000], 'ms'); });

console.log(`\n${pass} passed, ${fail} failed`);
process.exit(fail ? 1 : 0);
