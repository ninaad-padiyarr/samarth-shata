const app = document.querySelector('#app');
const notice = document.querySelector('#notice');
const identity = document.querySelector('#identity');
const logout = document.querySelector('#logout');
let session = JSON.parse(sessionStorage.getItem('campus-session') || 'null');
let active = null;
let timer, syncing = false;
const titles = {STUDENT:'Student Portal', FACULTY:'Faculty Portal', ADMIN:'COE Dashboard', OBSERVER:'Examination Overview'};
const el = (tag, props = {}, ...children) => {
  const node = Object.assign(document.createElement(tag), props);
  node.append(...children.flat().filter(x => x != null));
  return node;
};
const button = (text, action, css = '') => el('button', {textContent:text, className:css, type:'button',
  onclick: async event => {
    const target = event.currentTarget;
    target.disabled = true;
    try { await action(); } catch (error) { say(error.message); }
    finally { target.disabled = false; }
  }});
const say = text => { notice.textContent = text; };

async function api(path, fields, token = session?.token) {
  let response;
  try {
    response = await fetch('/api/' + path, {
      method: fields === undefined ? 'GET' : 'POST',
      headers: token ? {Authorization:'Bearer ' + token} : {},
      body: fields === undefined ? undefined : new URLSearchParams(fields),
      signal: AbortSignal.timeout(8000)
    });
  } catch {
    throw new Error('Server unreachable. Unconfirmed answers stay on this device.');
  }
  const data = await response.json();
  if (!response.ok) {
    if (response.status === 401 && path !== 'login') signOut('Session expired. Sign in again to resume.');
    throw Object.assign(new Error(data.error), {status:response.status});
  }
  return data;
}

const database = new Promise((resolve, reject) => {
  const request = indexedDB.open('campusexam-recovery',1);
  request.onupgradeneeded = () => request.result.createObjectStore('answers', {keyPath:'key'});
  request.onsuccess = () => resolve(request.result);
  request.onerror = () => reject(new Error('This browser could not open local recovery storage.'));
});
database.catch(() => {});
async function local(mode, operation) {
  const db = await database;
  return new Promise((resolve,reject) => {
    const tx = db.transaction('answers',mode);
    const request = operation(tx.objectStore('answers'));
    tx.oncomplete = () => resolve(request.result);
    tx.onabort = tx.onerror = () => reject(new Error('Local recovery storage failed.'));
  });
}
const pending = async attempt => (await local('readonly',s=>s.getAll())).filter(
  row => row.user === session?.username && row.attempt === attempt);
