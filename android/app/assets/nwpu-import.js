/* NWPU print-data adapter. No native bridge, password access or external requests.
 * Schema evidence: qllokirin/nonebot-plugin-npu, draw_course_schedule_pic.py.
 * Export the pure parser so fixtures can run under Node without a WebView. */
(function (root) {
    'use strict';
    function text(v) { return typeof v === 'string' ? v.trim() : ''; }
    function integer(v, min, max, label) {
        if (typeof v !== 'number' || !Number.isInteger(v) || v < min || v > max)
            throw new Error(label + '格式不受支持，请保留原课表并反馈页面变化');
        return v;
    }
    function date(v) {
        const m = text(v).match(/^(\d{4})-(\d{1,2})-(\d{1,2})$/);
        if (!m) return '';
        const d = new Date(Date.UTC(+m[1], +m[2] - 1, +m[3]));
        if (d.getUTCFullYear() !== +m[1] || d.getUTCMonth() !== +m[2] - 1 || d.getUTCDate() !== +m[3]) return '';
        return m[1] + '-' + m[2].padStart(2, '0') + '-' + m[3].padStart(2, '0');
    }
    function clock(v) {
        const n = typeof v === 'string' && /^\d{3,4}$/.test(v) ? +v : v;
        integer(n, 0, 2359, '作息时间');
        if (n % 100 > 59) throw new Error('作息时间无效');
        return String(Math.floor(n / 100)).padStart(2, '0') + ':' + String(n % 100).padStart(2, '0');
    }
    function normalize(data, semesterId, semesterName) {
        const vm = data && data.studentTableVm;
        if (!vm || !Array.isArray(vm.activities)) throw new Error('没有读到课表，请确认登录成功并打开个人课表');
        if (vm.activities.length > 2000) throw new Error('课表数据过大，暂不支持');
        const lessons = [], seen = new Set(), names = new Set();
        vm.activities.forEach(a => {
            const name = text(a.courseName);
            if (!name || name.length > 200) throw new Error('课程名称缺失或过长');
            if (!Array.isArray(a.weekIndexes) || !a.weekIndexes.length) throw new Error(name + '缺少上课周次');
            const weeks = Array.from(new Set(a.weekIndexes.map(w => integer(w, 1, 60, '周次')))).sort((a, b) => a - b);
            const weekday = integer(a.weekday, 1, 7, '星期');
            const start = integer(a.startUnit, 1, 30, '开始节次');
            const end = integer(a.endUnit, start, 30, '结束节次');
            if (a.teachers != null && (!Array.isArray(a.teachers) || a.teachers.some(t => typeof t !== 'string')))
                throw new Error('教师字段格式发生变化');
            if (a.room != null && typeof a.room !== 'string') throw new Error('教室字段格式发生变化');
            const lesson = {name, weeks, weekday, start, end, room: text(a.room), teachers: (a.teachers || []).map(text).filter(Boolean).join('、')};
            const key = JSON.stringify(lesson);
            if (!seen.has(key)) { seen.add(key); lessons.push(lesson); }
            names.add(name);
        });
        if (!lessons.length) throw new Error('该学期没有已排课课程，原课表保持不变');
        lessons.sort((a, b) => a.weekday - b.weekday || a.start - b.start || a.name.localeCompare(b.name));
        const units = [];
        const sourceUnits = vm.timeTableLayout && vm.timeTableLayout.courseUnitList;
        if (sourceUnits != null && !Array.isArray(sourceUnits)) throw new Error('作息表格式发生变化');
        (sourceUnits || []).forEach((u, i) => {
            // Array order represents period order in NWPU's print-data layout.
            const start = clock(u.startTime), end = clock(u.endTime);
            if (start >= end) throw new Error('作息表起止时间无效');
            units.push({number: i + 1, start, end});
        });
        const arranged = Array.isArray(vm.arrangedLessonSearchVms) ? vm.arrangedLessonSearchVms : [];
        const semester = arranged.length && arranged[0].semester || {};
        const unarranged = [];
        arranged.forEach(a => {
            const n = text(a.course && (a.course.nameZh || a.course.name)) || text(a.courseName);
            if (n && !names.has(n) && !unarranged.includes(n)) unarranged.push(n);
        });
        return {version: 1, school: '西北工业大学', termId: String(semesterId), termName: text(semesterName) || String(semesterId),
            startDate: date(semester.startDate), lessons, units, unarranged};
    }
    function context(win, doc) {
        const select = doc.querySelector('#allSemesters, select[name="semester"], select[id*="semester"], select[id*="Semester"]');
        const option = select && select.options[select.selectedIndex];
        const id = option ? String(option.value) : String(win.semesterId || (win.currentSemester || {}).id || '');
        if (!/^\d+$/.test(id)) throw new Error('请先打开个人课表并选择学期');
        const path = win.location.pathname.match(/\/course-table\/(\d+)\/?$/);
        const input = doc.querySelector('#studentId, input[name="studentId"]');
        const identities = doc.querySelectorAll('button.footer.btn.btn-primary[value]');
        const student = path ? path[1] : input && input.value || win.dataId || win.studentId ||
            (identities.length === 1 ? identities[0].value : '');
        if (!/^\d+$/.test(String(student))) throw new Error('请在教务网页中选择学生身份并打开个人课表');
        return {id, name: option ? option.textContent.trim() : '', student: String(student)};
    }
    function allowed(location) {
        return location.protocol === 'https:' && location.hostname === 'jwxt.nwpu.edu.cn' &&
            (!location.port || location.port === '443') && /^\/student\/for-std\/course-table(?:\/|$)/.test(location.pathname);
    }
    function start(token) {
        root.__louTimetableResult = {token, status: 'loading'};
        (async function () {
            try {
                if (!allowed(root.location)) throw new Error('请登录后进入个人课表页面');
                const c = context(root, root.document);
                const controller = new AbortController();
                const timer = setTimeout(() => controller.abort(), 15000);
                let response;
                try {
                    response = await root.fetch('/student/for-std/course-table/semester/' + c.id + '/print-data/' + c.student + '?hasExperiment=true',
                        {method: 'GET', credentials: 'same-origin', redirect: 'error', signal: controller.signal,
                            headers: {'Accept': 'application/json', 'X-Requested-With': 'XMLHttpRequest'}});
                    if (!response.ok) throw new Error('教务系统返回 ' + response.status + '，请重新登录或稍后重试');
                    const body = await response.text();
                    if (body.length > 2000000) throw new Error('课表响应过大');
                    const data = normalize(JSON.parse(body), c.id, c.name);
                    root.__louTimetableResult = {token, status: 'ok', data};
                } finally { clearTimeout(timer); }
            } catch (e) {
                // Do not return response bodies, cookies, IDs or server error details to Android/logs.
                const known = /^(请|没有|该学期|课表|教务系统返回|课程名称|周次|星期|开始节次|结束节次|作息|教师字段|教室字段)/.test(e.message || '');
                root.__louTimetableResult = {token, status: 'error', message: known ? e.message : '读取失败，请确认登录有效、网络可用且课表页面已经加载'};
            }
        })();
    }
    const api = {normalize, context, allowed, start};
    if (typeof module !== 'undefined' && module.exports) module.exports = api;
    else root.NwpuImport = api;
})(typeof window === 'undefined' ? globalThis : window);
