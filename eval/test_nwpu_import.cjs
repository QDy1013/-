// Run: node eval/test_nwpu_import.cjs. Fixtures are synthetic, never real student data.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const api = require('../android/app/assets/nwpu-import.js');
let checks = 0;
function check(name, fn) { fn(); checks++; console.log('PASS ' + name); }
const lesson = {courseName: '高等数学', weekIndexes: [5, 1, 3, 3], weekday: 1, startUnit: 1, endUnit: 2, room: '教A101', teachers: ['张老师']};
function fixture() {
    return {studentTableVm: {activities: [structuredClone(lesson)],
        timeTableLayout: {courseUnitList: [{startTime: 800, endTime: 845}, {startTime: '0855', endTime: 940}]},
        arrangedLessonSearchVms: [{course: {nameZh: '高等数学'}, semester: {startDate: '2026-8-31'}}, {course: {nameZh: '认识实习'}}]}};
}
const parse = d => api.normalize(d, 'NEW_TERM_99', '2026–2027秋季');
check('normalize weeks without filling even weeks', () => assert.deepEqual(parse(fixture()).lessons[0].weeks, [1, 3, 5]));
check('semester is passed through instead of hardcoded', () => assert.equal(parse(fixture()).termId, 'NEW_TERM_99'));
check('date and period clocks', () => {
    const t = parse(fixture()); assert.equal(t.startDate, '2026-08-31');
    assert.deepEqual(t.units[1], {number: 2, start: '08:55', end: '09:40'});
});
check('retain unarranged courses', () => assert.deepEqual(parse(fixture()).unarranged, ['认识实习']));
check('deduplicate identical meetings', () => { const d = fixture(); d.studentTableVm.activities.push(structuredClone(lesson)); assert.equal(parse(d).lessons.length, 1); });
check('retain changed rooms, even for same course', () => { const d = fixture(); d.studentTableVm.activities.push({...lesson, room: '教B201'}); assert.equal(parse(d).lessons.length, 2); });
check('do not discard unknown invalid meetings', () => { const d = fixture(); d.studentTableVm.activities.push({...lesson, weekday: 9}); assert.throws(() => parse(d)); });
for (const [key, value] of [['weekIndexes', []], ['weekIndexes', [0]], ['weekIndexes', [61]], ['weekday', '1'], ['startUnit', 0], ['endUnit', 1.5], ['room', {}], ['teachers', [{}]], ['courseName', '']]) {
    check('reject invalid ' + key + ' ' + JSON.stringify(value), () => { const d = fixture(); d.studentTableVm.activities[0][key] = value; assert.throws(() => parse(d)); });
}
check('reject reversed periods', () => { const d = fixture(); d.studentTableVm.activities[0].startUnit = 3; assert.throws(() => parse(d)); });
check('reject impossible clocks', () => { const d = fixture(); d.studentTableVm.timeTableLayout.courseUnitList[0].endTime = 860; assert.throws(() => parse(d)); });
check('empty semester cannot replace an existing timetable', () => { const d = fixture(); d.studentTableVm.activities = []; assert.throws(() => parse(d), /原课表保持不变/); });
check('login HTML or an unknown schema is rejected', () => assert.throws(() => parse({html: 'login'})));
check('invalid start date stays unknown rather than fabricated', () => { const d = fixture(); d.studentTableVm.arrangedLessonSearchVms[0].semester.startDate = '2026-02-30'; assert.equal(parse(d).startDate, ''); });
check('missing time layout preserves sections without guessed times', () => { const d = fixture(); delete d.studentTableVm.timeTableLayout; assert.deepEqual(parse(d).units, []); });
check('do not export identifiers or unrelated student fields', () => {
    const d = fixture(); d.studentTableVm.student = {name: 'PRIVATE', studentId: 'PRIVATE'};
    assert.equal(JSON.stringify(parse(d)).includes('PRIVATE'), false);
});
check('only selected semester option is used', () => {
    const doc = {querySelector: s => s.startsWith('#allSemesters') ? {options: [{value: '1', textContent: '旧'}, {value: '99', textContent: '新'}], selectedIndex: 1} : {value: '123'}, querySelectorAll: () => []};
    const c = api.context({location: {pathname: '/student/for-std/course-table'}, semesterId: 1}, doc);
    assert.deepEqual(c, {id: '99', name: '新', student: '123'});
});
check('multiple identities must be selected on school page', () => {
    assert.throws(() => api.context({semesterId: 99, location: {pathname: '/student/for-std/course-table'}}, {querySelector: () => null, querySelectorAll: () => [{value: 1}, {value: 2}]}));
});
check('numeric identity in course-table path', () => {
    assert.equal(api.context({semesterId: 99, location: {pathname: '/student/for-std/course-table/765432'}}, {querySelector: () => null, querySelectorAll: () => []}).student, '765432');
});
check('exact HTTPS origin and course-table path required', () => {
    for (const u of ['http://jwxt.nwpu.edu.cn/student/for-std/course-table', 'https://jwxt.nwpu.edu.cn.evil.test/student/for-std/course-table', 'https://jwxt.nwpu.edu.cn:8443/student/for-std/course-table', 'https://jwxt.nwpu.edu.cn/student/home', 'https://jwxt.nwpu.edu.cn/student/for-std/course-table-evil']) assert.equal(api.allowed(new URL(u)), false);
    assert.equal(api.allowed(new URL('https://jwxt.nwpu.edu.cn/student/for-std/course-table/123')), true);
});

(async () => {
    global.location = new URL('https://jwxt.nwpu.edu.cn/student/for-std/course-table');
    global.document = {querySelector: s => s.startsWith('#allSemesters') ? {options: [{value: '99', textContent: '秋季'}], selectedIndex: 0} : {value: '123'}, querySelectorAll: () => []};
    const originalFetch = global.fetch;
    global.fetch = async (url, options) => {
        assert.equal(url, '/student/for-std/course-table/semester/99/print-data/123?hasExperiment=true');
        assert.equal(options.credentials, 'same-origin'); assert.equal(options.method, 'GET'); assert.equal(options.redirect, 'error');
        return {ok: true, text: async () => JSON.stringify(fixture())};
    };
    api.start('test'); await new Promise(resolve => setImmediate(resolve));
    check('signed-in request returns normalized preview, not raw response', () => { assert.equal(global.__louTimetableResult.status, 'ok'); assert.equal(global.__louTimetableResult.token, 'test'); });
    global.fetch = async () => ({ok: false, status: 412});
    api.start('denied'); await new Promise(resolve => setImmediate(resolve));
    check('HTTP failure leaves no candidate to save', () => { assert.equal(global.__louTimetableResult.status, 'error'); assert.equal(global.__louTimetableResult.data, undefined); });
    global.fetch = async () => ({ok: true, text: async () => '<html>LOGIN SECRET</html>'});
    api.start('expired'); await new Promise(resolve => setImmediate(resolve));
    check('expired login produces bounded generic error', () => { assert.equal(global.__louTimetableResult.status, 'error'); assert.equal(JSON.stringify(global.__louTimetableResult).includes('SECRET'), false); });
    global.fetch = originalFetch;
    if (process.argv[2]) fs.writeFileSync(path.resolve(process.argv[2]), JSON.stringify(parse(fixture())), 'utf8');
    console.log(checks + ' checks passed');
})().catch(e => { console.error(e); process.exitCode = 1; });
