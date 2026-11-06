/* Undergraduate field-test adapter. Unknown structures fail closed, retaining the previous timetable.
 * It uses the school's real login button and never solves a security challenge. */
(function (root) {
    'use strict';
    let config;
    const aliases = {
        name: ['课程名称', '课程名', '课程'], weeks: ['周次', '上课周次', '教学周', '周数', '起止周'],
        day: ['星期', '周几', '上课星期', '星期几'], start: ['开始节次', '开始节', '起始节次', '节次', '上课节次'],
        end: ['结束节次', '结束节'], count: ['节数', '持续节数', '上课节数'],
        room: ['教室', '上课地点', '教室名称', '地点', '上课教室'], teacher: ['教师', '任课教师', '教师姓名', '老师']
    };
    function clean(s) { return String(s == null ? '' : s).replace(/\u00a0/g, ' ').trim(); }
    function hostAllowed(host, domains) {
        return domains.some(d => host === d || (d.endsWith('.edu.cn') && host.endsWith('.' + d)));
    }
    function trusted(url, credentials) {
        try { const u = new URL(url); return u.protocol === 'https:' && (!u.port || u.port === '443') && !u.username && !u.password && hostAllowed(u.hostname, credentials ? config.credentialDomains : config.domains); }
        catch (_) { return false; }
    }
    function setup(c) {
        config = c;
        if (!root.__louSchoolState || root.__louSchoolState.id !== c.id) root.__louSchoolState = {id: c.id, sent: false, visited: []};
    }
    function visible(e) { return !!e && !!(e.offsetWidth || e.offsetHeight || e.getClientRects().length) && root.getComputedStyle(e).visibility !== 'hidden'; }
    function ranges(value, max, week) {
        let s = clean(value).replace(/[（]/g, '(').replace(/[）]/g, ')');
        let parity = 0;
        if (week && /单/.test(s)) parity = 1;
        if (week && /双/.test(s)) { if (parity) throw new Error('RANGE'); parity = 2; }
        if (parity && !/[单双](?:周)?\)?$/.test(s)) throw new Error('RANGE');
        s = s.replace(/\((?:单|双)(?:周)?\)|(?:单|双)周?|第|周上|周|节|每|\s/g, '').replace(/[~～—–至]/g, '-').replace(/[，、;]/g, ',');
        if (!/^\d+(?:-\d+)?(?:,\d+(?:-\d+)?)*$/.test(s)) throw new Error('RANGE');
        const result = new Set();
        for (const part of s.split(',')) {
            const a = part.split('-').map(Number), end = a[1] || a[0];
            if (a[0] < 1 || end < a[0] || end > max) throw new Error('RANGE');
            for (let n = a[0]; n <= end; n++) if (!parity || n % 2 === (parity === 1 ? 1 : 0)) result.add(n);
        }
        if (!result.size) throw new Error('RANGE');
        return Array.from(result).sort((a,b) => a-b);
    }
    function date(value) {
        const m = clean(value).match(/^(20\d{2})[-年/](\d{1,2})[-月/](\d{1,2})(?:日)?$/);
        if (!m) return '';
        const d = new Date(Date.UTC(+m[1], +m[2]-1, +m[3]));
        if (d.getUTCFullYear() !== +m[1] || d.getUTCMonth() !== +m[2]-1 || d.getUTCDate() !== +m[3]) return '';
        return `${m[1]}-${m[2].padStart(2,'0')}-${m[3].padStart(2,'0')}`;
    }
    function weekday(s) {
        s = clean(s).replace(/星期|周/g, '');
        const n = /^[1-7]$/.test(s) ? +s : /^[一二三四五六日天]$/.test(s) ? '一二三四五六日'.indexOf(s === '天' ? '日' : s) + 1 : 0;
        if (n < 1 || n > 7 || !s) throw new Error('DAY'); return n;
    }
    function column(headers, names) { return headers.findIndex(h => names.includes(clean(h).replace(/\s/g, ''))); }
    function parseRows(headers, rows, context) {
        const cols = {}; Object.keys(aliases).forEach(k => cols[k] = column(headers, aliases[k]));
        if (['name','weeks','day','start'].some(k => cols[k] < 0)) throw new Error('UNSUPPORTED_HEADERS');
        const startDate = date(context.startDate);
        if (!startDate) throw new Error('MISSING_DATE');
        if (rows.length > 2000) throw new Error('SIZE');
        const lessons = [], unarranged = [], seen = new Set();
        const get = (row, key) => cols[key] < 0 ? '' : clean(row[cols[key]]);
        for (const row of rows) {
            if (row.every(v => !clean(v)) || get(row,'name') === clean(headers[cols.name])) continue;
            const name = get(row, 'name'); if (!name || name.length > 200) throw new Error('NAME');
            if (!get(row,'weeks') && !get(row,'day') && !get(row,'start')) { if (!unarranged.includes(name)) unarranged.push(name); continue; }
            const weeks = ranges(get(row,'weeks'), 60, true), day = weekday(get(row,'day'));
            let units = ranges(get(row,'start'), 30, false);
            if (cols.end >= 0 && get(row,'end')) {
                const end = Number(get(row,'end')); if (units.length !== 1 || !Number.isInteger(end) || end < units[0] || end > 30) throw new Error('UNITS');
                units = ranges(units[0] + '-' + end, 30, false);
            } else if (cols.count >= 0 && get(row,'count')) {
                const count = Number(get(row,'count')); if (units.length !== 1 || !Number.isInteger(count) || count < 1 || units[0]+count-1 > 30) throw new Error('UNITS');
                units = ranges(units[0] + '-' + (units[0]+count-1), 30, false);
            }
            const place = ['校区','教学楼'].map(h => { const i=column(headers,[h]);return i < 0 ? '' : clean(row[i]); }).concat(get(row,'room')).filter(Boolean);
            for (let i=0; i<units.length; i++) {
                const start=units[i]; let end=start;
                while (i+1<units.length && units[i+1]===end+1) end=units[++i];
                const lesson={name,weeks,weekday:day,start,end,room:Array.from(new Set(place)).join(' '),teachers:get(row,'teacher')};
                const key=JSON.stringify(lesson); if(!seen.has(key)){seen.add(key);lessons.push(lesson);}
            }
        }
        if (!lessons.length) throw new Error('EMPTY');
        lessons.sort((a,b)=>a.weekday-b.weekday || a.start-b.start || a.name.localeCompare(b.name));
        return {version:1,school:context.school,termId:'current',termName:clean(context.termName)||'当前学期',startDate,lessons,units:[],unarranged};
    }
    function matrix(table) {
        const rows=[];
        const source=Array.from(table.querySelectorAll('tr')).filter(r=>r.closest('table')===table);
        source.forEach((row,y)=>{
            rows[y]=rows[y]||[];let x=0;
            Array.from(row.children).filter(c=>/^(TD|TH)$/.test(c.tagName)).forEach(cell=>{
                while(rows[y][x]!==undefined)x++;
                const rs=Math.max(1,Math.min(100,cell.rowSpan||1)),cs=Math.max(1,Math.min(50,cell.colSpan||1));
                for(let dy=0;dy<rs;dy++){rows[y+dy]=rows[y+dy]||[];for(let dx=0;dx<cs;dx++)rows[y+dy][x+dx]=clean(cell.innerText||cell.textContent);}
                x+=cs;
            });
        });return rows;
    }
    function documents() {
        const result=[root.document];
        Array.from(root.document.querySelectorAll('iframe,frame')).forEach(f=>{try{if(f.contentDocument)result.push(f.contentDocument);}catch(_){}});
        return result;
    }
    function pageDate(docs) {
        for(const d of docs){
            const text=(d.body&&d.body.innerText)||'';
            const m=text.match(/(?:学期起始日期|开学日期|第一周周一|第1周周一|第一周开始日期)[\s:：]*(20\d{2}[-年/]\d{1,2}[-月/]\d{1,2}(?:日)?)/);
            if(m&&date(m[1]))return date(m[1]);
            const e=d.querySelector('input[name="startDate"],input[name="semesterStartDate"],input[id="semesterStartDate"]');
            if(e&&date(e.value))return date(e.value);
        }return '';
    }
    function tableResult(docs) {
        let term='当前学期';
        for(const d of docs){
            const s=d.querySelector('select[name="xnxq"],select[id="xnxq"],select[name="semester"],select[id="allSemesters"]');
            if(s&&s.options[s.selectedIndex]){const value=clean(s.options[s.selectedIndex].textContent);if(/20\d{2}/.test(value))term=value;}
        }
        for(const d of docs)for(const t of Array.from(d.querySelectorAll('table'))){
            const rows=matrix(t),h=rows.findIndex(r=>column(r,aliases.name)>=0&&column(r,aliases.weeks)>=0&&column(r,aliases.day)>=0&&column(r,aliases.start)>=0);
            if(h>=0)return parseRows(rows[h],rows.slice(h+1),{school:config.name,termName:term,startDate:pageDate(docs)});
        }
        return null;
    }
    function loginForm(docs) {
        if(!trusted(root.location.href,true))return null;
        for(const d of docs)for(const f of Array.from(d.forms||[])){
            const passwords=Array.from(f.querySelectorAll('input[type="password"]')).filter(visible);
            if(passwords.length!==1)continue;
            const p=passwords[0],fields=Array.from(f.querySelectorAll('input')).filter(e=>visible(e)&&/^(text|email|tel|number)$/.test(e.type)&&!/captcha|verify|code|验证码/i.test(e.id+' '+e.name+' '+e.placeholder));
            const named=fields.filter(e=>/username|usercode|useraccount|account|loginname|studentid|yhm|xh|工号|学号|用户名|账号|账户/i.test(e.id+' '+e.name+' '+e.placeholder));
            const user=named.length===1?named[0]:fields.length===1?fields[0]:null;
            const buttons=Array.from(f.querySelectorAll('button,input[type="submit"],input[type="button"],[role="button"]')).filter(e=>visible(e)&&/^(登\s*录|用户登录|账号登录|登录系统|Sign in|Log in|Login|LOGIN)$/i.test(clean(e.innerText||e.value||e.textContent)));
            if(user&&buttons.length===1)return {f,user,password:p,button:buttons[0]};
        }return null;
    }
    function challenge(docs) {
        return docs.some(d=>Array.from(d.querySelectorAll('.geetest_panel,.yidun_panel,.verifybox,.verify-box,.captcha-modal,.el-dialog__wrapper,input[name="captcha"],input[id="captcha"]')).some(visible));
    }
    function navigation(docs) {
        const priorities=config.id==='hitwh'&&root.location.hostname==='ids.hit.edu.cn'?['威海校区','威海']:['学生入口','学生登录','统一身份认证登录','统一身份认证','CAS登录','教务系统','我的课表','个人课表','学生课表','教学安排表'];
        for(const label of priorities){
            const found=[];
            for(const d of docs)for(const e of Array.from(d.querySelectorAll('a,button,[role="menuitem"]'))){
                if(visible(e)&&clean(e.innerText||e.textContent).replace(/\s/g,'')===label){
                    const href=e.getAttribute('href');if(href&&!href.startsWith('javascript:')&&href!=='#'&&!trusted(new URL(href,d.location.href).href,false))continue;
                    found.push(e);
                }
            }
            if(found.length===1)return found[0];
        }return null;
    }
    function safeAttribute(value) {value=String(value||'');return /^[A-Za-z_][A-Za-z0-9_.-]{0,40}$/.test(value)&&!/[0-9]{5}/.test(value)?value:value?'[field]':'';}
    function diagnostics() {
        const docs=documents(),headers=new Set();
        docs.forEach(d=>Array.from(d.querySelectorAll('th')).forEach(e=>{
            const s=clean(e.textContent).replace(/\s/g,'');if(Object.values(aliases).some(a=>a.includes(s))||['校区','教学楼'].includes(s))headers.add(s);
        }));
        return {documentCount:docs.length,ready:root.document.readyState,forms:docs.flatMap(d=>Array.from(d.forms||[]).slice(0,5).map(f=>({id:safeAttribute(f.id),inputs:Array.from(f.querySelectorAll('input')).slice(0,20).map(e=>({id:safeAttribute(e.id),name:safeAttribute(e.name),type:safeAttribute(e.type)}))}))),headers:Array.from(headers),tables:docs.flatMap(d=>Array.from(d.querySelectorAll('table')).slice(0,8).map(t=>({rows:t.rows?t.rows.length:0}))),hasSemesterDate:!!pageDate(docs)};
    }
    function inspect() {
        if(!trusted(root.location.href,false))return {phase:'error',message:'学校页面暂时无法识别，请点“反馈问题”'};
        const docs=documents();
        const errors=docs.flatMap(d=>Array.from(d.querySelectorAll('.error,.error-msg,.el-message--error,#msg,#errorMessage,.alert-danger'))).filter(visible);
        if(errors.some(e=>/用户名或密码错误|账号或密码错误|用户名不存在|登录失败|账户已锁定|密码不正确|验证码错误/.test(e.innerText||e.textContent||'')))return {phase:'error',message:'学校没有通过登录验证，请检查后重试'};
        try{const data=tableResult(docs);if(data)return {phase:'courses',data};}
        catch(e){return {phase:'error',message:e.message==='MISSING_DATE'?'课程已找到，但暂时没读到开学日期。请点“反馈问题”':e.message==='EMPTY'?'这个学期暂时没有已排课课程':'已找到课表，但还需要适配这个学校的格式。请点“反馈问题”'};}
        const f=loginForm(docs);
        if(root.__louSchoolState.sent&&challenge(docs))return {phase:'challenge'};
        if(f)return {phase:root.__louSchoolState.sent?'submitted':'ready'};
        if(challenge(docs))return {phase:'challenge'};
        if(navigation(docs))return {phase:'navigate'};
        return {phase:'loading'};
    }
    function submit(username,password) {
        const item=loginForm(documents());if(!item||root.__louSchoolState.sent)return {phase:'loading'};
        const action=new URL(item.f.getAttribute('action')||root.location.href,root.location.href);
        if(!trusted(root.location.href,true)||!trusted(action.href,true)||action.origin!==root.location.origin)return {phase:'error',message:'学校登录方式需要进一步确认，请点“反馈问题”'};
        if(!username||!password)return {phase:'error',message:'请填写账号和密码'};
        const setter=Object.getOwnPropertyDescriptor(root.HTMLInputElement.prototype,'value').set;
        for(const [input,value] of [[item.user,username],[item.password,password]]){setter.call(input,value);input.dispatchEvent(new root.Event('input',{bubbles:true}));input.dispatchEvent(new root.Event('change',{bubbles:true}));}
        root.__louSchoolState.sent=true;item.button.click();return {phase:'submitted'};
    }
    function navigate() {
        const e=navigation(documents());if(!e)return false;
        const key=clean(e.innerText||e.textContent)+'|'+(e.getAttribute('href')||'');
        if(root.__louSchoolState.visited.includes(key)||root.__louSchoolState.visited.length>=6)return false;
        root.__louSchoolState.visited.push(key);e.click();return true;
    }
    const api={setup,trusted,ranges,date,weekday,parseRows,matrix,inspect,submit,navigate,diagnostics};
    if(typeof module!=='undefined'&&module.exports)module.exports=api;else root.SchoolImport=api;
})(typeof window==='undefined'?globalThis:window);
