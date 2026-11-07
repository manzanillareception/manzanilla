/* Isolated browser fixture: fake native events only; no hotel/provider calls. */
export async function runReceptionOperationsFixture(assetBase = new URL('../android/hotel/src/main/assets/reception/', import.meta.url)) {
  const assert = (condition, message) => { if (!condition) throw new Error(message); };
  const response = await fetch(new URL('index.html', assetBase));
  const html = new DOMParser().parseFromString(await response.text(), 'text/html');
  html.querySelectorAll('script,meta[http-equiv="Content-Security-Policy"]').forEach(node => node.remove());
  const base = html.createElement('base');
  base.href = String(assetBase);
  html.head.prepend(base);
  const frame = document.createElement('iframe');
  frame.width = '837'; frame.height = '471';
  frame.title = 'Isolated reception operations fixture';
  const loaded = new Promise(resolve => frame.addEventListener('load', resolve, { once:true }));
  frame.srcdoc = '<!doctype html>' + html.documentElement.outerHTML;
  document.body.appendChild(frame);
  await loaded;
  const win = frame.contentWindow, doc = frame.contentDocument, messages = [];
  win.ManzaniaNative = { postMessage:value => messages.push(JSON.parse(value)) };
  for (const file of ['language-turns.js','reception.js']) {
    const script = doc.createElement('script');
    script.src = new URL(file, assetBase).href;
    const ready = new Promise((resolve,reject) => { script.onload = resolve; script.onerror = reject; });
    doc.body.appendChild(script); await ready;
  }
  const click = selector => {
    const target = doc.querySelector(selector);
    assert(target && !target.disabled, `Enabled control: ${selector}`);
    target.click();
  };
  const input = (name,value) => {
    const target = doc.querySelector(`[name="${name}"]`);
    assert(target, `Input exists: ${name}`);
    target.value = value;
    target.dispatchEvent(new win.Event('input', { bubbles:true }));
  };
  const actionMessages = action => messages.filter(message => message.action === action);
  win.manzania.receive({ type:'reception_info', translation_available:false, reception_available:true, openai_configured:false, local_audio_state:'off' });
  click('[data-action="request:message"]');
  click('[data-action="assist-go"]');
  const scope = { session_id:actionMessages('start').at(-1).session_id, generation:1 };
  const receive = event => win.manzania.receive({ ...scope, ...event });
  receive({ type:'audio_state', state:'off' });
  receive({ type:'reception_event', phase:'ready' });
  assert(actionMessages('message').length === 1, 'Home shortcut opens the note service after session readiness');
  const fields = [
    { name:'original_request',label:'Exact message to send to staff',required:true,max_length:4000 },
    { name:'guest_name',label:'Guest name (optional)',required:false,max_length:100 },
    { name:'room_number',label:'Room number (optional)',required:false,max_length:20 }
  ];
  const form = () => receive({ type:'operation_form', action:'message', fields });
  const review = () => click('#operation-form [type="submit"]');
  form();
  assert(doc.querySelector('[name="guest_name"]').maxLength === 100 && !doc.querySelector('[name="guest_name"]').required, 'Optional identity fields have model limits');
  input('original_request', '  \n '); review();
  assert(actionMessages('message').length === 1, 'Whitespace-only notes cannot submit');
  const original = '  Exact note & <img src=x>\nKeep this line.  ';
  input('original_request', original); input('guest_name','Synthetic Guest'); input('room_number','204');
  review();
  assert(actionMessages('message').at(-1).arguments.original_request === original, 'Original note is sent exactly');
  assert(doc.querySelector('#operation-form [type="submit"]').disabled, 'Duplicate submissions are disabled');
  receive({ type:'operation_result', status:'unavailable', text:'Synthetic network failure' });
  assert(doc.querySelector('[name="original_request"]').value === original, 'Network failure retains the note');
  assert(doc.querySelector('[name="guest_name"]').value === 'Synthetic Guest', 'Network failure retains identity');
  review();
  // Older servers return only missing fields. Already entered values must survive.
  receive({ type:'operation_form', action:'message', fields:[fields[0]] });
  assert(doc.querySelector('[name="room_number"]').value === '204', 'Partial validation response retains other fields');
  review();
  receive({ type:'reception_event', phase:'confirming', draft_id:'operation:note-1', message:original });
  assert(doc.querySelector('.draft').textContent === original && !doc.querySelector('.draft img'), 'Review escapes and preserves the original');
  click('[data-action="edit-draft"]');
  assert(doc.querySelector('[name="original_request"]').value === original, 'Editing restores the reviewed note');
  const revised = original + '\nA correction.';
  input('original_request',revised); review();
  receive({ type:'reception_event', phase:'confirming', draft_id:'operation:note-2', message:revised });
  click('[data-action="confirm"]');
  assert(actionMessages('confirm').at(-1).draft_id === 'operation:note-2', 'Confirmation binds to the revised draft');
  receive({ type:'operation_result', status:'error', text:'Synthetic lost response; outcome unknown' });
  assert(doc.querySelector('.draft').textContent === revised, 'Lost confirmation response keeps the draft');
  assert(doc.querySelector('[data-action="edit-draft"]').disabled, 'Uncertain writes cannot be replaced by a new request');
  click('[data-action="retry-draft"]');
  assert(actionMessages('confirm').slice(-2).every(message => message.draft_id === 'operation:note-2' && message.approved), 'Retry uses the same confirmation identity');
  receive({ type:'operation_result', status:'pending', text:'The original request is still executing.' });
  assert(doc.querySelector('.draft').textContent === revised && !doc.querySelector('[data-action="continue"]'), 'An executing confirmation retains its draft until a definitive receipt');
  click('[data-action="retry-draft"]');
  assert(actionMessages('confirm').at(-1).draft_id === 'operation:note-2' && actionMessages('confirm').at(-1).approved, 'Pending requests retry the same approved confirmation');
  receive({ type:'operation_result', status:'unavailable', text:'Synthetic retry response lost' });
  click('[data-action="cancel-draft"]');
  assert(actionMessages('confirm').at(-1).draft_id === 'operation:note-2' && actionMessages('confirm').at(-1).approved === false, 'Cancellation after an uncertain write resolves the same request');
  receive({ type:'operation_result',status:'saved',text:'Message saved in the staff inbox. No staff acknowledgement is confirmed.',result:{item_id:'fixture-note'} });
  assert(doc.querySelector('.receipt').textContent === 'Receipt: fixture-note', 'Only the returned server receipt is displayed');
  click('[data-action="continue"]'); click('[data-action="message"]'); form();
  input('original_request','Cancel this note'); review();
  receive({ type:'reception_event',phase:'confirming',draft_id:'operation:note-3',message:'Cancel this note' });
  click('[data-action="cancel-draft"]');
  assert(actionMessages('confirm').at(-1).approved === false && actionMessages('confirm').at(-1).draft_id === 'operation:note-3', 'Cancellation keeps the exact draft identity');
  assert(doc.querySelector('[data-action="retry-draft"]').disabled && !doc.querySelector('[data-action="message"]'), 'Cancellation waits for its result before another operation');
  receive({ type:'operation_result',status:'unavailable',text:'Synthetic cancellation response lost' });
  click('[data-action="retry-draft"]');
  assert(actionMessages('confirm').at(-1).approved === false && actionMessages('confirm').at(-1).draft_id === 'operation:note-3', 'Retrying a lost cancellation never approves the note');
  receive({ type:'operation_result',status:'cancelled',text:'Cancelled. Nothing was sent.' });
  assert(doc.querySelector('.request-receipt').textContent.includes('Cancelled'), 'Cancellation displays its actual result');
  click('[data-action="continue"]'); click('[data-action="message"]'); form();
  input('original_request','Private unsent note');
  receive({ type:'reception_event',phase:'stopped' });
  receive({ type:'operation_result',status:'saved',text:'Stale receipt must not appear',result:{item_id:'stale'} });
  assert(!doc.body.textContent.includes('Private unsent note') && !doc.body.textContent.includes('Stale receipt'), 'Session end clears the note and fences late results');
  assert(actionMessages('start').every(message => message.mode === 'reception'), 'Notes never start a voice session');
  return { passed:true, checks:24, fixture:'synthetic browser/native bridge', frame };
}