function discard(row) {
  return local('readwrite',store => {
    const request = store.get(row.key);
    request.onsuccess = () => {
      const stored = request.result;
      if (stored?.version === row.version && stored.choice === row.choice && stored.review === row.review)
        store.delete(row.key);
    };
    return request;
  });
}
function signOut(message = '') {
  session = active = null;
  clearInterval(timer);
  sessionStorage.removeItem('campus-session');
  say(message);
  showLogin();
}
logout.onclick = async () => {
  try { await api('logout', {}); } catch {}
  signOut();
};
function form(fields, label, submit) {
  const controls = fields.map(([name, caption, type = 'text', value = '']) => {
    const control = Array.isArray(type)
      ? el('select',{name},type.map(([key,title])=>el('option',{value:key,textContent:title})))
      : type === 'textarea'
        ? el('textarea',{name,value})
        : el('input',{name,type,value,required:type !== 'password' || name === 'password'});
    if (type === 'number') control.step = 'any';
    return el('label',{},caption,control);
  });
  const send = el('button',{textContent:label});
  return el('form',{onsubmit:async event => {
    event.preventDefault();
    send.disabled = true;
    try { await submit(Object.fromEntries(new FormData(event.currentTarget))); }
    catch (error) { say(error.message); }
    finally { send.disabled = false; }
  }},controls,send);
}
function showLogin() {
  identity.textContent = 'Local examination system';
  logout.hidden = true;
  const role = {'/admin':'ADMIN','/faculty':'FACULTY','/observer':'OBSERVER'}[location.pathname] || 'STUDENT';
  app.replaceChildren(el('div',{className:'login'},el('h1',{},titles[role]),
    el('p',{className:'muted'},'Sign in with your campus account.'),
    form([['username','USN or username'],['password','Password','password']],'Sign in',async fields=>{
      const result = await api('login',fields,null);
      session = {...result,username:fields.username.trim().toLowerCase()};
      sessionStorage.setItem('campus-session',JSON.stringify(session));
      say('');
      await home();
    }),
    el('nav',{className:'toolbar'},Object.entries({'/':'Student','/faculty':'Faculty','/admin':'COE','/observer':'HOD / Principal'})
      .map(([href,text])=>el('a',{href,textContent:text})))));
}
async function home() {
  active = null;
  clearInterval(timer);
  identity.textContent = titles[session.role] + ' | ' + session.username;
  logout.hidden = false;
  if (session.role === 'STUDENT') return studentHome();
  return staffHome();
}
async function studentHome() {
  const exams = await api('exams');
  app.replaceChildren(el('h1',{},'Your exams'),el('div',{className:'grid'},
    exams.length ? exams.map(exam => el('section',{},el('h2',{},exam.title),
      el('p',{},exam.instructions || 'Select an answer for each question, then submit.'),
      el('p',{className:'muted'},exam.mode + ' | ' + exam.questions + ' questions | ' + exam.duration / 60 + ' min'),
      el('p',{className:'muted'},'Closes ' + new Date(exam.closes).toLocaleString()),
      el('div',{className:'toolbar'},button('Start / resume',async ()=>{
        const {id} = await api('start',{exam:exam.id});
        await openAttempt(id);
      }),button('Attempt history',()=>history(exam),'secondary'))))
    : el('section',{},'There are no exams assigned to this account.')));
}
async function history(exam) {
  const list = await api('attempts?exam=' + exam.id);
  app.replaceChildren(el('h1',{},exam.title),button('Back to exams',home,'secondary'),
    el('section',{},list.length ? list.map((row,i)=>el('p',{},button(
      'Attempt ' + (i+1) + (row.submitted ? ' | Submitted' : ' | In progress'),()=>openAttempt(row.id)))) : 'No attempts yet.'));
}
async function openAttempt(id) {
  const data = await api('attempt?attempt=' + id);
  const current = data.navigation === "FORWARD" ? data.currentPosition-1 : active?.id === id ? active.current : 0;
  active = {id, data, current, offset:Date.now()-Date.parse(data.serverTime)};
  const queued = await pending(id);
  for (const row of queued) {
    const q = data.questions.find(x=>x.position === row.position);
    if (q && row.version > (q.version ?? 0) && !data.submitted) Object.assign(q,row);
  }
  renderAttempt();
  clearInterval(timer);
  if (!data.submitted) {
    timer = setInterval(tick,1000);
    void sync(true);
  }
}
function tick() {
  if (!active || active.data.submitted) return;
  if (active.data.paused) { void sync(true); return; }
  const seconds = Math.max(0,Math.ceil((Date.parse(active.data.deadline)-Date.now()+active.offset)/1000));
  const clock = document.querySelector('.timer');
  if (clock) clock.textContent = Math.floor(seconds/60) + ':' + String(seconds%60).padStart(2,'0');
  if (seconds === 0 || seconds % 5 === 0) void sync(seconds % 15 === 0);
}
async function sync(heartbeat = false) {
  if (syncing || !active || active.data.submitted) return;
  const current = active;
  syncing = true;
  try {
    if (heartbeat) {
      const fresh = await api("attempt?attempt=" + current.id);
      if (fresh.submitted || fresh.paused !== current.data.paused || fresh.deadline !== current.data.deadline) {
        await openAttempt(current.id);
        return;
      }
    }
    if (current.data.paused) { say("Exam paused by the COE. Waiting for resumption."); return; }
    const queued = await pending(current.id);
    for (const row of queued) {
      if (active !== current) return;
      try {
        const saved = await api('answer',{attempt:row.attempt,position:row.position,
          choice:row.choice ?? 0,review:row.review,version:row.version});
        if (saved.status === 'SUBMITTED') {
          await openAttempt(current.id);
          say('The attempt closed before pending changes could be confirmed.');
          return;
        }
        await discard(row);
      } catch (error) {
        if (error.status === 409) {
          await discard(row);
          await openAttempt(current.id);
          say('A newer server answer was found. Review your answers before submitting.');
          return;
        }
        throw error;
      }
    }
    if (active !== current) return;
    const remaining = (await pending(current.id)).length;
    if (heartbeat) {
      const state = await api('heartbeat',{attempt:current.id,pending:remaining});
      if (state.status === 'SUBMITTED') { await openAttempt(current.id); return; }
    }
    say(remaining ? remaining + ' change(s) pending sync.' : 'All answer changes confirmed by the server.');
  } catch (error) { say(error.message); }
  finally { syncing = false; }
}
let localWrites = Promise.resolve();
function changeAnswer(question, change) {
  const current = active;
  localWrites = localWrites.catch(()=>{}).then(async()=>{
    if (current !== active || current.data.paused) return;
    const row = {attempt:current.id,user:session.username,position:question.position,
      choice:question.choice,review:question.review ?? false,...change,version:(question.version ?? 0)+1};
    row.key = row.user + ':' + row.attempt + ':' + row.position;
    await local('readwrite',s=>s.put(row));
    Object.assign(question,row);
    renderAttempt();
    say('Answer pending server confirmation.');
    void sync();
  });
  localWrites.catch(error=>{renderAttempt();say(error.message);});
}
function renderAttempt() {
  const {data,current} = active;
  if (data.submitted) {
    clearInterval(timer);
    const review = data.questions.filter(q=>q.correct != null);
    app.replaceChildren(el('section',{},el('h1',{},'Your answers have been submitted'),
      el('p',{},data.title),el('p',{},data.score == null ? 'Results will appear after release.' :
        'Score: ' + data.score + ' | ' + (data.passed ? 'Passed' : 'Below passing marks')),
      button('Back to exams',home)),
      ...review.map(q=>el('section',{},el('strong',{},q.position + '. ' + q.prompt),
        el('p',{},'Correct answer: ' + q.options[q.correct-1]),el('p',{},q.explanation || ''))));
    return;
  }
  const q = data.questions[current];
  const go = position => {active.current=position;renderAttempt();};
  const option = (text,index)=>el('label',{className:'option'},
    el('input',{type:'radio',name:'choice',checked:q.choice===index+1,disabled:data.paused,
      onchange:()=>changeAnswer(q,{choice:index+1})}),text);
  app.replaceChildren(el('h1',{},data.title),el('div',{className:'exam'},
    el('section',{},el('h2',{},'Question ' + q.position + ' of ' + data.questions.length),
      el('p',{className:'prompt'},q.prompt),q.image ? el('img',{src:q.image,alt:'Question illustration',className:'question-image'}) : null,q.options.map(option),
      el('div',{className:'toolbar'},button('Clear answer',()=>changeAnswer(q,{choice:null}),'secondary'),
        button(q.review ? 'Unflag question' : 'Flag for review',()=>changeAnswer(q,{review:!q.review}),'secondary')),
      el('div',{className:'toolbar'},current>0 && data.navigation==='FREE' ? button('Back',()=>go(current-1)) : null,
        current+1<data.questions.length && !data.paused ? button('Next',async()=>{
          if (data.navigation==='FREE') { go(current+1); return; }
          await localWrites;
          await sync();
          if ((await pending(data.id)).length) throw new Error('Wait for answer confirmation before advancing.');
          await api('next-question',{attempt:data.id,position:q.position});
          await openAttempt(data.id);
        }) : null)),
    el('section',{},el('h2',{},'Question navigator'),el('p',{className:'timer'},''),
      el('div',{className:'navigator'},data.questions.map((item,index)=>{
        const control = button(String(item.position),()=>go(index),
          [item.choice?'answered':'secondary',item.review?'review':'',index===current?'current':''].join(' '));
        control.disabled = data.navigation==='FORWARD' && index!==current;
        return control;
      })),
      el('p',{className:'muted'},'Green = answered. Gold outline = flagged.'),
      form([['detail','Technical issue','textarea']],'Report issue',async fields=>{
        const incident = await api('incident',{attempt:data.id,...fields});
        say('Issue reported | Incident ' + incident.id);
      }),
      data.paused ? el('p',{},'The COE has paused this exam.') : button('Submit exam',async()=>{
        if (!confirm('Submit this attempt? You will not be able to edit your answers.')) return;
        await localWrites;
        await sync();
        if ((await pending(data.id)).length) throw new Error('Wait for pending answers to sync before submitting.');
        await api('submit',{attempt:data.id});
        say('');
        await openAttempt(data.id);
      }))));
  const seconds = Math.max(0,Math.ceil((Date.parse(data.deadline)-Date.now()+active.offset)/1000));
  document.querySelector('.timer').textContent = data.paused ? 'Paused' : Math.floor(seconds/60) + ':' + String(seconds%60).padStart(2,'0');
}

