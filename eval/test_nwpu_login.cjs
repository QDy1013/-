// Synthetic DOM; never uses a real account or makes network requests.
const assert = require('node:assert/strict');
const api = require('../android/app/assets/nwpu-login.js');
let checks = 0, controls, formAction, clicks, changes, errors, challenges;
class Input {
    constructor() { this._value = ''; this.events = []; }
    get value() { return this._value; }
    set value(v) { this._value = v; }
    dispatchEvent(e) { this.events.push(e.type); }
}
function setup(mobile = false) {
    global.location = new URL('https://uis.nwpu.edu.cn/cas/login?service=school');
    global.HTMLInputElement = Input;
    global.Event = class { constructor(type) { this.type = type; } };
    global.getComputedStyle = () => ({visibility: 'visible'});
    global.jQuery = function () {}; global.mfaDetect = function () {};
    global.__louLoginSent = false;
    clicks = 0; changes = []; errors = []; challenges = [];
    global.vm = {guardDialogVisible: false, changeMenu: value => changes.push(value)};
    controls = {username: new Input(), password: new Input(), button: {click: () => clicks++}};
    formAction = '/cas/login?service=school';
    const form = {getAttribute: () => formAction, querySelector: selector => {
        if (selector === '#username') return controls.username;
        if (selector === '#password') return controls.password;
        if (selector === '[name="button"]') return mobile ? null : controls.button;
        if (selector.startsWith('input[type="submit"]')) return controls.button;
        return null;
    }};
    global.document = {querySelector: s => s === '#fm1' ? form : null,
        querySelectorAll: s => s.includes('el-message--error') ? errors : challenges};
}
function check(name, fn) { setup(); fn(); checks++; console.log('PASS ' + name); }
check('desktop official button clicked once', () => {
    assert.equal(api.submit('test-user', 'secret').phase, 'submitted'); assert.equal(clicks, 1);
    assert.deepEqual(changes, ['1']); assert.deepEqual(controls.password.events, ['input', 'change']);
});
check('mobile official submit button is supported', () => { setup(true); assert.equal(api.inspect().phase, 'ready'); assert.equal(api.submit('test-user', 'secret').phase, 'submitted'); assert.equal(clicks, 1); });
check('repeat polling never resubmits credentials', () => { api.submit('u', 'p'); api.submit('u', 'p'); assert.equal(clicks, 1); });
check('only the exact school authentication origin is accepted', () => {
    for (const u of ['http://uis.nwpu.edu.cn/cas/login', 'https://uis.nwpu.edu.cn.evil.test/cas/login', 'https://uis.nwpu.edu.cn:8443/cas/login', 'https://jwxt.nwpu.edu.cn/student/login']) assert.equal(api.trusted(new URL(u)), false);
});
check('untrusted pages receive no credentials', () => {
    global.location = new URL('https://example.com/cas/login'); api.submit('u', 'p'); assert.equal(controls.username.value, ''); assert.equal(controls.password.value, ''); assert.equal(clicks, 0);
});
check('unexpected form actions cannot receive credentials', () => { formAction = 'https://example.com/steal'; assert.equal(api.submit('u', 'p').phase, 'error'); assert.equal(controls.password.value, ''); assert.equal(clicks, 0); });
check('missing form does not submit', () => { global.document.querySelector = () => null; assert.equal(api.inspect().phase, 'loading'); assert.equal(api.submit('u', 'p').phase, 'loading'); assert.equal(clicks, 0); });
check('security challenge is reported without touching it', () => { global.vm.guardDialogVisible = true; assert.equal(api.inspect().phase, 'challenge'); assert.equal(api.submit('u', 'p').phase, 'loading'); assert.equal(clicks, 0); });
check('DOM challenge preserves school verification', () => { challenges = [{offsetWidth: 20, getClientRects: () => [{}]}]; assert.equal(api.inspect().phase, 'challenge'); assert.equal(clicks, 0); });
check('errors expose fixed messages instead of server text', () => { errors = [{offsetWidth: 10, textContent: '用户名 wrong-user 密码 wrong-password 错误', getClientRects: () => [{}]}]; const s = JSON.stringify(api.inspect()); assert.equal(s.includes('wrong-user'), false); assert.equal(s.includes('wrong-password'), false); assert.equal(api.inspect().phase, 'error'); });
check('status never contains username or password', () => { api.submit('PRIVATE_USER', 'PRIVATE_PASS'); assert.equal(JSON.stringify(api.inspect()).includes('PRIVATE'), false); });
check('special characters go through native input setters unchanged', () => { const p = '"<>&\'\\密码'; api.submit('u', p); assert.equal(controls.password.value, p); });
check('page readiness waits for the school handlers', () => { delete global.mfaDetect; assert.equal(api.inspect().phase, 'loading'); assert.equal(clicks, 0); });
console.log(checks + ' checks passed');