function table(rows, columns) {
  if (!rows.length) return el('p',{className:'muted'},'No records.');
  return el('div',{className:'scroll'},el('table',{},
    el('thead',{},el('tr',{},columns.map(([key,label])=>el('th',{scope:'col'},label || key)))),
    el('tbody',{},rows.map(row=>el('tr',{},columns.map(([key])=>el('td',{},String(row[key] ?? ''))))))));
}
const section = (title, ...children) => el('details',{},el('summary',{},title),...children);
const choices = (rows, label) => rows.map(row=>[row.id,label(row)]);
function action(path, after = null) {
  return async fields => {
    const result = await api(path,fields);
    say('Saved' + (result.id == null ? '.' : ' | Record ' + result.id));
    if (after) await after(result);
  };
}
const questionFields = [['prompt','Question','textarea'],['a','Option A'],['b','Option B'],['c','Option C'],['d','Option D'],
  ['correct','Correct option',[[1,'A'],[2,'B'],[3,'C'],[4,'D']]],['explanation','Explanation','textarea'],
  ['topic','Topic'],['difficulty','Difficulty',[['EASY','Easy'],['MEDIUM','Medium'],['HARD','Hard']]],
  ['marks','Marks','number','1'],['penalty','Penalty','number','0']];

async function staffHome() {
  const role = session.role;
  app.replaceChildren(el('h1',{},titles[role]),el('p',{},'Loading records...'));
  const panels = [];
  if (role === 'ADMIN') panels.push(...await adminPanels());
  if (role === 'FACULTY' || role === 'ADMIN') panels.push(...await bankPanels(role));
  panels.push(await examsPanel(role));
  app.replaceChildren(el('h1',{},titles[role]),...panels);
}
async function adminPanels() {
  const [groups,subjects,accounts] = await Promise.all(['groups','subjects','accounts'].map(p=>api(p)));
  return [
    section('Academic groups and subjects',
      table(groups,[['id'],['department'],['academic_session','Session'],['study_year','Year'],['semester'],['section']]),
      form([['department','Department / program'],['session','Academic session'],['year','Year of study','number'],
        ['semester','Semester','number'],['section','Section']],'Add group',action('groups',staffHome)),
      table(subjects,[['id'],['name','Subject']]),form([['name','Subject name']],'Add subject',action('subjects',staffHome))),
    section('Accounts and faculty access',
      table(accounts,[['id'],['username'],['role'],['group_id','Group']]),
      form([['username','USN or username'],['password','Password (12+ characters)','password'],
        ['role','Role',['STUDENT','FACULTY','OBSERVER','ADMIN'].map(x=>[x,x])],
        ['group','Academic group (students only)',choices(groups,x=>x.id+' | '+x.department+' '+x.section)]],
        'Create account',action('accounts',staffHome)),
      rosterPanel(),
      form([['faculty','Faculty account',choices(accounts.filter(a=>a.role==='FACULTY'),x=>x.username)],
        ['group','Academic group',choices(groups,x=>x.id+' | '+x.department+' '+x.section)],
        ['subject','Subject',choices(subjects,x=>x.name)]],'Grant faculty scope',action('faculty-scopes'))),
    section('Audit log',button('Load audit records',async()=>{
      const target = document.querySelector('#audit-records');
      target.replaceChildren(table(await api('audit'),[['happened','Time'],['actor'],['action'],['entity'],['detail']]));
    }),el('div',{id:'audit-records'}))];
}
async function bankPanels(role) {
  const banks = await api('banks');
  const scope = role==='FACULTY' ? await api('my-scopes') : await api('groups');
  const subjects = role==='FACULTY'
    ? [...new Map(scope.map(x=>[x.subject_id,[x.subject_id,x.subject]])).values()]
    : choices(await api('subjects'),x=>x.name);
  const bankSelect = choices(banks,x=>x.title+' | '+x.state);
  const list = el('div');
  const panel = section('Question banks',
    table(scope,[['id','Group ID'],['department'],['academic_session','Session'],['section'],...(role==='FACULTY'?[['subject']]:[])]),
    table(banks,[['id'],['title'],['state'],['groups']]),
    form([['title','Bank title'],['subject','Subject',subjects],['groups','Group IDs, comma-separated']],
      'Create bank',action('banks',staffHome)),
    questionEditor(bankSelect),
    importPanel(bankSelect),
    form([['bank','Bank',bankSelect],['title','New draft title']],'Copy bank to a new draft',action('revise-bank',staffHome)),
    form([['bank','Draft bank',choices(banks.filter(b=>b.state==='DRAFT'),b=>b.title)],
      ['question','Question ID','number']],'Remove draft question',async fields=>{
      if (confirm('Remove this question from the draft bank?')) await action('delete-question')(fields);
    }),
    form([['bank','Bank',bankSelect],['state','New state',(role==='ADMIN'
      ? ['SUBMITTED','APPROVED','REJECTED','ARCHIVED','DRAFT'] : ['SUBMITTED','DRAFT']).map(x=>[x,x])]],
      'Change bank state',action('bank-state',staffHome)),
    form([['bank','Bank',bankSelect]],'View questions',async({bank})=>{
      list.replaceChildren(table(await api('questions?bank='+bank),[['id'],['prompt'],['topic'],['difficulty'],['correct'],['marks'],['penalty']]));
    }),list);
  return [panel];
}
async function examsPanel(role) {
  const exams = await api('exams');
  const monitor = el('div');
  const panel = section('Published exams and live monitoring',
    table(exams,[['id'],['title'],['mode'],['questions'],['total_marks'],['closes'],['released']]));
  panel.open = true;
  panel.append(form([['exam','Exam',choices(exams,x=>x.title)]],'View live status',async({exam})=>{
    const draw = async()=>{
      const records = await api('monitor?exam='+exam);
      monitor.replaceChildren(el('div',{className:'toolbar'},button('Refresh',draw),
        button('Export CSV',()=>exportCsv(records,'exam-'+exam+'.csv'),'secondary'),
        button('Export Excel',async()=>{
          const report = await api('xlsx-report?exam='+exam);
          const bytes = Uint8Array.from(atob(report.file),ch=>ch.charCodeAt(0));
          download(new Blob([bytes],{type:'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'}),'exam-'+exam+'.xlsx');
        },'secondary'),
        button('Print / save PDF',()=>printReport(records),'secondary')),
        table(records,[['username','Student'],['group_id','Group'],['status'],['answered'],['total'],
          ['remaining_seconds','Seconds'],['reported_pending','Pending'],['score'],['passed']]));
    };
    clearInterval(timer);
    await draw();
    timer = setInterval(()=>{if(monitor.isConnected)draw().catch(error=>say(error.message));else clearInterval(timer);},10000);
  }),monitor);
  const reports = el('div');
  panel.append(form([['exam','Exam',choices(exams,x=>x.title)]],'View analytics and incidents',async({exam})=>{
    const [stats,incidents] = await Promise.all([api('analytics?exam='+exam),
      role==='FACULTY' ? Promise.resolve([]) : api('incidents?exam='+exam)]);
    reports.replaceChildren(el('h2',{},'Group results'),
      table(stats.groups,[['department'],['semester'],['section'],['assigned'],['completed'],['average'],['passed']]),
      el('h2',{},'Question accuracy'),table(stats.questions,[['position'],['topic'],['difficulty'],['submitted'],['answered'],['correct']]),
      role==='FACULTY' ? null : el('h2',{},'Incidents'),role==='FACULTY' ? null : table(incidents,[['id'],['username'],['reported'],['detail'],['resolution']]));
  }),reports);
  if (role==='ADMIN') {
    panel.append(section('Exam controls',
      form([['exam','Exam',choices(exams.filter(x=>!x.released),x=>x.title)],
        ['command','Action',['START','PAUSE','RESUME','EXTEND','CLOSE'].map(x=>[x,x])],
        ['seconds','Extension in seconds (extend only)','number','300'],['reason','Audit reason']],
        'Apply control',async fields=>{
          if (fields.command!=='CLOSE' || confirm('Close this exam and score all active attempts?'))
            await action('exam-control',staffHome)(fields);
        }),
      form([['incident','Incident ID','number'],['detail','Resolution','textarea']],
        'Resolve incident',action('resolve-incident'))));
    const banks = (await api('banks')).filter(b=>b.state==='APPROVED');
    const preview = el('div');
    panel.append(section('Publish an exam',
      form([['title','Exam title'],['instructions','Instructions','textarea'],
        ['bank','Approved bank',choices(banks,x=>x.title+' | Groups '+x.groups.join(', '))],
        ['groups','Eligible group IDs, comma-separated'],['students','Optional student account IDs, comma-separated','textarea'],
        ['navigation','Navigation',[['FREE','Free navigation'],['FORWARD','Forward only']]],['mode','Mode',[['OFFICIAL','Official'],['PRACTICE','Practice']]],
        ['duration','Duration in minutes','number','60'],['attempts','Attempt limit','number','1'],
        ['feedback','Practice feedback',[['SCORE','Immediate score'],['DELAYED','After release'],['EXPLANATIONS','Immediate score and explanations']]],
        ['opens','Opens (local time)','datetime-local'],['closes','Closes (local time)','datetime-local'],
        ['pass','Passing marks','number','1'],['easy','Easy questions','number','1'],
        ['medium','Medium questions','number','0'],['hard','Hard questions','number','0']],
        'Validate and preview',async fields=>{
          const configuration={...fields,duration:Number(fields.duration)*60,
            opens:new Date(fields.opens).toISOString(),closes:new Date(fields.closes).toISOString()};
          const result=await api('preview',configuration);
          preview.replaceChildren(el('p',{},result.students+' eligible student(s) | '+result.questions+' questions | '+result.marks+' marks'),
            el('p',{className:'muted'},'Sample selection. Publication draws a fresh set from the same difficulty mix.'),
            ...result.sample.map(q=>el('details',{},el('summary',{},q.prompt),el('ol',{},q.options.map(o=>el('li',{},o))))),
            button('Publish this configuration',async()=>{
              if (confirm('Publish this exam to the selected groups?')) await action('publish',staffHome)(configuration);
            }));
        }),preview),
      form([['exam','Exam',choices(exams.filter(x=>!x.released),x=>x.title)]],'Release results',async fields=>{
        if (confirm('Release marks and answer explanations to students?')) await action('release',staffHome)(fields);
      }));
  }
  return panel;
}
function exportCsv(rows,name) {
  if (!rows.length) throw new Error('There are no rows to export.');
  const escape = value => {
    let text = String(value ?? '');
    if (typeof value === 'string' && /^[\s]*[=+\-@]/.test(text)) text = "'" + text;
    return '"' + text.replaceAll('"','""') + '"';
  };
  const columns = Object.keys(rows[0]);
  const csv = [columns,...rows.map(r=>columns.map(c=>r[c]))].map(row=>row.map(escape).join(',')).join('\r\n');
  download(new Blob([csv],{type:'text/csv;charset=utf-8'}),name);
}
function download(blob,name) {
  const url = URL.createObjectURL(blob);
  el('a',{href:url,download:name}).click();
  setTimeout(()=>URL.revokeObjectURL(url),1000);
}
function questionEditor(banks) {
  const image = el('input',{type:'file',accept:'image/png,image/jpeg'});
  return section('Add a question',el('label',{},'Optional image (PNG/JPEG, up to 30 KiB)',image),
    form([['bank','Draft bank',banks],...questionFields],'Add question',async fields=>{
      const file = image.files[0];
      if (file) {
        if (file.size > 30720) throw new Error('Image must be 30 KiB or smaller.');
        fields.image = await new Promise((resolve,reject)=>{
          const reader = new FileReader();
          reader.onload = ()=>resolve(reader.result);
          reader.onerror = ()=>reject(new Error('Image could not be read.'));
          reader.readAsDataURL(file);
        });
      }
      await action('questions')(fields);
    }));
}
const csvHeaders = ['prompt','a','b','c','d','correct','explanation','topic','difficulty','marks','penalty'];
function csvRows(text, expected = csvHeaders) {
  const rows = [];
  let row=[], cell='', quoted=false, ended=false;
  text = text.replace(/^\uFEFF/,'');
  const next = ()=>{row.push(cell);cell='';ended=false;};
  for (let i=0;i<text.length;i++) {
    const ch=text[i];
    if (quoted) {
      if (ch!=='"') cell+=ch;
      else if (text[i+1]==='"') {cell+='"';i++;}
      else {quoted=false;ended=true;}
    } else if (ch===',' || ch==='\n' || ch==='\r') {
      next();
      if (ch!==',') {rows.push(row);row=[];if(ch==='\r' && text[i+1]==='\n')i++;}
    } else if (ch==='"' && cell==='' && !ended) quoted=true;
    else if (ended || ch==='"') throw new Error('Invalid quoted field on CSV row '+(rows.length+1));
    else cell+=ch;
  }
  if (quoted) throw new Error('A quoted CSV field was not closed.');
  next();
  if (row.some(Boolean)) rows.push(row);
  return rowsToQuestions(rows,expected);
}
function rowsToQuestions(rows, expected = csvHeaders) {
  const headers=rows.shift()?.map(s=>String(s).trim().toLowerCase()) || [];
  if (headers.length!==expected.length || new Set(headers).size!==headers.length ||
      expected.some(h=>!headers.includes(h))) throw new Error('Use the template column headers.');
  return rows.filter(r=>r.some(Boolean)).map((r,index)=>{
    if (r.length!==headers.length) throw new Error('Wrong column count on row '+(index+2));
    return Object.fromEntries(headers.map((key,i)=>[key,String(r[i] ?? '')]));
  });
}
function importPanel(banks) {
  const file = el('input',{type:'file',accept:'.csv,.xlsx'});
  const preview = el('div');
  let rows = [];
  file.onchange = ()=>{rows=[];preview.replaceChildren();};
  return section('Bulk question import',
    button('Download CSV template',()=>exportCsv([Object.fromEntries(csvHeaders.map(k=>[k,'']))],'question-template.csv'),'secondary'),
    el('label',{},'Question CSV or XLSX file',file),
    button('Preview',async()=>{
      const selected=file.files[0];
      if (!selected || selected.size>256000) throw new Error('Select a file under 250 KiB.');
      if (/\.xlsx$/i.test(selected.name)) {
        const bytes = new Uint8Array(await selected.arrayBuffer());
        if (bytes.length>42000) throw new Error('XLSX files must be under 42 KB. Use CSV for larger batches.');
        let binary = '';
        for (const byte of bytes) binary += String.fromCharCode(byte);
        rows = rowsToQuestions(await api('xlsx-preview',{file:btoa(binary)}));
      } else rows = csvRows(await selected.text());
      preview.replaceChildren(el('p',{},rows.length+' question(s) ready to validate and import.'),
        table(rows,[['prompt'],['correct'],['topic'],['difficulty'],['marks'],['penalty']]));
    }),preview,
    form([['bank','Draft bank',banks]],'Import previewed rows',async({bank})=>{
      if (!rows.length) throw new Error('Preview a file with questions first.');
      const result=await api('import-questions',{bank,rows:JSON.stringify(rows)});
      say(result.count+' question(s) imported. An identical retry returns the original count.');
    }));
}
function rosterPanel() {
  const keys=['username','password','role','group'];
  const file=el('input',{type:'file',accept:'.csv'});
  return section('Import student or faculty accounts',
    el('p',{},'Use STUDENT or FACULTY. Enter the academic group ID for each student. Passwords must be at least 12 characters.'),
    button('Download roster template',()=>exportCsv([Object.fromEntries(keys.map(key=>[key,'']))],'roster-template.csv'),'secondary'),
    el('label',{},'Roster CSV',file),
    button('Import roster',async()=>{
      const selected=file.files[0];
      if (!selected || selected.size>32000) throw new Error('Select a roster file under 32 KB.');
      const rows=csvRows(await selected.text(),keys);
      if (!rows.length || rows.length>100) throw new Error('Include 1 to 100 accounts.');
      if (!confirm('Create '+rows.length+' accounts? The entire batch will roll back if a row is invalid.')) return;
      const result=await api('import-accounts',{rows:JSON.stringify(rows)});
      say(result.count+' accounts created.');
      await staffHome();
    }));
}
function printReport(rows) {
  const report = el('section',{className:'print-report'},el('h1',{},'CampusExam results'),
    table(rows,[['username'],['group_id'],['status'],['answered'],['total'],['score'],['passed']]));
  document.body.append(report);
  try { window.print(); } finally { report.remove(); }
}
window.addEventListener('online',()=>void sync(true));
if (session) home().catch(error=>say(error.message)); else showLogin();
